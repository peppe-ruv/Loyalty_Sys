#!/usr/bin/env bash
# Vetrina enterprise ospitata: comandi del codespace (F2-DIST-03, F2-DIST-09, ADR-049, ADR-050, ADR-051, M8.14 V3, V7 e
# V12; Q-616, Q-621, Q-624, Q-660…Q-663).
#
# Avvia il compose di riferimento con gli overlay di vetrina (deploy/vetrina/compose.vetrina.yml e
# deploy/vetrina/compose.codespace.yml) in un GitHub Codespace acceso su richiesta (ADR-050): il TLS lo termina
# l'inoltro delle porte di GitHub. È l'unica modalità (ADR-051 decisione 5): `LH_VETRINA_MODE=codespace` resta ammessa
# nei file di configurazione già scritti (regola 14), ogni altro valore è rifiutato. Runbook: deploy/vetrina/README.md.
#
#   vetrina.sh codespace            solo nel codespace (Q-663, ADR-051): scrive la configurazione dall'ambiente del
#                                   codespace, provisioning, controlli, database di Keycloak RICREATO DA ZERO (Q-672),
#                                   avvio, overlay dei realm (master, operatori, membri, utenti di test), account
#                                   operatore e membri di test (Q-673)
#
#   vetrina.sh provision            segreti, CA locale e certificati di Postgres e Kafka (idempotente, regola 20)
#   vetrina.sh preflight [--offline]
#                                   controlli prima dell'avvio: configurazione, architettura dell'host e delle immagini,
#                                   permessi dei segreti, certificati, nessun segreto nell'ambiente
#   vetrina.sh up                   preflight e avvio (docker compose up -d --wait)
#   vetrina.sh down                 arresto, volumi conservati
#   vetrina.sh reset                azzeramento (Q-624), a mano: volumi di Postgres e Kafka ricreati, realm reimportato,
#                                   overlay del realm, account operatore; nessun backup
#   vetrina.sh idp-reset            ricrea da zero il database `idp` di Keycloak (non quello dell'hub): Keycloak
#                                   reimporta i realm e gli overlay si riapplicano (ADR-051 decisione 10, Q-672);
#                                   lo fa da solo `codespace` a ogni avvio
#   vetrina.sh membri               membri di test dal portale (Anna, Marco, Giulia registrati; Laura da zero, Q-673):
#                                   solo nel codespace, con hub e web su e le porte pubbliche
#   vetrina.sh immagine             rifiuta (uscita 2) un'immagine LH_IMAGE più vecchia degli script del repository
#                                   (etichetta OCI di revisione o tag vX.Y.Z); lo lancia avvio.sh dopo il pull;
#                                   `--scegli` e `--risolvi` scelgono e scaricano build-<commit> di main (vedi sotto)
#   vetrina.sh operators            account operatore nominativi da operators.list (Q-618), password temporanee in un
#                                   file 0600, poi verifica della MFA (apply-overlay.sh --check-operators)
#   vetrina.sh programma            configurazione di programma da seed/ con il token di un operatore (Q-617, Q-626):
#                                   passo interattivo (Device Authorization Grant con MFA, Q-630)
#   vetrina.sh compose <argomenti>  docker compose con i file e il progetto della vetrina (ps, logs …)
#
# Configurazione: file KEY=VALORE (LH_VETRINA_CONFIG, default /etc/loyaltyhub-vetrina/vetrina.env; lo scrive
# `vetrina.sh codespace`, modello in vetrina.env.example). Solo le chiavi ammesse, nessun segreto. I segreti stanno in $LH_VETRINA_DIR/secrets e
# $LH_VETRINA_DIR/tls, file 0600 creati da `provision`; lo script non stampa mai un valore, solo nomi e percorsi.
# Utenti di test (ADR-051 decisione 1): solo con CODESPACES=true, impostata da GitHub; altrove gli overlay si applicano
# senza utenti.
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
# LH_VETRINA_MODE e LH_VETRINA_PUBLIC_ADDRESS restano ammesse solo per i file già scritti (regola 14): la prima vale
# solo `codespace`, la seconda solo 127.0.0.1 e non ha altro effetto. `codespace` non le scrive più.
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
# Console del realm `master` (ADR-055, Q-727): sta sull'indirizzo pubblico della porta 8001 come quelle dei realm
# `loyaltyhub` e `loyaltyhub-members`, ma il proxy la apre SOLO se il proprietario ha messo nel codespace il segreto
# LH_VETRINA_MASTER_ADMIN_PASSWORD (utente `proprietario`, ruolo admin). La porta 8180 (127.0.0.1) non si inoltra piu':
# serve solo agli script dell'host (KEYCLOAK_LOCAL). LH_VETRINA_ADMIN_HOST e' una chiave dei file gia' scritti (Q-670,
# superata): resta ammessa e ignorata, `codespace` non la scrive piu'.
# Password del proprietario letta da cmd_codespace (mai esportata): la vede solo apply_realm_overlay, per il processo figlio.
MASTER_OWNER_PASSWORD=""
# Volumi azzerati dall'azzeramento (Q-624).
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
  # Unica modalità: il codespace (ADR-050, ADR-051 decisione 5). La chiave resta ammessa per i file già scritti.
  LH_VETRINA_MODE="${LH_VETRINA_MODE:-codespace}"
  case "$LH_VETRINA_MODE" in
    codespace) export LH_VETRINA_MODE ;;
    *) usage_error "LH_VETRINA_MODE ammette solo codespace, l'unica modalità supportata (ADR-051 decisione 5)" ;;
  esac
  local host_re='^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$'
  [ -n "${LH_IMAGE:-}" ] || usage_error "LH_IMAGE obbligatoria (immagine unica pubblicata, meglio con @sha256)"
  [[ "${LH_VETRINA_DIR:-}" == /* ]] || usage_error "LH_VETRINA_DIR deve essere un percorso assoluto"
  [[ "${LH_VETRINA_WEB_HOST:-}" =~ $host_re ]] || usage_error "LH_VETRINA_WEB_HOST non è un nome DNS valido (minuscolo, senza schema né percorso)"
  [[ "${LH_VETRINA_IDP_HOST:-}" =~ $host_re ]] || usage_error "LH_VETRINA_IDP_HOST non è un nome DNS valido (minuscolo, senza schema né percorso)"
  [ "$LH_VETRINA_WEB_HOST" != "$LH_VETRINA_IDP_HOST" ] || usage_error "LH_VETRINA_WEB_HOST e LH_VETRINA_IDP_HOST devono essere diversi (Q-620: due nomi)"
  # Chiave dei file scritti prima di V12: se presente deve essere 127.0.0.1, il proxy ascolta solo su loopback (Q-661).
  [ -z "${LH_VETRINA_PUBLIC_ADDRESS:-}" ] || [ "$LH_VETRINA_PUBLIC_ADDRESS" = 127.0.0.1 ] \
    || usage_error "LH_VETRINA_PUBLIC_ADDRESS, se presente, deve essere 127.0.0.1: il proxy ascolta solo su loopback"
  # Nel codespace l'inoltro delle porte di GitHub è l'unica via d'ingresso (Q-661).
  [[ "$LH_VETRINA_WEB_HOST" == *"-$CODESPACE_WEB_PORT."* ]] || usage_error "LH_VETRINA_WEB_HOST nel codespace è <nome>-$CODESPACE_WEB_PORT.<dominio di inoltro>"
  [[ "$LH_VETRINA_IDP_HOST" == *"-$CODESPACE_IDP_PORT."* ]] || usage_error "LH_VETRINA_IDP_HOST nel codespace è <nome>-$CODESPACE_IDP_PORT.<dominio di inoltro>"
  # Web e Keycloak parlano HTTP in chiaro: solo loopback, davanti c'è il proxy (ADR-049, ADR-050).
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
  local files=(-f "$REFERENCE" -f "$OVERLAY" -f "$CODESPACE_OVERLAY")
  # KC_HOSTNAME_ADMIN non si imposta (Q-670, opzione A): l'overlay lo toglie con un valore nullo, che Compose
  # risolverebbe dall'ambiente del processo; lo si toglie anche da li' perche' una variabile esportata a mano non
  # riporti la console dei due realm sull'indirizzo privato.
  unset_args+=(-u KC_HOSTNAME_ADMIN -u LH_VETRINA_MASTER_ADMIN_PASSWORD)
  # Utenti di test (Q-676, ADR-051): hub e web li accettano solo con LH_TEST_USERS_ALLOWED=true e LH_ENVIRONMENT=test,
  # che passano soltanto alla vetrina dentro un GitHub Codespace (CODESPACES=true, impostata da GitHub). Altrove le due
  # variabili sono forzate a vuoto, anche se esportate a mano: un utente con LH_TEST_USER resta rifiutato.
  local test_env=(LH_TEST_USERS_ALLOWED= LH_ENVIRONMENT=)
  if [ "${CODESPACES:-}" = true ]; then
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
  # 5. Cartella del permesso per la console master (montata nel proxy): all'inizio e' sempre chiusa (ADR-055).
  [ -f "$LH_VETRINA_DIR/caddy/master-console.caddy" ] || master_console chiusa
  info "provisioning completato in $LH_VETRINA_DIR (nessun valore stampato)"
}

# Stato della console del realm `master` sul proxy (ADR-055, Q-727): `aperta` o `chiusa`. Copia il file scelto in
# $LH_VETRINA_DIR/caddy/master-console.caddy (importato da Caddyfile.codespace; contiene solo permessi, mai segreti, quindi
# leggibile da tutti) e, se il contenuto cambia e il proxy gira, lo riavvia: l'API di amministrazione di Caddy e' spenta
# (`admin off`), quindi il Caddyfile si rilegge solo ripartendo. Il file e' in una cartella montata, non un file singolo,
# perche' la sostituzione con rename lascerebbe un file singolo legato al vecchio inode.
master_console() {
  local state="$1" src dir="$LH_VETRINA_DIR/caddy" dest
  dest="$dir/master-console.caddy"
  case "$state" in
    aperta) src="$SCRIPT_DIR/caddy/master-aperta.caddy" ;;
    chiusa) src="$SCRIPT_DIR/caddy/master-chiusa.caddy" ;;
    *) die "stato della console master non valido: $state" ;;
  esac
  [ -L "$dir" ] && die "$dir è un collegamento simbolico"
  mkdir -p "$dir"
  chmod 755 "$dir"
  if [ -f "$dest" ] && cmp -s "$src" "$dest"; then return 0; fi
  write_atomic "$dest" < "$src"
  chmod 644 "$dest"
  if compose ps --status running --services 2>/dev/null | grep -qx proxy; then
    compose restart proxy >/dev/null
    info "proxy riavviato: console del realm master $state"
  fi
}

master_console_is_open() {
  [ -f "$LH_VETRINA_DIR/caddy/master-console.caddy" ] && cmp -s "$SCRIPT_DIR/caddy/master-aperta.caddy" "$LH_VETRINA_DIR/caddy/master-console.caddy"
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
  # 1. Architettura dell'host: il codespace ha quella che GitHub assegna (amd64) e le immagini devono averne la
  #    variante (ADR-050); LH_VETRINA_EXPECTED_ARCH impone un'architettura attesa (prove).
  local arch expected
  arch="$(uname -m)"
  expected="${LH_VETRINA_EXPECTED_ARCH:-$arch}"
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
  # 4. Memoria: lo stack ha limiti per circa 6,5 GB (ADR-049: 5-7 GB).
  if [ -r /proc/meminfo ]; then
    local kb
    kb="$(awk '/^MemTotal:/ {print $2}' /proc/meminfo)"
    if [ "$kb" -lt $((8 * 1024 * 1024)) ]; then
      echo "Avviso: memoria dell'host sotto 8 GB ($((kb / 1024)) MB): i limiti dei container sommano circa 6,5 GB." >&2
    fi
  fi
  # 5. Compose valido con l'overlay, poi immagini per l'architettura dell'host (serve il registro: --offline lo salta).
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
#   LH_IMAGE                immagine unica (segreto del codespace o build-<commit> scelta da avvio.sh se il segreto manca)
#   LH_HUB_DEMO_URL         facoltativa, collegamento di ritorno alla demo in HUB-02
#   LH_VETRINA_OPERATORS    facoltativa, elenco degli account operatore (righe o `;`), segreto del codespace (Q-663)
#   LH_VETRINA_MASTER_ADMIN_PASSWORD
#                           facoltativa, password dell'utente `proprietario` del realm master: segreto del codespace che
#                           conosce solo il proprietario (ADR-055, Q-727). Con il segreto la console master e' aperta sul
#                           proxy; senza resta chiusa (404). Mai stampata, mai negli argomenti, tolta dall'ambiente.
cmd_codespace() {
  [ "${CODESPACES:-}" = true ] || usage_error "il comando codespace vale solo dentro un GitHub Codespace (CODESPACES=true)"
  local name="${CODESPACE_NAME:-}" domain="${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN:-}"
  [[ "$name" =~ ^[a-z0-9][a-z0-9-]{0,80}[a-z0-9]$ ]] || usage_error "CODESPACE_NAME assente o non valido"
  [[ "$domain" =~ ^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$ ]] || usage_error "GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN assente o non valido"
  [ -n "${LH_IMAGE:-}" ] || usage_error "LH_IMAGE obbligatoria: segreto del codespace o immagine scelta da .devcontainer/vetrina/avvio.sh"
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
    echo "LH_IMAGE=$LH_IMAGE"
    echo "LH_VETRINA_DIR=$dir"
    echo "LH_VETRINA_WEB_HOST=$name-$CODESPACE_WEB_PORT.$domain"
    echo "LH_VETRINA_IDP_HOST=$name-$CODESPACE_IDP_PORT.$domain"
    echo "LH_HUB_DEMO_URL=${LH_HUB_DEMO_URL:-}"
    echo "LH_BIND_ADDRESS=127.0.0.1"
  } | write_atomic "$CONFIG"
  chmod 600 "$CONFIG"
  # Elenco degli operatori dal segreto del codespace: righe o `;`, mai stampato (contiene e-mail).
  local operators="${LH_VETRINA_OPERATORS:-}"
  unset LH_VETRINA_OPERATORS
  # Password del proprietario per la console del realm master (ADR-055, Q-727): segreto del codespace, mai stampato,
  # mai negli argomenti; si toglie subito dall'ambiente (compose, curl e gli altri figli non la ereditano) e resta solo in
  # MASTER_OWNER_PASSWORD fino alla fine dell'overlay dei realm.
  local master_pw="${LH_VETRINA_MASTER_ADMIN_PASSWORD:-}"
  unset LH_VETRINA_MASTER_ADMIN_PASSWORD
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
  MASTER_OWNER_PASSWORD="$master_pw"
  master_pw=""
  apply_realm_overlay
  MASTER_OWNER_PASSWORD=""
  cmd_operators
  # Membri di test (Q-673): servono le porte pubbliche (il BFF raggiunge Keycloak dall'indirizzo pubblico); se non lo
  # sono ancora li registra avvio.sh dopo averle pubblicate. Un errore qui non ferma l'avvio: avvio.sh lo ripete e lo dice.
  cmd_membri --if-reachable || echo "Avviso: membri di test non registrati (messaggio sopra); li riprova avvio.sh, oppure: vetrina.sh membri" >&2
  info "web: https://$LH_VETRINA_WEB_HOST · Keycloak: https://$LH_VETRINA_IDP_HOST (porte $CODESPACE_WEB_PORT e $CODESPACE_IDP_PORT pubbliche, Q-661)"
  info "console dei realm (pubbliche, ADR-051 decisione 8): https://$LH_VETRINA_IDP_HOST/admin/loyaltyhub/console/ e https://$LH_VETRINA_IDP_HOST/admin/loyaltyhub-members/console/ (utenti vetrina.admin e membri.admin, credenziali nel runbook)"
  # Console del realm master (ADR-055, Q-727): mai la password, solo l'indirizzo e lo stato.
  if master_console_is_open; then
    info "console del realm master (ADR-055): https://$LH_VETRINA_IDP_HOST/admin/master/console/ · aperta: utente proprietario, credenziali nel segreto LH_VETRINA_MASTER_ADMIN_PASSWORD del codespace (dopo 5 tentativi falliti il realm impone un'attesa crescente fino a 15 minuti)"
  else
    info "console del realm master (ADR-055): https://$LH_VETRINA_IDP_HOST/admin/master/console/ · chiusa: segreto LH_VETRINA_MASTER_ADMIN_PASSWORD assente o non valido (si imposta su github.com/settings/codespaces e vale dal prossimo avvio)"
  fi
  info "se il programma è vuoto: pulsante \"Carica il programma di esempio\" nel backoffice (V10), oppure vetrina.sh programma (Q-630)"
}

# Freschezza dell'immagine (F2-DIST-09, ADR-051, ADR-050, M8.14): gli script del repository (overlay, vetrina.sh,
# vetrina-membri.mjs) presuppongono un web e un hub della stessa epoca. Un'immagine costruita da un commit più vecchio che
# differisce da HEAD nelle cartelle che finiscono nell'immagine (quelle copiate da deploy/image/Dockerfile; un solo ritocco agli script non la invecchia) fa fallire l'avvio in modo oscuro (es. login dei membri sul realm sbagliato),
# quindi si rifiuta subito. Revisione dell'immagine: etichetta OCI `org.opencontainers.image.revision`; se manca e il
# riferimento termina con un tag `:vX.Y.Z` noto a git, il commit del tag. Se resta ignota: avviso e si continua.
# Non legge la configurazione (gira prima di `codespace`); LH_IMAGE arriva dall'ambiente. Nessun segreto in uscita.
# Uscita: 0 ok o non verificabile · 2 immagine più vecchia del repository.
IMAGE_FRESHNESS_PATHS=(web services libs contracts seed deploy/hub deploy/image pom.xml .mvn mvnw)

# Scelta automatica dell'immagine (M8.14): la vetrina segue main. image.yml pubblica `build-<commit>` firmata a ogni merge
# su main che tocca IMAGE_FRESHNESS_PATHS; l'immagine giusta per HEAD e' quella dell'ultimo commit, fino a HEAD, che ha
# toccato quei percorsi (un commit di sola documentazione non ne costruisce una).
#   vetrina.sh immagine --scegli    stampa il riferimento ghcr.io/<proprietario>/loyaltyhub:build-<commit>
#   vetrina.sh immagine --risolvi   come --scegli, poi la scarica. Se build-<commit> non c'e': prima un tag v* senza
#                                   differenze nelle cartelle dell'immagine (equivalente, subito); poi, solo se
#                                   un'esecuzione di image.yml per il commit e' in corso (o lo stato non e' verificabile),
#                                   riprova ogni LH_AVVIO_INTERVALLO_S (30) fino a LH_AVVIO_ATTESA_S (900) secondi; se
#                                   manca ancora (o l'esecuzione e' fallita o assente) errore subito o a fine attesa
#                                   (uscita 1). Stampa su stdout solo il riferimento.
# Il proprietario viene da GITHUB_REPOSITORY (impostata dal codespace). Nessun segreto in uscita.
image_git() { git -c "safe.directory=$REPO_ROOT" -C "$REPO_ROOT" "$@"; }

choose_image() {
  local repo="${GITHUB_REPOSITORY:-}" owner sha
  [ -n "$repo" ] && [[ "$repo" == */* ]] || usage_error "GITHUB_REPOSITORY assente: serve per scegliere l'immagine (oppure imposta il segreto LH_IMAGE)"
  owner="${repo%%/*}"
  sha="$(image_git log -1 --format=%H HEAD -- "${IMAGE_FRESHNESS_PATHS[@]}" 2>/dev/null)" || sha=""
  [ -n "$sha" ] || usage_error "nessun commit del clone ha toccato i percorsi dell'immagine: clone incompleto? Imposta il segreto LH_IMAGE"
  echo "ghcr.io/${owner,,}/loyaltyhub:build-$sha"
}

# docker pull; se fallisce e il codespace ha un token, accede a ghcr.io (pacchetto non pubblico) una sola volta e riprova.
# Il token passa solo da stdin, mai negli argomenti. Uscita: 0 scaricata · 1 non esiste (manifest sconosciuto o non
# trovato) · 2 altro errore (rete, accesso, GHCR): la causa reale resta in PULL_CAUSE (una riga, senza segreti).
PULL_LOGIN_DONE=0
PULL_CAUSE=""
pull_image() {
  local out
  if out="$(docker pull --quiet "$1" 2>&1 >/dev/null)"; then return 0; fi
  if [ "$PULL_LOGIN_DONE" = 0 ] && [ -n "${GITHUB_TOKEN:-}" ] && [ -n "${GITHUB_USER:-}" ]; then
    PULL_LOGIN_DONE=1
    printf '%s' "$GITHUB_TOKEN" | docker login ghcr.io -u "$GITHUB_USER" --password-stdin >/dev/null 2>&1 || true
    if out="$(docker pull --quiet "$1" 2>&1 >/dev/null)"; then return 0; fi
  fi
  PULL_CAUSE="$(printf '%s' "$out" | head -n 1 | cut -c1-200)"
  if [ -z "$PULL_CAUSE" ] || [[ "$PULL_CAUSE" =~ [Mm]anifest\ unknown|[Nn]ot\ [Ff]ound|[Nn]ame\ unknown ]]; then return 1; fi
  return 2
}

# Stato delle esecuzioni di image.yml per il commit dell'immagine (e per HEAD, perche' un recupero a mano porta l'sha
# del suo HEAD): `in_corso` (accodata o in esecuzione), `assente` (nessuna esecuzione), `fallita`, `non_verificabile`
# (gh o token assenti, errore dell'API, esecuzione riuscita ma immagine non ancora scaricabile). gh usa il token del codespace.
build_state() {
  local sha="$1" repo="${GITHUB_REPOSITORY:-}" head runs="" part s
  command -v gh >/dev/null 2>&1 || { echo non_verificabile; return; }
  head="$(image_git rev-parse HEAD 2>/dev/null || true)"
  for s in "$sha" "$head"; do
    [ -n "$s" ] || continue
    part="$(GH_TOKEN="${GH_TOKEN:-${GITHUB_TOKEN:-}}" gh api "repos/$repo/actions/workflows/image.yml/runs?head_sha=$s&per_page=5" \
      --jq '.workflow_runs[] | .status + ":" + (.conclusion // "")' 2>/dev/null)" || { echo non_verificabile; return; }
    runs="$runs$part"$'\n'
    [ "$s" = "$head" ] && break
  done
  runs="$(printf '%s' "$runs" | sed '/^$/d')"
  if printf '%s\n' "$runs" | grep -Eq '^(queued|in_progress|waiting|pending|requested):'; then echo in_corso
  elif [ -z "$runs" ]; then echo assente
  elif printf '%s\n' "$runs" | grep -q '^completed:success$'; then echo non_verificabile
  else echo fallita; fi
}

resolve_image() {
  local ref sha short waited=0 st state tag="" tagref owner limit="${LH_AVVIO_ATTESA_S:-900}" step="${LH_AVVIO_INTERVALLO_S:-30}"
  [[ "$limit" =~ ^[0-9]+$ ]] && [[ "$step" =~ ^[1-9][0-9]*$ ]] || usage_error "LH_AVVIO_ATTESA_S e LH_AVVIO_INTERVALLO_S devono essere numeri interi (secondi)"
  ref="$(choose_image)"
  sha="${ref##*:build-}"
  short="${sha:0:7}"
  owner="${GITHUB_REPOSITORY%%/*}"
  pull_image "$ref" && { echo "immagine build-$short scaricata" >&2; echo "$ref"; return 0; }
  st=$?
  [ "$st" = 2 ] && echo "Avviso: scaricamento di build-$short non riuscito: $PULL_CAUSE" >&2
  # Un tag v* senza differenze nelle cartelle dell'immagine e' equivalente a build-<commit>: lo si usa subito.
  tag="$(image_git tag --list 'v*' --sort=-v:refname 2>/dev/null | head -n 1)"
  tagref="ghcr.io/${owner,,}/loyaltyhub:$tag"
  if [ -n "$tag" ] && image_git diff --quiet "refs/tags/$tag" HEAD -- "${IMAGE_FRESHNESS_PATHS[@]}" 2>/dev/null && pull_image "$tagref"; then
    echo "immagine build-$short non pubblicata: uso il tag $tag, che non differisce da HEAD nelle cartelle dell'immagine (equivalente)" >&2
    echo "$tagref"
    return 0
  fi
  local rilancio="Rilancia la build con «Run workflow» da main sul workflow «Build and Release Image» (.github/workflows/image.yml, workflow_dispatch), poi riavvia il codespace; oppure imposta il segreto LH_IMAGE."
  while true; do
    state="$(build_state "$sha")"
    case "$state" in
      in_corso) echo "immagine build-$short in costruzione su GitHub Actions, attendo… (${waited}/${limit} s)" >&2 ;;
      non_verificabile) echo "immagine build-$short non scaricabile e stato della build non verificabile (gh, token o API): attendo con un limite di ${limit} s (${waited}/${limit} s)" >&2 ;;
      assente)
        echo "Errore: l'immagine build-$short non esiste su ghcr.io e nessuna esecuzione di image.yml la sta costruendo, e nessun tag v* è allineato a HEAD. Non uso un'immagine più vecchia. ${PULL_CAUSE:+Causa dello scaricamento: $PULL_CAUSE. }$rilancio" >&2
        return 1 ;;
      fallita)
        echo "Errore: l'ultima esecuzione di image.yml per il commit ${short} è fallita o annullata: build-$short non esiste e nessun tag v* è allineato a HEAD. Guarda l'esecuzione nella scheda Actions. $rilancio" >&2
        return 1 ;;
    esac
    if [ "$waited" -ge "$limit" ]; then
      echo "Errore: l'immagine build-$short non è comparsa su ghcr.io dopo ${limit} s e nessun tag v* è allineato a HEAD. Non uso un'immagine più vecchia. ${PULL_CAUSE:+Ultima causa dello scaricamento: $PULL_CAUSE. }$rilancio" >&2
      return 1
    fi
    sleep "$step"
    waited=$((waited + step))
    if pull_image "$ref"; then echo "immagine build-$short scaricata" >&2; echo "$ref"; return 0; fi
  done
}

cmd_immagine() {
  case "${1:-}" in
    --scegli) choose_image; return ;;
    --risolvi) resolve_image; return ;;
    '') ;;
    *) usage_error "uso: $0 immagine [--scegli|--risolvi]" ;;
  esac
  local image="${LH_IMAGE:-}"
  [ -n "$image" ] || usage_error "LH_IMAGE assente: serve l'immagine da controllare"
  # safe.directory: il comando gira come root (sudo) su un clone di un altro utente.
  local -a git=(git -c "safe.directory=$REPO_ROOT" -C "$REPO_ROOT")
  local head rev="" label="" tag="" last="${image##*/}"
  head="$("${git[@]}" rev-parse HEAD 2>/dev/null)" || { echo "Avviso: freschezza dell'immagine non verificabile: $REPO_ROOT non è un clone git" >&2; return 0; }

  label="$(docker image inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$image" 2>/dev/null || true)"
  [[ "$label" =~ ^[0-9a-f]{7,40}$ ]] && rev="$label"
  if [ -z "$rev" ] && [[ "$last" == *:* ]] && [[ "${last##*:}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    tag="${last##*:}"
    rev="$("${git[@]}" rev-list -n1 "refs/tags/$tag" 2>/dev/null || true)"
  fi
  if [ -z "$rev" ]; then
    echo "Avviso: revisione dell'immagine $image non verificabile (nessuna etichetta org.opencontainers.image.revision né tag di rilascio noto): si continua senza controllo" >&2
    return 0
  fi
  # Il commit dell'immagine può mancare nel clone locale (commit e tag li scarica avvio.sh come utente, mai root).
  if ! "${git[@]}" cat-file -e "$rev^{commit}" 2>/dev/null; then
    echo "Avviso: revisione dell'immagine $image (${rev:0:7}) non verificabile: commit assente dal clone locale: si continua senza controllo" >&2
    return 0
  fi
  rev="$("${git[@]}" rev-parse "$rev^{commit}")"
  if [ "$rev" = "$head" ] || "${git[@]}" diff --quiet "$rev" HEAD -- "${IMAGE_FRESHNESS_PATHS[@]}" 2>/dev/null; then
    info "immagine aggiornata rispetto al repository: $image (${rev:0:7}, HEAD ${head:0:7})"
    return 0
  fi
  local msg
  if "${git[@]}" merge-base --is-ancestor "$rev" HEAD 2>/dev/null; then
    msg="immagine $image costruita dal commit ${rev:0:7}, HEAD del repository ${head:0:7}: l'immagine è più vecchia degli script del repo: imposta il segreto del codespace LH_IMAGE su un'immagine costruita da questo commit, oppure crea un nuovo tag di rilascio${LH_IMMAGINE_ESPLICITA:+ (o togli il segreto LH_IMAGE per seguire main)}"
  else
    # Non antenato: l'immagine è più nuova del clone o di un altro ramo, "più vecchia" sarebbe falso.
    msg="l'immagine $image (${rev:0:7}) non è un antenato di HEAD (${head:0:7}): è più nuova del clone o di un altro ramo: aggiorna il clone del codespace (git pull) oppure scegli un'immagine costruita da questo commit"
  fi
  if [ "${LH_VETRINA_ALLOW_STALE_IMAGE:-}" = true ]; then
    echo "AVVISO FORTE: $msg. Si continua perché LH_VETRINA_ALLOW_STALE_IMAGE=true (solo per diagnosi): il comportamento può essere incoerente." >&2
    return 0
  fi
  echo "Errore: $msg. (Solo per diagnosi: LH_VETRINA_ALLOW_STALE_IMAGE=true)" >&2
  exit 2
}

# Utenti di test (ADR-051 decisione 1, Q-676): solo dentro un GitHub Codespace, lo stesso criterio che passa a hub e web
# LH_TEST_USERS_ALLOWED (compose()). Altrove gli overlay si applicano senza account di test: l'hub li rifiuterebbe
# comunque, ma non devono nemmeno esistere in Keycloak con una password pubblica.
test_users_allowed() { [ "${CODESPACES:-}" = true ]; }

apply_realm_overlay() {
  local mode="${1:-}" pw args=()
  [ -n "$mode" ] && args+=("$mode")
  test_users_allowed || args+=(--no-test-users)
  IFS= read -r pw < "$LH_VETRINA_DIR/secrets/idp-admin-password" || [ -n "$pw" ]
  # Le password vanno solo nell'ambiente del processo figlio (mai negli argomenti, mai stampate). Quella del proprietario
  # (ADR-055) e' vuota se il segreto del codespace manca: allora lo script non crea l'utente.
  # Uscita 3 di apply-overlay.sh = solo il passo del proprietario e' fallito (password non valida): il resto e' applicato,
  # la console master resta chiusa e la vetrina non si ferma.
  local rc=0
  LH_VETRINA_MASTER_ADMIN_PASSWORD="$MASTER_OWNER_PASSWORD" KC_BOOTSTRAP_ADMIN_PASSWORD="$pw" KEYCLOAK_URL="$KEYCLOAK_LOCAL" \
    "$REPO_ROOT/deploy/idp/vetrina/apply-overlay.sh" ${args[@]+"${args[@]}"} || rc=$?
  # Le sole verifiche (--check-operators) non cambiano lo stato della console.
  if [ -n "$mode" ]; then return "$rc"; fi
  case "$rc" in
    0) if [ -n "$MASTER_OWNER_PASSWORD" ]; then master_console aperta; else master_console chiusa; fi ;;
    3) echo "Avviso: console del realm master chiusa: l'utente del proprietario non e' pronto (motivo sopra, mai il valore della password)." >&2
       master_console chiusa ;;
    *) master_console chiusa || true
       return "$rc" ;;
  esac
}

# Database di Keycloak da zero (ADR-051 decisione 10, Q-672): ferma Keycloak e chi dipende dal suo emittente, ricrea
# SOLO il database `idp` (non quello dell'hub) con lo stesso proprietario e gli stessi privilegi dello script iniziale di
# Postgres (deploy/compose/postgres-init/10-idp.sh). Alla ripartenza Keycloak reimporta i realm di base.
cmd_idp_reset() {
  info "database di Keycloak ricreato da zero (l'hub non si tocca)"
  compose stop idp web hub proxy >/dev/null 2>&1 || true
  # Il database e' nuovo e l'utente del proprietario non c'e' piu': la console master si richiude (il proxy e' fermo, nessun
  # riavvio); la riapre apply_realm_overlay solo dopo aver ricreato e verificato l'utente (ADR-055).
  master_console chiusa
  compose up -d --wait --wait-timeout 300 postgres
  compose exec -T postgres psql -v ON_ERROR_STOP=1 -U loyaltyhub -d loyaltyhub \
    -c 'DROP DATABASE IF EXISTS idp WITH (FORCE)' \
    -c 'CREATE DATABASE idp OWNER idp' \
    -c 'REVOKE ALL ON DATABASE idp FROM PUBLIC' > /dev/null
}

# Codice HTTP di un URL pubblico, senza seguire reindirizzamenti ("000" se non risponde).
http_code() { curl -sS -o /dev/null -w '%{http_code}' --max-redirs 0 --max-time 15 "$1" 2>/dev/null || true; }

# Porte pubbliche davvero raggiungibili: Keycloak (discovery del realm dei membri, 200) e web (login dei membri, 303 del BFF).
public_ports_ready() {
  [ "$(http_code "https://$LH_VETRINA_IDP_HOST/realms/loyaltyhub-members/.well-known/openid-configuration")" = 200 ] \
    && [ "$(http_code "https://$LH_VETRINA_WEB_HOST/api/auth/login?realm=members")" = 303 ]
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
  # Una porta privata risponde 302 verso il login di GitHub, e `curl -f` considera riuscito un 302: si confronta il codice
  # esatto, senza seguire reindirizzamenti, su entrambe le porte (discovery di Keycloak 200, login dei membri del web 303).
  if [ "$soft" = 1 ] && ! public_ports_ready; then
    info "membri di test: le porte $CODESPACE_WEB_PORT e $CODESPACE_IDP_PORT non rispondono ancora dall'indirizzo pubblico (non pubbliche?): li registra avvio.sh dopo averle pubblicate"
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
  [ -n "$cmd" ] || usage_error "uso: $0 codespace|provision|preflight [--offline]|up|down|reset|idp-reset|operators|membri|immagine [--scegli|--risolvi]|programma|compose <argomenti>"
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
    immagine) cmd_immagine "$@" ;;
    programma) load_config; cmd_programma "$@" ;;
    compose) load_config; compose "$@" ;;
    *) usage_error "comando sconosciuto: $cmd" ;;
  esac
}

main "$@"
