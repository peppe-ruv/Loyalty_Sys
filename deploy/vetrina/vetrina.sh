#!/usr/bin/env bash
# Vetrina enterprise ospitata: comandi dell'host (F2-DIST-03, F2-DIST-09, ADR-049, ADR-050, M8.14 V3 e V7; Q-616,
# Q-620, Q-621, Q-624, Q-660…Q-663).
#
# Avvia il compose di riferimento con l'overlay di vetrina (deploy/vetrina/compose.vetrina.yml). Due modalità
# (LH_VETRINA_MODE): `host`, un host fisso con il proxy ACME sulle porte 80 e 443 (ADR-049, arm64 per default), e
# `codespace`, un GitHub Codespace acceso su richiesta (ADR-050) con l'overlay deploy/vetrina/compose.codespace.yml:
# niente ACME, il TLS lo termina l'inoltro delle porte di GitHub. Runbook completo: deploy/vetrina/README.md.
#
#   vetrina.sh codespace            solo nel codespace (Q-663, ADR-051): scrive la configurazione dall'ambiente del
#                                   codespace, provisioning, controlli, database di Keycloak RICREATO DA ZERO (Q-672),
#                                   avvio, overlay dei realm (master, operatori, membri, utenti di test), account
#                                   operatore e membri di test (Q-673)
#
#   vetrina.sh provision            segreti, CA locale e certificati di Postgres e Kafka (idempotente, regola 20)
#   vetrina.sh preflight [--offline]
#                                   controlli prima dell'avvio: configurazione, architettura arm64 dell'host e delle
#                                   immagini, permessi dei segreti, certificati, nessun segreto nell'ambiente
#   vetrina.sh up                   preflight e avvio (docker compose up -d --wait)
#   vetrina.sh down                 arresto, volumi conservati
#   vetrina.sh reset                azzeramento settimanale (Q-624): volumi di Postgres e Kafka ricreati, realm
#                                   reimportato, overlay del realm, account operatore; nessun backup
#   vetrina.sh idp-reset            ricrea da zero il database `idp` di Keycloak (non quello dell'hub): Keycloak
#                                   reimporta i realm e gli overlay si riapplicano (ADR-051 decisione 10, Q-672);
#                                   lo fa da solo `codespace` a ogni avvio
#   vetrina.sh membri               membri di test dal portale (Anna, Marco, Giulia registrati; Laura da zero, Q-673):
#                                   solo nel codespace, con hub e web su e le porte pubbliche
#   vetrina.sh operators            account operatore nominativi da operators.list (Q-618), password temporanee in un
#                                   file 0600, poi verifica della MFA (apply-overlay.sh --check-operators)
#   vetrina.sh programma            configurazione di programma da seed/ con il token di un operatore (Q-617, Q-626):
#                                   passo interattivo (Device Authorization Grant con MFA), mai nel timer (Q-630)
#   vetrina.sh compose <argomenti>  docker compose con i file e il progetto della vetrina (ps, logs …)
#
# Configurazione: file KEY=VALORE (LH_VETRINA_CONFIG, default /etc/loyaltyhub-vetrina/vetrina.env; modello in
# vetrina.env.example). Solo le chiavi ammesse, nessun segreto. I segreti stanno in $LH_VETRINA_DIR/secrets e
# $LH_VETRINA_DIR/tls, file 0600 creati da `provision`; lo script non stampa mai un valore, solo nomi e percorsi.
# Utenti di test (ADR-051 decisione 1): solo nel codespace; sull'host fisso gli overlay si applicano senza utenti.
# Uscita: 0 ok · 1 controllo o comando fallito · 2 uso o configurazione non validi.
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
REFERENCE="$REPO_ROOT/deploy/compose/reference.yml"
OVERLAY="$SCRIPT_DIR/compose.vetrina.yml"
CODESPACE_OVERLAY="$SCRIPT_DIR/compose.codespace.yml"
PROJECT="loyaltyhub-vetrina"
CONFIG="${LH_VETRINA_CONFIG:-/etc/loyaltyhub-vetrina/vetrina.env}"

# Utenti dei container che leggono i file montati: immagine unica, Keycloak e apache/kafka girano come 1000; la chiave
# del server Postgres deve essere dell'utente postgres dell'immagine Debian (999), con permessi 0600.
CONTAINER_UID=1000
POSTGRES_UID=999

# Chiavi ammesse nel file di configurazione (nessun segreto).
CONFIG_KEYS=(LH_VETRINA_MODE LH_IMAGE LH_VETRINA_DIR LH_VETRINA_WEB_HOST LH_VETRINA_IDP_HOST LH_VETRINA_PUBLIC_ADDRESS LH_HUB_DEMO_URL LH_BIND_ADDRESS
            LH_VETRINA_ADMIN_HOST)
# Segreti generati sull'host: nome del file e forma (password = 32 caratteri url-safe; key = 32 byte in base64).
SECRETS=(db-password:password idp-db-password:password idp-admin-password:password web-client-secret:password
         portal-client-secret:password widgets-client-secret:password cms-client-secret:password web-session-key:key subject-key:key)
# Variabili di segreto del compose di riferimento: nella vetrina non devono stare nell'ambiente (regola 20).
SECRET_ENV=(LH_DB_PASSWORD LH_IDP_DB_PASSWORD LH_IDP_ADMIN_PASSWORD LH_WEB_CLIENT_SECRET LH_PORTAL_CLIENT_SECRET LH_WIDGETS_CLIENT_SECRET
            LH_CMS_CLIENT_SECRET LH_WEB_SESSION_KEY LH_SUBJECT_KEY LH_GRAFANA_ADMIN_PASSWORD KC_BOOTSTRAP_ADMIN_PASSWORD)
# Porte del proxy nel codespace (Q-661): HTTP in chiaro su loopback, l'inoltro di GitHub le pubblica in https.
CODESPACE_WEB_PORT=8000
CODESPACE_IDP_PORT=8001
# Console del realm `master` nel codespace: porta 8180 con inoltro privato (solo il proprietario, accesso con GitHub).
# Il suo indirizzo e' l'attributo `frontendUrl` del realm master (Q-670, ADR-051 decisione 8); le console dei realm
# `loyaltyhub` e `loyaltyhub-members` stanno invece sull'indirizzo pubblico della porta 8001.
CODESPACE_ADMIN_PORT=8180
# Volumi azzerati ogni settimana (Q-624). I volumi del proxy (certificati ACME) restano.
RESET_VOLUMES=(lh-ref-postgres lh-ref-kafka)
# Validità dei certificati: CA 10 anni, foglie 397 giorni, rinnovo quando ne mancano meno di 30.
CA_DAYS=3650
LEAF_DAYS=397
RENEW_SECONDS=$((30 * 86400))

die() { echo "Errore: $*" >&2; exit 1; }
usage_error() { echo "Errore: $*" >&2; exit 2; }
info() { echo "vetrina: $*"; }

# ---------------------------------------------------------------------------------------------------------------
# Configurazione

load_config() {
  [ -f "$CONFIG" ] || usage_error "manca il file di configurazione $CONFIG (modello: deploy/vetrina/vetrina.env.example)"
  [ -L "$CONFIG" ] && usage_error "$CONFIG è un collegamento simbolico"
  # Il file decide immagine e indirizzi di un processo che gira come root: solo il proprietario lo scrive.
  local mode
  mode="$(stat -c '%a' "$CONFIG")"
  case "$mode" in
    *[2367]?|*[2367]) usage_error "$CONFIG è scrivibile dal gruppo o da altri (permessi $mode): usa 0600 o 0644" ;;
  esac
  local line key value n=0
  while IFS= read -r line || [ -n "$line" ]; do
    n=$((n + 1))
    case "$line" in ''|'#'*) continue ;; esac
    [[ "$line" == *=* ]] || usage_error "$CONFIG riga $n: atteso CHIAVE=valore"
    key="${line%%=*}"
    value="${line#*=}"
    # Virgolette di contorno ammesse, nessuna espansione.
    if [[ "$value" =~ ^\"(.*)\"$ ]] || [[ "$value" =~ ^\'(.*)\'$ ]]; then value="${BASH_REMATCH[1]}"; fi
    local ok=0 k
    for k in "${CONFIG_KEYS[@]}"; do [ "$k" = "$key" ] && ok=1; done
    [ "$ok" = 1 ] || usage_error "$CONFIG riga $n: chiave non ammessa: $key (ammesse: ${CONFIG_KEYS[*]})"
    export "$key=$value"
  done < "$CONFIG"
  validate_config
}

validate_config() {
  LH_VETRINA_MODE="${LH_VETRINA_MODE:-host}"
  case "$LH_VETRINA_MODE" in
    host|codespace) export LH_VETRINA_MODE ;;
    *) usage_error "LH_VETRINA_MODE deve essere host o codespace" ;;
  esac
  local host_re='^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$'
  [ -n "${LH_IMAGE:-}" ] || usage_error "LH_IMAGE obbligatoria (immagine unica pubblicata, meglio con @sha256)"
  [[ "${LH_VETRINA_DIR:-}" == /* ]] || usage_error "LH_VETRINA_DIR deve essere un percorso assoluto"
  [[ "${LH_VETRINA_WEB_HOST:-}" =~ $host_re ]] || usage_error "LH_VETRINA_WEB_HOST non è un nome DNS valido (minuscolo, senza schema né percorso)"
  [[ "${LH_VETRINA_IDP_HOST:-}" =~ $host_re ]] || usage_error "LH_VETRINA_IDP_HOST non è un nome DNS valido (minuscolo, senza schema né percorso)"
  [ "$LH_VETRINA_WEB_HOST" != "$LH_VETRINA_IDP_HOST" ] || usage_error "LH_VETRINA_WEB_HOST e LH_VETRINA_IDP_HOST devono essere diversi (Q-620: due nomi)"
  local ip_re='^([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})$'
  [[ "${LH_VETRINA_PUBLIC_ADDRESS:-}" =~ $ip_re ]] || usage_error "LH_VETRINA_PUBLIC_ADDRESS deve essere un indirizzo IPv4 dell'host (l'indirizzo privato dell'istanza)"
  local o
  for o in "${BASH_REMATCH[@]:1}"; do [ "$o" -le 255 ] || usage_error "LH_VETRINA_PUBLIC_ADDRESS non valido"; done
  if [ "$LH_VETRINA_MODE" = codespace ]; then
    # Nel codespace il proxy ascolta solo su loopback: l'inoltro delle porte di GitHub è l'unica via d'ingresso (Q-661).
    [ "$LH_VETRINA_PUBLIC_ADDRESS" = 127.0.0.1 ] || usage_error "LH_VETRINA_PUBLIC_ADDRESS deve essere 127.0.0.1 nel codespace"
    [[ "$LH_VETRINA_WEB_HOST" == *"-$CODESPACE_WEB_PORT."* ]] || usage_error "LH_VETRINA_WEB_HOST nel codespace è <nome>-$CODESPACE_WEB_PORT.<dominio di inoltro>"
    [[ "$LH_VETRINA_IDP_HOST" == *"-$CODESPACE_IDP_PORT."* ]] || usage_error "LH_VETRINA_IDP_HOST nel codespace è <nome>-$CODESPACE_IDP_PORT.<dominio di inoltro>"
    [[ "${LH_VETRINA_ADMIN_HOST:-}" =~ $host_re ]] && [[ "$LH_VETRINA_ADMIN_HOST" == *"-$CODESPACE_ADMIN_PORT."* ]] \
      || usage_error "LH_VETRINA_ADMIN_HOST nel codespace è <nome>-$CODESPACE_ADMIN_PORT.<dominio di inoltro> (frontendUrl del realm master, Q-670)"
  else
    case "$LH_VETRINA_PUBLIC_ADDRESS" in
      0.0.0.0|127.*) usage_error "LH_VETRINA_PUBLIC_ADDRESS non può essere 0.0.0.0 né loopback: indica l'indirizzo dell'interfaccia dell'host" ;;
    esac
  fi
  # Web e Keycloak parlano HTTP in chiaro: solo loopback, davanti c'è il proxy (ADR-049).
  if [ -n "${LH_BIND_ADDRESS:-}" ] && [ "$LH_BIND_ADDRESS" != "127.0.0.1" ]; then
    usage_error "LH_BIND_ADDRESS deve essere 127.0.0.1 nella vetrina (web e idp solo dietro il proxy)"
  fi
  export LH_BIND_ADDRESS=127.0.0.1
  if [ -n "${LH_HUB_DEMO_URL:-}" ] && ! [[ "$LH_HUB_DEMO_URL" =~ ^https://[a-z0-9.-]+(:[0-9]+)?$ ]]; then
    usage_error "LH_HUB_DEMO_URL deve essere un'origine https senza percorso né barra finale, oppure vuota"
  fi
  # Profilo e identità fissi: l'overlay li impone, qui si rifiuta chi prova a cambiarli dall'ambiente.
  export LH_PROFILE=enterprise LH_IDENTITY_MODE=oidc LH_OTEL_METRICS_ENABLED=false
}

no_secret_env() {
  local v bad=()
  for v in "${SECRET_ENV[@]}"; do
    if [ -n "${!v:-}" ]; then bad+=("$v"); fi
  done
  [ "${#bad[@]}" = 0 ] || die "segreti nell'ambiente (${bad[*]}): nella vetrina i segreti stanno solo nei file di $LH_VETRINA_DIR (regola 20). Togli le variabili e rilancia."
}

compose() {
  # Ambiente pulito dalle variabili di segreto anche se l'operatore le ha esportate: l'overlay le forza comunque a vuoto.
  local unset_args=() v
  for v in "${SECRET_ENV[@]}"; do unset_args+=(-u "$v"); done
  local files=(-f "$REFERENCE" -f "$OVERLAY")
  # Nel codespace KC_HOSTNAME_ADMIN non si imposta (Q-670, opzione A): l'overlay lo toglie con un valore nullo, che
  # Compose risolverebbe dall'ambiente del processo; lo si toglie anche da li' perche' una variabile esportata a mano
  # non riporti la console dei due realm sull'indirizzo privato.
  if [ "${LH_VETRINA_MODE:-host}" = codespace ]; then
    files+=(-f "$CODESPACE_OVERLAY")
    unset_args+=(-u KC_HOSTNAME_ADMIN)
  fi
  # Utenti di test (Q-676, ADR-051): hub e web li accettano solo con LH_TEST_USERS_ALLOWED=true e LH_ENVIRONMENT=test,
  # che passano soltanto alla vetrina dentro un GitHub Codespace (CODESPACES=true, impostata da GitHub). Altrove le due
  # variabili sono forzate a vuoto, anche se esportate a mano: un utente con LH_TEST_USER resta rifiutato.
  local test_env=(LH_TEST_USERS_ALLOWED= LH_ENVIRONMENT=)
  if [ "${LH_VETRINA_MODE:-host}" = codespace ] && [ "${CODESPACES:-}" = true ]; then
    test_env=(LH_TEST_USERS_ALLOWED=true LH_ENVIRONMENT=test)
  fi
  env "${unset_args[@]}" "${test_env[@]}" docker compose --project-name "$PROJECT" "${files[@]}" "$@"
}

# ---------------------------------------------------------------------------------------------------------------
# Provisioning: segreti, CA locale e certificati (Q-621). Mai un valore su stdout.

need() { command -v "$1" >/dev/null 2>&1 || die "serve $1"; }

# Scrive stdin in un file nuovo in modo atomico (temporaneo 0600 nella stessa cartella, poi rename).
write_atomic() {
  local dest="$1" tmp
  [ -L "$dest" ] && die "$dest è un collegamento simbolico"
  tmp="$(mktemp "$(dirname "$dest")/.tmp.XXXXXX")"
  cat > "$tmp"
  chmod 600 "$tmp"
  mv -f "$tmp" "$dest"
}

set_owner() {
  local uid="$1" mode="$2"; shift 2
  chmod "$mode" "$@"
  if [ "${LH_VETRINA_TEST_NO_CHOWN:-}" = 1 ]; then return 0; fi
  chown "$uid:$uid" "$@"
}

gen_secret() {
  local kind="$1"
  case "$kind" in
    password) openssl rand -base64 48 | tr -d '\n=' | tr '+/' '-_' | cut -c1-32 ;;
    key) openssl rand -base64 32 ;;
  esac
}

# Certificato foglia firmato dalla CA locale: leaf <nome> <CN> <subjectAltName> <extendedKeyUsage>
# Rinnovato se manca, se scade entro 30 giorni o se non è firmato dalla CA attuale.
leaf() {
  local name="$1" cn="$2" san="$3" eku="$4"
  local key="$TLS/$name.key" crt="$TLS/$name.crt"
  if [ -f "$crt" ] && [ -f "$key" ] && openssl x509 -in "$crt" -noout -checkend "$RENEW_SECONDS" >/dev/null 2>&1 \
     && openssl verify -CAfile "$CA/ca.crt" "$crt" >/dev/null 2>&1; then
    info "presente: tls/$name.crt"
    return 1
  fi
  local work
  work="$(mktemp -d)"
  openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$work/key.pem" 2>/dev/null
  openssl req -new -key "$work/key.pem" -subj "/CN=$cn/O=Loyalty Hub vetrina" -out "$work/req.csr" 2>/dev/null
  printf 'basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=%s\nsubjectAltName=%s\n' \
    "$eku" "$san" > "$work/ext.cnf"
  openssl x509 -req -in "$work/req.csr" -CA "$CA/ca.crt" -CAkey "$CA/ca.key" -set_serial "0x$(openssl rand -hex 16)" \
    -days "$LEAF_DAYS" -sha256 -extfile "$work/ext.cnf" -out "$work/crt.pem" 2>/dev/null
  write_atomic "$key" < "$work/key.pem"
  write_atomic "$crt" < "$work/crt.pem"
  rm -rf "$work"
  info "emesso: tls/$name.crt (scade tra $LEAF_DAYS giorni)"
  return 0
}

cmd_provision() {
  need openssl
  if [ "$(id -u)" != 0 ] && [ "${LH_VETRINA_TEST_NO_CHOWN:-}" != 1 ]; then
    die "provision va eseguito come root: assegna i file agli utenti dei container (uid $CONTAINER_UID e $POSTGRES_UID)"
  fi
  local SEC="$LH_VETRINA_DIR/secrets"
  TLS="$LH_VETRINA_DIR/tls"
  CA="$LH_VETRINA_DIR/ca"
  local d
  for d in "$LH_VETRINA_DIR" "$SEC" "$TLS" "$CA"; do
    [ -L "$d" ] && die "$d è un collegamento simbolico"
    mkdir -p "$d"
    chmod 700 "$d"
  done

  # 1. Segreti: generati una volta sola, mai riscritti (subject-key è immutabile: docs/11 §16).
  local entry name kind
  for entry in "${SECRETS[@]}"; do
    name="${entry%%:*}"; kind="${entry#*:}"
    if [ -s "$SEC/$name" ]; then
      info "presente: secrets/$name"
    else
      gen_secret "$kind" | write_atomic "$SEC/$name"
      info "generato: secrets/$name"
    fi
  done
  set_owner "$CONTAINER_UID" 600 "$SEC"/*

  # 2. CA locale (Q-621): chiave solo sull'host, mai montata in un container.
  local renew_all=0
  if [ ! -s "$CA/ca.key" ] || [ ! -s "$CA/ca.crt" ] || ! openssl x509 -in "$CA/ca.crt" -noout -checkend "$RENEW_SECONDS" >/dev/null 2>&1; then
    local work
    work="$(mktemp -d)"
    openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$work/ca.key" 2>/dev/null
    openssl req -x509 -new -key "$work/ca.key" -sha256 -days "$CA_DAYS" -subj "/CN=Loyalty Hub vetrina CA locale" \
      -addext "basicConstraints=critical,CA:TRUE,pathlen:0" -addext "keyUsage=critical,keyCertSign,cRLSign" \
      -out "$work/ca.crt" 2>/dev/null
    write_atomic "$CA/ca.key" < "$work/ca.key"
    write_atomic "$CA/ca.crt" < "$work/ca.crt"
    rm -rf "$work"
    renew_all=1
    info "generata: CA locale (ca/ca.crt, scade tra $CA_DAYS giorni)"
  else
    info "presente: CA locale"
  fi
  chmod 600 "$CA/ca.key"
  [ "${LH_VETRINA_TEST_NO_CHOWN:-}" = 1 ] || chown 0:0 "$CA/ca.key" "$CA/ca.crt"
  if [ "$renew_all" = 1 ]; then rm -f "$TLS"/*.crt; fi
  # Certificato pubblico della CA: lo leggono hub, Keycloak e Kafka (truststore), non è un segreto.
  cp "$CA/ca.crt" "$TLS/ca.crt.tmp" && mv -f "$TLS/ca.crt.tmp" "$TLS/ca.crt"

  # 3. Certificati di Postgres e Kafka e certificato client dell'hub verso Kafka (mTLS).
  leaf postgres postgres "DNS:postgres" serverAuth || :
  local kafka_new=0 client_new=0
  if leaf kafka kafka "DNS:kafka" "serverAuth,clientAuth"; then kafka_new=1; fi
  if leaf hub-kafka-client hub "DNS:hub" clientAuth; then client_new=1; fi
  # Keystore PEM del broker: chiave PKCS#8 e certificato nello stesso file (ssl.keystore.type=PEM).
  if [ "$kafka_new" = 1 ] || [ ! -s "$TLS/kafka-keystore.pem" ]; then
    cat "$TLS/kafka.key" "$TLS/kafka.crt" | write_atomic "$TLS/kafka-keystore.pem"
  fi
  # Certificati dell'hub in base64 su una riga (KAFKA_SSL_*_B64, LhKafkaSecurity).
  if [ "$client_new" = 1 ] || [ ! -s "$TLS/kafka-client-key.b64" ] || [ "$renew_all" = 1 ]; then
    base64 -w0 < "$CA/ca.crt" | write_atomic "$TLS/kafka-client-ca.b64"
    base64 -w0 < "$TLS/hub-kafka-client.crt" | write_atomic "$TLS/kafka-client-cert.b64"
    base64 -w0 < "$TLS/hub-kafka-client.key" | write_atomic "$TLS/kafka-client-key.b64"
  fi

  # 4. Proprietari e permessi: 0600 per tutto ciò che è privato, 0644 per i certificati pubblici.
  set_owner "$CONTAINER_UID" 600 "$TLS/kafka-keystore.pem" "$TLS"/kafka-client-*.b64 "$TLS/kafka.key" "$TLS/hub-kafka-client.key"
  set_owner "$POSTGRES_UID" 600 "$TLS/postgres.key"
  chmod 644 "$TLS/ca.crt" "$TLS"/*.crt
  info "provisioning completato in $LH_VETRINA_DIR (nessun valore stampato)"
}

# ---------------------------------------------------------------------------------------------------------------
# Controlli preliminari

check_file() {
  # check_file <percorso> <permessi attesi> <uid atteso, oppure - per non controllarlo>
  local f="$1" mode="$2" uid="$3"
  [ -e "$f" ] || { echo "  manca: $f" >&2; return 1; }
  [ -L "$f" ] && { echo "  collegamento simbolico: $f" >&2; return 1; }
  [ -s "$f" ] || { echo "  vuoto: $f" >&2; return 1; }
  local actual
  actual="$(stat -c '%a' "$f")"
  [ "$actual" = "$mode" ] || { echo "  permessi $actual invece di $mode: $f" >&2; return 1; }
  if [ "$uid" != "-" ] && [ "${LH_VETRINA_TEST_NO_CHOWN:-}" != 1 ]; then
    [ "$(stat -c '%u' "$f")" = "$uid" ] || { echo "  proprietario $(stat -c '%u' "$f") invece di $uid: $f" >&2; return 1; }
  fi
}

check_images_arch() {
  # Ogni immagine del compose deve avere una variante linux/<arch> dell'host. Legge il manifest dal registro.
  local want="$1" img ko=0 images
  images="$(compose config --images)"
  for img in $images; do
    if docker manifest inspect -v "$img" 2>/dev/null | LH_WANT_ARCH="$want" python3 -c '
import json, os, sys
data = json.load(sys.stdin)
items = data if isinstance(data, list) else [data]
archs = set()
for it in items:
    plat = (it.get("Descriptor") or {}).get("platform") or {}
    archs.add(plat.get("architecture"))
    for m in ((lambda r: r if isinstance(r, dict) else {})(it.get("Raw"))).get("manifests", []) or []:
        archs.add((m.get("platform") or {}).get("architecture"))
sys.exit(0 if os.environ["LH_WANT_ARCH"] in archs else 1)'; then
      info "$want: $img"
    else
      echo "  immagine senza variante linux/$want (o registro non raggiungibile): $img" >&2
      ko=1
    fi
  done
  return "$ko"
}

cmd_preflight() {
  local offline=0
  [ "${1:-}" = "--offline" ] && offline=1
  local ko=0
  # 1. Architettura dell'host: un host fisso è arm64 per default (ADR-049, LH_VETRINA_EXPECTED_ARCH per un altro host);
  #    il codespace ha l'architettura che GitHub assegna (amd64), e le immagini devono averne la variante (ADR-050).
  local arch expected
  arch="$(uname -m)"
  if [ "$LH_VETRINA_MODE" = codespace ]; then expected="${LH_VETRINA_EXPECTED_ARCH:-$arch}"; else expected="${LH_VETRINA_EXPECTED_ARCH:-aarch64}"; fi
  if [ "$arch" = "$expected" ] || { [ "$expected" = aarch64 ] && [ "$arch" = arm64 ]; }; then
    info "architettura dell'host: $arch"
  else
    echo "  architettura dell'host $arch, attesa $expected" >&2
    ko=1
  fi
  local image_arch
  case "$arch" in
    aarch64|arm64) image_arch=arm64 ;;
    x86_64|amd64) image_arch=amd64 ;;
    *) image_arch="$arch" ;;
  esac
  # 2. Nessun segreto nell'ambiente.
  no_secret_env
  # 3. Segreti e certificati: presenti, non collegamenti, permessi e proprietari.
  local entry
  for entry in "${SECRETS[@]}"; do
    check_file "$LH_VETRINA_DIR/secrets/${entry%%:*}" 600 "$CONTAINER_UID" || ko=1
  done
  local f
  for f in kafka-keystore.pem kafka-client-ca.b64 kafka-client-cert.b64 kafka-client-key.b64; do
    check_file "$LH_VETRINA_DIR/tls/$f" 600 "$CONTAINER_UID" || ko=1
  done
  check_file "$LH_VETRINA_DIR/tls/postgres.key" 600 "$POSTGRES_UID" || ko=1
  check_file "$LH_VETRINA_DIR/ca/ca.key" 600 0 || ko=1
  for f in ca.crt postgres.crt kafka.crt; do
    check_file "$LH_VETRINA_DIR/tls/$f" 644 - || ko=1
  done
  for f in postgres kafka hub-kafka-client; do
    if [ -f "$LH_VETRINA_DIR/tls/$f.crt" ]; then
      openssl verify -CAfile "$LH_VETRINA_DIR/tls/ca.crt" "$LH_VETRINA_DIR/tls/$f.crt" >/dev/null 2>&1 \
        || { echo "  certificato non firmato dalla CA locale: tls/$f.crt (vetrina.sh provision)" >&2; ko=1; }
      openssl x509 -in "$LH_VETRINA_DIR/tls/$f.crt" -noout -checkend $((7 * 86400)) >/dev/null 2>&1 \
        || { echo "  certificato in scadenza entro 7 giorni: tls/$f.crt (vetrina.sh provision)" >&2; ko=1; }
    fi
  done
  [ "$ko" = 0 ] || die "controlli preliminari falliti (sopra l'elenco)"
  # 4. Indirizzo pubblicato dal proxy: deve essere di un'interfaccia dell'host.
  if command -v ip >/dev/null 2>&1; then
    ip -o -4 addr show | grep -q "inet ${LH_VETRINA_PUBLIC_ADDRESS}/" \
      || die "LH_VETRINA_PUBLIC_ADDRESS ($LH_VETRINA_PUBLIC_ADDRESS) non è un indirizzo di questo host"
  fi
  # 5. Memoria: lo stack ha limiti per circa 6,5 GB (ADR-049: 5-7 GB su 12).
  if [ -r /proc/meminfo ]; then
    local kb
    kb="$(awk '/^MemTotal:/ {print $2}' /proc/meminfo)"
    if [ "$kb" -lt $((8 * 1024 * 1024)) ]; then
      echo "Avviso: memoria dell'host sotto 8 GB ($((kb / 1024)) MB): i limiti dei container sommano circa 6,5 GB." >&2
    fi
  fi
  # 6. Compose valido con l'overlay, poi immagini per l'architettura dell'host (serve il registro: --offline lo salta).
  need docker
  compose config -q || die "docker compose config fallito con l'overlay di vetrina"
  if [ "$offline" = 0 ]; then
    need python3
    check_images_arch "$image_arch" || die "immagini non disponibili per linux/$image_arch"
  fi
  info "controlli preliminari superati"
}

# ---------------------------------------------------------------------------------------------------------------
# Avvio, azzeramento, operatori, programma

KEYCLOAK_LOCAL="http://127.0.0.1:8180"

cmd_up() {
  cmd_preflight "$@"
  compose up -d --wait --wait-timeout 900
  info "vetrina avviata: https://$LH_VETRINA_WEB_HOST (Keycloak: https://$LH_VETRINA_IDP_HOST)"
}

# ---------------------------------------------------------------------------------------------------------------
# Codespace (ADR-050, Q-660…Q-663)

# Variabili del codespace lette da `vetrina.sh codespace` (nessun segreto della vetrina: quelli li genera provision).
#   CODESPACES, CODESPACE_NAME, GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN  impostate da GitHub
#   LH_IMAGE                immagine unica (segreto del codespace o derivata dall'ultimo tag v* da avvio.sh)
#   LH_HUB_DEMO_URL         facoltativa, collegamento di ritorno alla demo in HUB-02
#   LH_VETRINA_OPERATORS    facoltativa, elenco degli account operatore (righe o `;`), segreto del codespace (Q-663)
cmd_codespace() {
  [ "${CODESPACES:-}" = true ] || usage_error "il comando codespace vale solo dentro un GitHub Codespace (CODESPACES=true)"
  local name="${CODESPACE_NAME:-}" domain="${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN:-}"
  [[ "$name" =~ ^[a-z0-9][a-z0-9-]{0,80}[a-z0-9]$ ]] || usage_error "CODESPACE_NAME assente o non valido"
  [[ "$domain" =~ ^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$ ]] || usage_error "GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN assente o non valido"
  [ -n "${LH_IMAGE:-}" ] || usage_error "LH_IMAGE obbligatoria: segreto del codespace o ultimo tag v* (.devcontainer/vetrina/avvio.sh)"
  if [ "$(id -u)" != 0 ] && [ "${LH_VETRINA_TEST_NO_CHOWN:-}" != 1 ]; then
    die "codespace va eseguito come root (sudo): provision assegna i file agli utenti dei container"
  fi
  local dir="${LH_VETRINA_DIR:-$(dirname "$CONFIG")}"
  [ -L "$dir" ] && die "$dir è un collegamento simbolico"
  mkdir -p "$dir"
  chmod 700 "$dir"
  # Configurazione rigenerata a ogni avvio: il nome del codespace non cambia, l'immagine può cambiare.
  {
    echo "# Generato da vetrina.sh codespace (ADR-050): non modificare a mano, si riscrive a ogni avvio."
    echo "LH_VETRINA_MODE=codespace"
    echo "LH_IMAGE=$LH_IMAGE"
    echo "LH_VETRINA_DIR=$dir"
    echo "LH_VETRINA_WEB_HOST=$name-$CODESPACE_WEB_PORT.$domain"
    echo "LH_VETRINA_IDP_HOST=$name-$CODESPACE_IDP_PORT.$domain"
    echo "LH_VETRINA_ADMIN_HOST=$name-$CODESPACE_ADMIN_PORT.$domain"
    echo "LH_VETRINA_PUBLIC_ADDRESS=127.0.0.1"
    echo "LH_HUB_DEMO_URL=${LH_HUB_DEMO_URL:-}"
    echo "LH_BIND_ADDRESS=127.0.0.1"
  } | write_atomic "$CONFIG"
  chmod 600 "$CONFIG"
  # Elenco degli operatori dal segreto del codespace: righe o `;`, mai stampato (contiene e-mail).
  local operators="${LH_VETRINA_OPERATORS:-}"
  unset LH_VETRINA_OPERATORS
  # Le variabili di ambiente del codespace non devono sovrascrivere il file appena scritto.
  unset LH_IMAGE LH_HUB_DEMO_URL LH_VETRINA_DIR
  load_config
  cmd_provision
  if [ -n "$operators" ]; then
    printf '%s\n' "$operators" | tr ';' '\n' | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e '/^$/d' \
      | write_atomic "$LH_VETRINA_DIR/operators.list"
    info "elenco degli operatori aggiornato dal segreto del codespace"
  fi
  # Keycloak riparte da zero a ogni avvio (ADR-051 decisione 10, Q-672): il database `idp` si ricrea PRIMA che Keycloak
  # parta, cosi' reimporta i realm di base; poi gli overlay (master, operatori, membri, utenti di test con id fissi) si
  # riapplicano. Il database dell'hub e i suoi dati restano: il legame (iss, sub) dei membri registrati resta valido.
  cmd_preflight
  cmd_idp_reset
  compose up -d --wait --wait-timeout 900
  info "vetrina avviata: https://$LH_VETRINA_WEB_HOST (Keycloak: https://$LH_VETRINA_IDP_HOST)"
  apply_realm_overlay
  cmd_operators
  # Membri di test (Q-673): servono le porte pubbliche (il BFF raggiunge Keycloak dall'indirizzo pubblico); se non lo
  # sono ancora li registra avvio.sh dopo averle pubblicate. Un errore qui non ferma l'avvio: avvio.sh lo ripete e lo dice.
  cmd_membri --if-reachable || echo "Avviso: membri di test non registrati (messaggio sopra); li riprova avvio.sh, oppure: vetrina.sh membri" >&2
  info "web: https://$LH_VETRINA_WEB_HOST · Keycloak: https://$LH_VETRINA_IDP_HOST (porte $CODESPACE_WEB_PORT e $CODESPACE_IDP_PORT pubbliche, Q-661)"
  info "console dei realm (pubbliche, ADR-051 decisione 8): https://$LH_VETRINA_IDP_HOST/admin/loyaltyhub/console/ e https://$LH_VETRINA_IDP_HOST/admin/loyaltyhub-members/console/ (utenti vetrina.admin e membri.admin, credenziali nel runbook)"
  info "console del realm master (Q-670): https://$LH_VETRINA_ADMIN_HOST/admin/master/console/ (porta $CODESPACE_ADMIN_PORT privata: solo il proprietario del codespace; utente admin, password in $LH_VETRINA_DIR/secrets/idp-admin-password)"
  info "se il programma è vuoto: pulsante \"Carica il programma di esempio\" nel backoffice (V10), oppure vetrina.sh programma (Q-630)"
}

# Utenti di test (ADR-051 decisione 1, Q-676): solo dentro un GitHub Codespace, lo stesso criterio che passa a hub e web
# LH_TEST_USERS_ALLOWED (compose()). Sull'host fisso gli overlay si applicano senza account di test: l'hub li
# rifiuterebbe comunque, ma non devono nemmeno esistere in Keycloak con una password pubblica.
test_users_allowed() { [ "${LH_VETRINA_MODE:-host}" = codespace ] && [ "${CODESPACES:-}" = true ]; }

apply_realm_overlay() {
  local mode="${1:-}" pw args=()
  [ -n "$mode" ] && args+=("$mode")
  test_users_allowed || args+=(--no-test-users)
  IFS= read -r pw < "$LH_VETRINA_DIR/secrets/idp-admin-password" || [ -n "$pw" ]
  # La password va solo nell'ambiente del processo figlio (mai negli argomenti, mai stampata).
  # Nel codespace il realm master riceve come frontendUrl l'indirizzo privato della porta 8180 (Q-670): l'indirizzo e'
  # dinamico, quindi lo si passa qui e non sta in master.json. Altrove la variabile e' vuota e il realm non si tocca.
  local master_frontend=""
  [ "${LH_VETRINA_MODE:-host}" = codespace ] && master_frontend="https://$LH_VETRINA_ADMIN_HOST"
  MASTER_FRONTEND_URL="$master_frontend" KC_BOOTSTRAP_ADMIN_PASSWORD="$pw" KEYCLOAK_URL="$KEYCLOAK_LOCAL" \
    "$REPO_ROOT/deploy/idp/vetrina/apply-overlay.sh" ${args[@]+"${args[@]}"}
}

# Database di Keycloak da zero (ADR-051 decisione 10, Q-672): ferma Keycloak e chi dipende dal suo emittente, ricrea
# SOLO il database `idp` (non quello dell'hub) con lo stesso proprietario e gli stessi privilegi dello script iniziale di
# Postgres (deploy/compose/postgres-init/10-idp.sh). Alla ripartenza Keycloak reimporta i realm di base.
cmd_idp_reset() {
  info "database di Keycloak ricreato da zero (l'hub non si tocca)"
  compose stop idp web hub proxy >/dev/null 2>&1 || true
  compose up -d --wait --wait-timeout 300 postgres
  compose exec -T postgres psql -v ON_ERROR_STOP=1 -U loyaltyhub -d loyaltyhub \
    -c 'DROP DATABASE IF EXISTS idp WITH (FORCE)' \
    -c 'CREATE DATABASE idp OWNER idp' \
    -c 'REVOKE ALL ON DATABASE idp FROM PUBLIC' > /dev/null
}

# Membri di test dal portale (Q-673, scripts/vetrina-membri.mjs): Anna, Marco e Giulia registrati con i dati del seed,
# Laura riportata alla registrazione da zero. Solo nel codespace. Con --if-reachable salta (senza errore) se l'indirizzo
# pubblico di Keycloak non risponde ancora, cioe' se le porte 8000 e 8001 non sono ancora pubbliche.
cmd_membri() {
  if ! test_users_allowed; then
    info "membri di test: solo nel codespace (gli utenti di test non esistono altrove): saltato"
    return 0
  fi
  local soft=0
  [ "${1:-}" = "--if-reachable" ] && soft=1
  if ! command -v node >/dev/null 2>&1; then
    if [ "$soft" = 1 ]; then
      info "membri di test: manca node nel PATH, li registra avvio.sh"
      return 0
    fi
    die "serve node (Node.js 22) per registrare i membri di test"
  fi
  if [ "$soft" = 1 ] && ! curl -fsS -o /dev/null --max-time 15 "https://$LH_VETRINA_IDP_HOST/realms/loyaltyhub-members/.well-known/openid-configuration"; then
    info "membri di test: l'indirizzo pubblico di Keycloak non risponde ancora (porte 8000 e 8001 non pubbliche?): li registra avvio.sh dopo averle pubblicate"
    return 0
  fi
  node "$REPO_ROOT/scripts/vetrina-membri.mjs" --web "https://$LH_VETRINA_WEB_HOST"
}

cmd_operators() {
  local list="$LH_VETRINA_DIR/operators.list"
  if [ ! -f "$list" ]; then
    info "nessun $list: nessun account operatore da creare (Q-618)"
    return 0
  fi
  need python3
  python3 "$SCRIPT_DIR/operatori.py" --keycloak "$KEYCLOAK_LOCAL" \
    --admin-password-file "$LH_VETRINA_DIR/secrets/idp-admin-password" \
    --list "$list" --out "$LH_VETRINA_DIR/operator-passwords.txt"
  apply_realm_overlay --check-operators
}

cmd_reset() {
  local lock="$LH_VETRINA_DIR/.reset.lock"
  mkdir -p "$LH_VETRINA_DIR"
  exec 9>"$lock"
  if command -v flock >/dev/null 2>&1; then flock -n 9 || die "un azzeramento è già in corso"; fi
  info "azzeramento iniziato $(date -u +%Y-%m-%dT%H:%M:%SZ) (Q-624: nessun backup)"
  cmd_provision
  cmd_preflight "$@"
  compose down --remove-orphans
  local v
  for v in "${RESET_VOLUMES[@]}"; do
    docker volume rm -f "${PROJECT}_${v}" >/dev/null
    info "volume ricreato: ${PROJECT}_${v}"
  done
  compose up -d --wait --wait-timeout 900
  apply_realm_overlay
  cmd_operators
  # Il database dell'hub e' ripartito vuoto: i membri di test si registrano di nuovo (solo nel codespace).
  cmd_membri --if-reachable
  info "azzeramento completato $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  info "passo rimasto: pulsante \"Carica il programma di esempio\" nel backoffice (V10), oppure vetrina.sh programma (token di un operatore, Q-617, Q-630)"
}

cmd_programma() {
  need node
  # Il token arriva dal Device Authorization Grant del client lh-cli (MFA dell'operatore, Q-626): lo script mostra
  # indirizzo e codice da confermare nel browser e tiene il token solo in memoria. L'hub è raggiunto su loopback.
  node "$REPO_ROOT/scripts/vetrina-programma.mjs" \
    --issuer "https://$LH_VETRINA_IDP_HOST/realms/loyaltyhub" \
    --base-url "http://127.0.0.1:8080" --allow-http --apply "$@"
}

main() {
  local cmd="${1:-}"
  [ -n "$cmd" ] || usage_error "uso: $0 codespace|provision|preflight [--offline]|up|down|reset|idp-reset|operators|membri|programma|compose <argomenti>"
  shift
  case "$cmd" in
    codespace) cmd_codespace ;;
    provision) load_config; cmd_provision ;;
    preflight) load_config; cmd_preflight "$@" ;;
    up) load_config; cmd_up "$@" ;;
    down) load_config; compose down ;;
    reset) load_config; cmd_reset "$@" ;;
    operators) load_config; cmd_operators ;;
    idp-reset) load_config; cmd_idp_reset ;;
    membri) load_config; cmd_membri ;;
    programma) load_config; cmd_programma "$@" ;;
    compose) load_config; compose "$@" ;;
    *) usage_error "comando sconosciuto: $cmd" ;;
  esac
}

main "$@"
