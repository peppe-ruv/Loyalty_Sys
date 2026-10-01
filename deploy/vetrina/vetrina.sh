#!/usr/bin/env bash
# Vetrina enterprise ospitata: comandi dell'host (F2-DIST-03, F2-DIST-09, ADR-049, M8.14 V3; Q-616, Q-620, Q-621, Q-624).
#
# Avvia il compose di riferimento con l'overlay di vetrina (deploy/vetrina/compose.vetrina.yml) su un host Oracle Cloud
# A1 (arm64). Runbook completo: deploy/vetrina/README.md.
#
#   vetrina.sh provision            segreti, CA locale e certificati di Postgres e Kafka (idempotente, regola 20)
#   vetrina.sh preflight [--offline]
#                                   controlli prima dell'avvio: configurazione, architettura arm64 dell'host e delle
#                                   immagini, permessi dei segreti, certificati, nessun segreto nell'ambiente
#   vetrina.sh up                   preflight e avvio (docker compose up -d --wait)
#   vetrina.sh down                 arresto, volumi conservati
#   vetrina.sh reset                azzeramento settimanale (Q-624): volumi di Postgres e Kafka ricreati, realm
#                                   reimportato, overlay del realm, account operatore; nessun backup
#   vetrina.sh operators            account operatore nominativi da operators.list (Q-618), password temporanee in un
#                                   file 0600, poi verifica della MFA (apply-overlay.sh --check-operators)
#   vetrina.sh programma            configurazione di programma da seed/ con il token di un operatore (Q-617, Q-626):
#                                   passo interattivo (Device Authorization Grant con MFA), mai nel timer (Q-630)
#   vetrina.sh compose <argomenti>  docker compose con i file e il progetto della vetrina (ps, logs …)
#
# Configurazione: file KEY=VALORE (LH_VETRINA_CONFIG, default /etc/loyaltyhub-vetrina/vetrina.env; modello in
# vetrina.env.example). Solo le chiavi ammesse, nessun segreto. I segreti stanno in $LH_VETRINA_DIR/secrets e
# $LH_VETRINA_DIR/tls, file 0600 creati da `provision`; lo script non stampa mai un valore, solo nomi e percorsi.
# Uscita: 0 ok · 1 controllo o comando fallito · 2 uso o configurazione non validi.
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
REFERENCE="$REPO_ROOT/deploy/compose/reference.yml"
OVERLAY="$SCRIPT_DIR/compose.vetrina.yml"
PROJECT="loyaltyhub-vetrina"
CONFIG="${LH_VETRINA_CONFIG:-/etc/loyaltyhub-vetrina/vetrina.env}"

# Utenti dei container che leggono i file montati: immagine unica, Keycloak e apache/kafka girano come 1000; la chiave
# del server Postgres deve essere dell'utente postgres dell'immagine Debian (999), con permessi 0600.
CONTAINER_UID=1000
POSTGRES_UID=999

# Chiavi ammesse nel file di configurazione (nessun segreto).
CONFIG_KEYS=(LH_IMAGE LH_VETRINA_DIR LH_VETRINA_WEB_HOST LH_VETRINA_IDP_HOST LH_VETRINA_PUBLIC_ADDRESS LH_HUB_DEMO_URL LH_BIND_ADDRESS)
# Segreti generati sull'host: nome del file e forma (password = 32 caratteri url-safe; key = 32 byte in base64).
SECRETS=(db-password:password idp-db-password:password idp-admin-password:password web-client-secret:password
         widgets-client-secret:password cms-client-secret:password web-session-key:key subject-key:key)
# Variabili di segreto del compose di riferimento: nella vetrina non devono stare nell'ambiente (regola 20).
SECRET_ENV=(LH_DB_PASSWORD LH_IDP_DB_PASSWORD LH_IDP_ADMIN_PASSWORD LH_WEB_CLIENT_SECRET LH_WIDGETS_CLIENT_SECRET
            LH_CMS_CLIENT_SECRET LH_WEB_SESSION_KEY LH_SUBJECT_KEY LH_GRAFANA_ADMIN_PASSWORD KC_BOOTSTRAP_ADMIN_PASSWORD)
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
  case "$LH_VETRINA_PUBLIC_ADDRESS" in
    0.0.0.0|127.*) usage_error "LH_VETRINA_PUBLIC_ADDRESS non può essere 0.0.0.0 né loopback: indica l'indirizzo dell'interfaccia dell'host" ;;
  esac
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
  env "${unset_args[@]}" docker compose --project-name "$PROJECT" -f "$REFERENCE" -f "$OVERLAY" "$@"
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

check_images_arm64() {
  # Ogni immagine del compose deve avere una variante linux/arm64 (host Oracle A1). Legge il manifest dal registro.
  local img ko=0 images
  images="$(compose config --images)"
  for img in $images; do
    if docker manifest inspect -v "$img" 2>/dev/null | python3 -c '
import json, sys
data = json.load(sys.stdin)
items = data if isinstance(data, list) else [data]
archs = set()
for it in items:
    plat = (it.get("Descriptor") or {}).get("platform") or {}
    archs.add(plat.get("architecture"))
    for m in (it.get("Raw") or {}).get("manifests", []) or []:
        archs.add((m.get("platform") or {}).get("architecture"))
sys.exit(0 if "arm64" in archs else 1)'; then
      info "arm64: $img"
    else
      echo "  immagine senza variante linux/arm64 (o registro non raggiungibile): $img" >&2
      ko=1
    fi
  done
  return "$ko"
}

cmd_preflight() {
  local offline=0
  [ "${1:-}" = "--offline" ] && offline=1
  local ko=0
  # 1. Architettura dell'host (Oracle A1 = arm64). LH_VETRINA_EXPECTED_ARCH solo per provare l'overlay altrove.
  local arch expected="${LH_VETRINA_EXPECTED_ARCH:-aarch64}"
  arch="$(uname -m)"
  if [ "$arch" = "$expected" ] || { [ "$expected" = aarch64 ] && [ "$arch" = arm64 ]; }; then
    info "architettura dell'host: $arch"
  else
    echo "  architettura dell'host $arch, attesa $expected (Oracle Cloud A1 è arm64)" >&2
    ko=1
  fi
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
  # 6. Compose valido con l'overlay, poi immagini arm64 (serve il registro: --offline lo salta).
  need docker
  compose config -q || die "docker compose config fallito con l'overlay di vetrina"
  if [ "$offline" = 0 ]; then
    need python3
    check_images_arm64 || die "immagini non disponibili per linux/arm64"
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

apply_realm_overlay() {
  local mode="${1:-}" pw
  IFS= read -r pw < "$LH_VETRINA_DIR/secrets/idp-admin-password" || [ -n "$pw" ]
  # La password va solo nell'ambiente del processo figlio (mai negli argomenti, mai stampata).
  KC_BOOTSTRAP_ADMIN_PASSWORD="$pw" KEYCLOAK_URL="$KEYCLOAK_LOCAL" \
    "$REPO_ROOT/deploy/idp/vetrina/apply-overlay.sh" ${mode:+"$mode"}
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
  info "azzeramento completato $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  info "passo interattivo rimasto: vetrina.sh programma (configurazione di programma con il token di un operatore, Q-617, Q-630)"
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
  [ -n "$cmd" ] || usage_error "uso: $0 provision|preflight [--offline]|up|down|reset|operators|programma|compose <argomenti>"
  shift
  case "$cmd" in
    provision) load_config; cmd_provision ;;
    preflight) load_config; cmd_preflight "$@" ;;
    up) load_config; cmd_up "$@" ;;
    down) load_config; compose down ;;
    reset) load_config; cmd_reset "$@" ;;
    operators) load_config; cmd_operators ;;
    programma) load_config; cmd_programma "$@" ;;
    compose) load_config; compose "$@" ;;
    *) usage_error "comando sconosciuto: $cmd" ;;
  esac
}

main "$@"
