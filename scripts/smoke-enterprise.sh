#!/usr/bin/env bash
# Smoke enterprise con login OIDC reale sul compose di riferimento (F2-QA-04, M8.2, ADR-049, M8.14 V6).
# Lo usa il job `smoke enterprise (compose, OIDC)` di .github/workflows/ci.yml; gira anche in locale con Docker.
#
#   bash scripts/smoke-enterprise.sh up     # segreti e TLS di prova, compose enterprise, overlay di test del realm
#   bash scripts/smoke-enterprise.sh run    # scripts/smoke-enterprise.mjs compose
#   bash scripts/smoke-enterprise.sh logs   # diagnostica
#   bash scripts/smoke-enterprise.sh down   # smonta tutto e cancella segreti e certificati
#
# Prerequisiti: Docker con compose, openssl, python3, curl, Node 22, l'immagine unica in LH_IMAGE (default
# lh-image:ci, costruita da deploy/image/Dockerfile) e i nomi web.lh.test e idp.lh.test che risolvono a 127.0.0.1
# (in CI una riga in /etc/hosts; il proxy di prova li serve su 127.0.0.1:8443).
# Variabili: LH_IMAGE, LH_CI_DIR (default $RUNNER_TEMP/lh-smoke-enterprise o /tmp/lh-smoke-enterprise),
#            LH_SMOKE_WAIT_S (attesa massima dell'avvio, default 900).
#
# Regola 20: segreti, CA di prova e password temporanee si generano qui, a ogni esecuzione, in file 0600 di LH_CI_DIR
# (cartella 0700, fuori dal repository); passano a compose con --env-file e agli script da file o dall'ambiente del
# solo processo, mai come argomenti né su stdout. In GitHub Actions ogni valore è anche mascherato (::add-mask::).
# Nessun client di produzione cambia: il login passa dal client `web` (Authorization Code + PKCE) e dalla MFA del
# realm; l'overlay di test (deploy/idp/test-idp) aggiunge solo il client di prova, il membro `testmember`, il broker e
# l'LDAP di prova. L'operatore è `marta.admin` del realm, con la password temporanea di deploy/idp/bootstrap.sh.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

LH_IMAGE="${LH_IMAGE:-lh-image:ci}"
DIR="${LH_CI_DIR:-${RUNNER_TEMP:-/tmp}/lh-smoke-enterprise}"
WAIT_S="${LH_SMOKE_WAIT_S:-900}"
ENV_FILE="$DIR/ci.env"
TLS="$DIR/tls"
SECRETS="$DIR/secrets"
WEB_HOST=web.lh.test
IDP_HOST=idp.lh.test
PORT=8443
WEB_URL="https://${WEB_HOST}:${PORT}"
IDP_URL="https://${IDP_HOST}:${PORT}"
HUB_URL="http://127.0.0.1:8080"
KEYCLOAK_ADMIN_URL="http://127.0.0.1:8180"
OPERATOR=marta.admin
MEMBER=testmember
PROJECT=lh-smoke-enterprise

compose() {
  docker compose -p "$PROJECT" --env-file "$ENV_FILE" \
    -f deploy/compose/reference.yml -f deploy/compose/ci/smoke-enterprise.yml "$@"
}

# Valori casuali: esadecimale per password e segreti dei client, base64 di 32 byte per le chiavi del web e dell'hub.
hex() { openssl rand -hex "$1"; }
key32() { openssl rand -base64 32; }

# Variabili dell'env file che sono segreti (mascherate in GitHub Actions).
SECRET_VARS=(LH_DB_PASSWORD LH_IDP_DB_PASSWORD LH_IDP_ADMIN_PASSWORD LH_WEB_CLIENT_SECRET LH_PORTAL_CLIENT_SECRET LH_WIDGETS_CLIENT_SECRET
  LH_CMS_CLIENT_SECRET LH_WEB_SESSION_KEY LH_SUBJECT_KEY LH_TEST_IDP_SECRET LH_LDAP_BIND_CREDENTIAL
  LH_LDAP_TEST_CLIENT_SECRET LH_MEMBER_TEST_PASSWORD)

load_env() {
  [ -r "$ENV_FILE" ] || { echo "Manca $ENV_FILE: eseguire prima 'up'." >&2; exit 1; }
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
}

prepare() {
  umask 077
  rm -rf "$DIR"
  mkdir -p "$TLS" "$SECRETS"
  chmod 700 "$DIR" "$TLS" "$SECRETS"

  # CA di prova e certificato del proxy per i due nomi (validi 2 giorni). La chiave della CA si cancella subito:
  # nessuno può firmare altro con questa CA. Il certificato della CA non è un segreto: lo legge il web (uid del
  # container), quindi 0644.
  openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj "/CN=Loyalty Hub CI smoke CA" \
    -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" \
    -keyout "$TLS/ca.key" -out "$TLS/ca.crt" 2>/dev/null
  openssl req -newkey rsa:2048 -nodes -subj "/CN=${WEB_HOST}" -keyout "$TLS/server.key" -out "$TLS/server.csr" 2>/dev/null
  printf 'subjectAltName=DNS:%s,DNS:%s\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n' \
    "$WEB_HOST" "$IDP_HOST" > "$TLS/server.ext"
  openssl x509 -req -in "$TLS/server.csr" -CA "$TLS/ca.crt" -CAkey "$TLS/ca.key" -CAcreateserial -days 2 \
    -extfile "$TLS/server.ext" -out "$TLS/server.crt" 2>/dev/null
  rm -f "$TLS/ca.key" "$TLS/ca.srl" "$TLS/server.csr" "$TLS/server.ext"
  chmod 644 "$TLS/ca.crt"
  chmod 600 "$TLS/server.key" "$TLS/server.crt"

  {
    echo "LH_IMAGE=${LH_IMAGE}"
    echo "LH_PROFILE=enterprise"
    echo "LH_IDENTITY_MODE=oidc"
    echo "LH_WEB_URL=${WEB_URL}"
    echo "LH_IDP_PUBLIC_URL=${IDP_URL}"
    echo "LH_CI_UID=$(id -u)"
    echo "LH_CI_GID=$(id -g)"
    echo "LH_CI_TLS_DIR=${TLS}"
    echo "LH_DB_PASSWORD=$(hex 24)"
    echo "LH_IDP_DB_PASSWORD=$(hex 24)"
    echo "LH_IDP_ADMIN_PASSWORD=$(hex 24)"
    echo "LH_WEB_CLIENT_SECRET=$(hex 32)"
    echo "LH_PORTAL_CLIENT_SECRET=$(hex 32)"
    echo "LH_WIDGETS_CLIENT_SECRET=$(hex 32)"
    echo "LH_CMS_CLIENT_SECRET=$(hex 32)"
    echo "LH_WEB_SESSION_KEY=$(key32)"
    echo "LH_SUBJECT_KEY=$(key32)"
    # Overlay di test del realm (deploy/idp/test-idp/apply-overlay.sh, OVERLAY_VARS). Il broker verso l'IdP di prova
    # non si usa nello smoke: URL https su un dominio riservato (.invalid), mai contattato.
    echo "LH_TEST_IDP_AUTH_URL=https://idp-test.invalid/realms/idp-test/protocol/openid-connect/auth"
    echo "LH_TEST_IDP_TOKEN_URL=https://idp-test.invalid/realms/idp-test/protocol/openid-connect/token"
    echo "LH_TEST_IDP_USERINFO_URL=https://idp-test.invalid/realms/idp-test/protocol/openid-connect/userinfo"
    echo "LH_TEST_IDP_ISSUER=https://idp-test.invalid/realms/idp-test"
    echo "LH_TEST_IDP_SECRET=$(hex 24)"
    echo "LH_LDAP_BIND_CREDENTIAL=$(hex 24)"
    echo "LH_LDAP_TEST_CLIENT_SECRET=$(hex 32)"
    echo "LH_MEMBER_TEST_PASSWORD=$(hex 16)"
  } > "$ENV_FILE"
  chmod 600 "$ENV_FILE"

  load_env
  if [ "${GITHUB_ACTIONS:-}" = "true" ]; then
    for v in "${SECRET_VARS[@]}"; do echo "::add-mask::${!v}"; done
  fi
  # Credenziali del membro di prova nel formato di bootstrap.sh, per lo smoke (file 0600).
  printf '%s: %s\n' "$MEMBER" "$LH_MEMBER_TEST_PASSWORD" > "$SECRETS/membro.txt"
  chmod 600 "$SECRETS/membro.txt"
}

# Primo servizio uscito (escluso migrate, che deve uscire con 0), o vuoto.
exited_service() {
  local svc id code
  for svc in $(compose ps -a --status exited --services 2>/dev/null); do
    if [ "$svc" = "migrate" ]; then
      id="$(compose ps -a -q migrate)"
      code="$(docker inspect -f '{{.State.ExitCode}}' "$id")"
      [ "$code" = "0" ] && continue
    fi
    echo "$svc"
    return
  done
}

wait_ready() {
  local deadline=$((SECONDS + WAIT_S)) dead health
  local curl_ca=(curl -fsS -o /dev/null --max-time 10 --cacert "$TLS/ca.crt")
  while :; do
    dead="$(exited_service)"
    if [ -n "$dead" ]; then
      echo "::error::il servizio ${dead} è terminato durante l'avvio" >&2
      return 1
    fi
    # Niente `curl | grep -q`: con pipefail un SIGPIPE darebbe un falso negativo.
    health="$(curl -fsS --max-time 10 "$HUB_URL/actuator/health" 2>/dev/null || true)"
    if [[ "$health" == *'"status":"UP"'* ]] \
      && "${curl_ca[@]}" "$IDP_URL/realms/loyaltyhub/.well-known/openid-configuration" 2>/dev/null \
      && "${curl_ca[@]}" "$IDP_URL/realms/loyaltyhub-members/.well-known/openid-configuration" 2>/dev/null \
      && "${curl_ca[@]}" "$WEB_URL/api/demo/status" 2>/dev/null; then
      echo "Stack enterprise pronto: hub UP, discovery OIDC dei due realm e web raggiungibili dal proxy TLS."
      return 0
    fi
    if [ "$SECONDS" -ge "$deadline" ]; then
      echo "::error::stack enterprise non pronto entro ${WAIT_S} s" >&2
      compose ps -a >&2 || true
      return 1
    fi
    sleep 10
  done
}

up() {
  for host in "$WEB_HOST" "$IDP_HOST"; do
    if [ "$(getent hosts "$host" | awk '{print $1; exit}')" != "127.0.0.1" ]; then
      echo "Il nome $host non risolve a 127.0.0.1: aggiungere '127.0.0.1 $WEB_HOST $IDP_HOST' a /etc/hosts." >&2
      exit 1
    fi
  done
  prepare
  compose config -q
  compose up -d
  wait_ready

  # Overlay di test del realm (broker, client di prova, membro di prova, federazione LDAP) e password temporanee
  # degli operatori del realm (solo nel file 0600, mai su stdout). La password di amministrazione passa dall'ambiente
  # del solo processo, come prevedono i due script.
  KC_BOOTSTRAP_ADMIN_PASSWORD="$LH_IDP_ADMIN_PASSWORD" KEYCLOAK_URL="$KEYCLOAK_ADMIN_URL" \
    bash deploy/idp/test-idp/apply-overlay.sh
  KC_BOOTSTRAP_ADMIN_PASSWORD="$LH_IDP_ADMIN_PASSWORD" KEYCLOAK_URL="$KEYCLOAK_ADMIN_URL" \
    LH_IDP_BOOTSTRAP_OUT="$SECRETS/operatori.txt" bash deploy/idp/bootstrap.sh
}

run() {
  [ -r "$SECRETS/operatori.txt" ] || { echo "Mancano le credenziali di prova: eseguire prima 'up'." >&2; exit 1; }
  NODE_EXTRA_CA_CERTS="$TLS/ca.crt" node scripts/smoke-enterprise.mjs compose \
    --web "$WEB_URL" --hub "$HUB_URL" \
    --operator "$OPERATOR" --operator-credentials "$SECRETS/operatori.txt" \
    --member "$MEMBER" --member-credentials "$SECRETS/membro.txt"
}

logs() {
  set +e
  compose ps -a
  # Stato dei componenti della salute dell'hub (solo nome, stato ed eventuale classe d'errore, nessun dettaglio).
  echo "::group::salute hub"
  curl -sS --max-time 10 "$HUB_URL/actuator/health" | python3 -c '
import json, sys
try:
    h = json.load(sys.stdin)
except ValueError:
    sys.exit("risposta non JSON")
print("status", h.get("status"))
for name, c in sorted((h.get("components") or {}).items()):
    print(" ", name, c.get("status"), (c.get("details") or {}).get("error", ""))
'
  docker inspect -f '{{range .State.Health.Log}}{{.ExitCode}} {{.Output}}{{end}}' "$(compose ps -a -q hub)" | tail -n 20
  echo "::endgroup::"
  for svc in proxy web hub migrate idp ldap kafka postgres; do
    echo "::group::log $svc"
    compose logs --no-color --tail=300 "$svc"
    echo "::endgroup::"
  done
  # Avvisi ed errori dell'hub su tutto il log, non solo sulle ultime righe (i consumer Kafka le riempiono).
  echo "::group::hub WARN/ERROR"
  compose logs --no-color hub | grep -E ' (WARN|ERROR) |Started |APPLICATION FAILED' | tail -n 120
  echo "::endgroup::"
}

down() {
  if [ -r "$ENV_FILE" ]; then
    compose down -v --remove-orphans > /dev/null 2>&1 || true
  fi
  rm -rf "$DIR"
}

case "${1:-}" in
  up) up ;;
  run) run ;;
  logs) logs ;;
  down) down ;;
  *) echo "Uso: bash scripts/smoke-enterprise.sh up | run | logs | down" >&2; exit 2 ;;
esac
