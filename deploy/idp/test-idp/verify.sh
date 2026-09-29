#!/usr/bin/env bash
# SOLO PROVA (F2-IAM-04, F2-IAM-03, ADR-027, ADR-048). Verifica non interattiva dell'IdP di prova e dei ruoli nei token.
#
# Controlli (senza argomenti si eseguono tutti, in questo ordine, e ci si ferma al primo che fallisce):
#   ldap     federazione LDAP: grant password dell'utente LDAP con il client di prova (F2-IAM-04);
#   member   il membro di prova `testmember` dell'overlay: nel token `lh_roles` contiene MEMBER e nessun altro ruolo
#            applicativo, `email_verified` e' false (Q-557, ADR-048 decisione 11);
#   sources  ogni client `src-*` del realm: un token client_credentials (private_key_jwt) ha `lh_roles` = [SOURCE] e
#            nient'altro, quindi mai MEMBER ne' ruoli operatore (Q-557, Q-494, ADR-048 decisione 11).
# Uso: verify.sh [ldap] [member] [sources]
#
# Prerequisiti: realm `loyaltyhub` avviato e overlay applicato con apply-overlay.sh (che crea il client di
# prova `lh-ldap-test` e l'utente `testmember`). Il client di produzione `web` ha i direct access grants
# disattivati di proposito: qui si usa solo `lh-ldap-test`. Servono curl e python3 (e openssl per `sources`).
#
# Variabili: KEYCLOAK_URL (default http://localhost:8080)
#   ldap, member: LH_LDAP_TEST_CLIENT_SECRET (richiesta)
#   ldap:         LH_LDAP_TEST_USERNAME / LH_LDAP_TEST_PASSWORD (default: utente del seed ldap-seed.ldif)
#   member:       LH_MEMBER_TEST_PASSWORD (richiesta, la stessa passata ad apply-overlay.sh), LH_MEMBER_TEST_USERNAME
#                 (default testmember)
#   sources:      KC_BOOTSTRAP_ADMIN_PASSWORD o LH_IDP_ADMIN_PASSWORD (richiesta), KC_BOOTSTRAP_ADMIN_USERNAME o
#                 LH_IDP_ADMIN_USERNAME (default admin)
#
# `sources`: i client `src-*` autenticano con private_key_jwt e la chiave pubblica della fonte e' un JWKS che il
# repository non ha (nessun segreto nel repository). Per ottenere un token vero lo script genera una coppia di chiavi
# RSA usa-e-getta in una cartella temporanea (0700, mai nel repository), registra per pochi secondi la chiave pubblica
# come JWKS inline del client (solo gli attributi use.jwks.url, use.jwks.string e jwks.string, con l'API admin), firma
# l'asserzione, chiede il token e ripristina subito gli attributi originali (anche in caso di errore o di interruzione).
# Per questo rifiuta di girare se nel realm manca il client `lh-ldap-test`, cioe' se non e' il realm di prova.
set -euo pipefail

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8080}"
REALM="loyaltyhub"
CLIENT_ID="lh-ldap-test"

CHECKS=("$@")
if [ "${#CHECKS[@]}" -eq 0 ]; then
  CHECKS=(ldap member sources)
fi
for c in "${CHECKS[@]}"; do
  case "$c" in
    ldap | member | sources) ;;
    *) echo "Errore: controllo sconosciuto '${c}' (ammessi: ldap member sources)." >&2; exit 1 ;;
  esac
done
want() {
  local c
  for c in "${CHECKS[@]}"; do
    [ "$c" = "$1" ] && return 0
  done
  return 1
}

command -v python3 >/dev/null || { echo "Errore: serve python3." >&2; exit 1; }
command -v curl >/dev/null || { echo "Errore: serve curl." >&2; exit 1; }

if want ldap || want member; then
  CLIENT_SECRET="${LH_LDAP_TEST_CLIENT_SECRET:?LH_LDAP_TEST_CLIENT_SECRET deve essere impostata (vedi apply-overlay.sh)}"
fi

WORK="$(mktemp -d)"
chmod 700 "$WORK"
umask 077

# Uscita: ripristina i client src-* ancora modificati (solo `sources`), poi cancella la cartella temporanea.
# Se un ripristino non riesce l'uscita e' un errore, e il messaggio dice cosa correggere a mano.
cleanup() {
  local status=$?
  if [ -s "$WORK/restore.list" ]; then
    restore_pending || status=1
  fi
  rm -rf "$WORK"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Grant password con il client di prova. $1 utente, $2 password, $3 file di uscita (risposta del token endpoint).
# Credenziali in un file (non sulla riga di comando), codificate come form. Esce con 1 se la risposta non e' 200.
password_grant() {
  local code
  CLIENT_SECRET="$CLIENT_SECRET" GRANT_USER="$1" GRANT_PASSWORD="$2" CLIENT_ID="$CLIENT_ID" \
    python3 -c 'import os,urllib.parse;print(urllib.parse.urlencode({"grant_type":"password","client_id":os.environ["CLIENT_ID"],"client_secret":os.environ["CLIENT_SECRET"],"username":os.environ["GRANT_USER"],"password":os.environ["GRANT_PASSWORD"],"scope":"openid"}),end="")' \
    > "$WORK/form"
  code="$(curl -sS -o "$3" -w '%{http_code}' -X POST \
    "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' --data-binary "@$WORK/form")"
  if [ "$code" != "200" ]; then
    echo "Errore: il token endpoint ha risposto ${code}: $(python3 -c 'import sys,json;d=json.load(open(sys.argv[1]));print(d.get("error"),"-",d.get("error_description"))' "$3" 2>/dev/null || echo 'risposta non JSON')" >&2
    exit 1
  fi
}

if want ldap; then
  TEST_USER="${LH_LDAP_TEST_USERNAME:-testuser}"
  # Password del solo utente fittizio di ldap-seed.ldif (dato di prova, non un segreto).
  TEST_PASSWORD="${LH_LDAP_TEST_PASSWORD:-testpassword}"

  echo "== Federazione LDAP: grant password per ${TEST_USER} con il client di prova ${CLIENT_ID} =="
  password_grant "$TEST_USER" "$TEST_PASSWORD" "$WORK/token.json"

  # Decodifica del payload dell'access token (senza verifica di firma: qui interessano i claim) e asserzioni.
  python3 - "$WORK/token.json" "$TEST_USER" <<'PY'
import base64, json, sys
tok = json.load(open(sys.argv[1]))["access_token"]
payload = tok.split(".")[1]
claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
aud = claims.get("aud")
aud_list = aud if isinstance(aud, list) else [aud] if aud else []
shown = {k: claims.get(k) for k in ("iss", "aud", "azp", "preferred_username", "lh_roles", "scope")}
print("Claim dell'access token:")
print(json.dumps(shown, indent=2, ensure_ascii=False))
errors = []
if "hub" not in aud_list:
    errors.append(f"aud non contiene 'hub': {aud!r}")
if claims.get("preferred_username") != sys.argv[2]:
    errors.append(f"preferred_username atteso {sys.argv[2]!r}, trovato {claims.get('preferred_username')!r}")
# lh_roles: l'utente LDAP non ha ruoli applicativi mappati; il claim e' comunque presente perche'
# contiene i ruoli di default del realm (default-roles-loyaltyhub e i suoi composti).
if "lh_roles" not in claims:
    errors.append("claim lh_roles assente")
elif not isinstance(claims["lh_roles"], list):
    errors.append(f"lh_roles non e' una lista: {claims['lh_roles']!r}")
if errors:
    for e in errors:
        print("ERRORE:", e, file=sys.stderr)
    sys.exit(1)
print("OK: aud contiene 'hub', preferred_username corretto, lh_roles presente.")
PY
fi

if want member; then
  MEMBER_USER="${LH_MEMBER_TEST_USERNAME:-testmember}"
  # Come la password del client di prova: segnaposto ${LH_MEMBER_TEST_PASSWORD} dell'overlay, valore solo nell'ambiente.
  MEMBER_PASSWORD="${LH_MEMBER_TEST_PASSWORD:?LH_MEMBER_TEST_PASSWORD deve essere impostata (vedi apply-overlay.sh)}"

  echo
  echo "== Membro di prova: token di ${MEMBER_USER} con il client di prova ${CLIENT_ID} =="
  password_grant "$MEMBER_USER" "$MEMBER_PASSWORD" "$WORK/member-token.json"

  python3 - "$WORK/member-token.json" "$MEMBER_USER" <<'PY'
import base64, json, sys
tok = json.load(open(sys.argv[1]))["access_token"]
payload = tok.split(".")[1]
claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
aud = claims.get("aud")
aud_list = aud if isinstance(aud, list) else [aud] if aud else []
shown = {k: claims.get(k) for k in ("iss", "aud", "azp", "preferred_username", "email_verified", "lh_roles", "scope")}
print("Claim dell'access token:")
print(json.dumps(shown, indent=2, ensure_ascii=False))
# Un membro registrato riceve il ruolo predefinito del realm, il cui composito e' MEMBER: nel claim, Keycloak espande
# il composito e aggiunge i propri ruoli tecnici. Oltre a questi tre e a MEMBER non deve comparire nient'altro
# (operatori, SOURCE, MFA_REQUIRED_ROLE): un token con un ruolo in piu' non e' piu' un token di solo membro (Q-554).
KEYCLOAK_TECHNICAL = {"default-roles-loyaltyhub", "offline_access", "uma_authorization"}
errors = []
if "hub" not in aud_list:
    errors.append(f"aud non contiene 'hub': {aud!r}")
if claims.get("preferred_username") != sys.argv[2]:
    errors.append(f"preferred_username atteso {sys.argv[2]!r}, trovato {claims.get('preferred_username')!r}")
if claims.get("email_verified") is not False:
    errors.append(f"email_verified atteso false, trovato {claims.get('email_verified')!r}: senza SMTP l'e-mail non e' mai verificata e non e' una garanzia (Q-557)")
roles = claims.get("lh_roles")
if not isinstance(roles, list):
    errors.append(f"lh_roles assente o non e' una lista: {roles!r}")
else:
    if "MEMBER" not in roles:
        errors.append(f"lh_roles non contiene MEMBER: {roles!r} (il ruolo predefinito del realm non lo porta)")
    extra = sorted(set(roles) - KEYCLOAK_TECHNICAL - {"MEMBER"})
    if extra:
        errors.append(f"lh_roles contiene ruoli oltre a MEMBER e a quelli tecnici di Keycloak: {extra} (in totale {roles!r})")
if errors:
    for e in errors:
        print("ERRORE:", e, file=sys.stderr)
    sys.exit(1)
print("OK: aud contiene 'hub', lh_roles = MEMBER (piu' i soli ruoli tecnici di Keycloak), email_verified = false.")
PY
fi

# Token di amministrazione (password da file, non sulla riga di comando). Quello del realm master dura poco (60 s di
# default): si richiede di nuovo a ogni giro invece di riusarlo.
admin_token() {
  printf 'client_id=admin-cli&grant_type=password&username=%s&password=' "$ADMIN_USER" > "$WORK/admin-login"
  printf '%s' "$ADMIN_PASSWORD" | python3 -c 'import sys,urllib.parse;sys.stdout.write(urllib.parse.quote(sys.stdin.read(),safe=""))' >> "$WORK/admin-login"
  curl -sS -f -o "$WORK/admin-token.json" -X POST "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' --data-binary "@$WORK/admin-login" || return 1
  python3 -c 'import sys,json;print(json.load(open(sys.argv[1]))["access_token"])' "$WORK/admin-token.json"
}

# Ripristina gli attributi originali dei client in $WORK/restore.list (righe: id, file del corpo, clientId). Chi resta
# senza ripristino rimane nell'elenco e viene segnalato; ritorna 1 se anche uno solo non e' stato ripristinato.
restore_pending() {
  local token id file name failed=0
  : > "$WORK/restore.next"
  if ! token="$(admin_token)"; then
    echo "ATTENZIONE: token di amministrazione non ottenuto, client src-* NON ripristinati: $(cut -d' ' -f3 "$WORK/restore.list" | tr '\n' ' ')" >&2
    echo "  Ripristino a mano: per ogni client in Keys riattivare 'Use JWKS URL' e cancellare 'jwks.string' e 'use.jwks.string' (chiave di prova usa-e-getta)." >&2
    return 1
  fi
  while read -r id file name <&3; do
    if curl -sS -f -o /dev/null -X PUT "${ADMIN}/clients/${id}" -H "Authorization: Bearer ${token}" \
      -H 'Content-Type: application/json' --data-binary "@${file}"; then
      echo "  client ${name}: attributi delle chiavi ripristinati"
    else
      echo "ATTENZIONE: ripristino del client ${name} non riuscito: si fida ancora della chiave di prova usa-e-getta." >&2
      echo "  Ripristino a mano: in Keys riattivare 'Use JWKS URL' e cancellare 'jwks.string' e 'use.jwks.string'." >&2
      echo "${id} ${file} ${name}" >> "$WORK/restore.next"
      failed=1
    fi
  done 3< "$WORK/restore.list"
  mv "$WORK/restore.next" "$WORK/restore.list"
  return "$failed"
}

if want sources; then
  command -v openssl >/dev/null || { echo "Errore: serve openssl (chiave di prova per il private_key_jwt)." >&2; exit 1; }
  ADMIN_USER="${KC_BOOTSTRAP_ADMIN_USERNAME:-${LH_IDP_ADMIN_USERNAME:-admin}}"
  ADMIN_PASSWORD="${KC_BOOTSTRAP_ADMIN_PASSWORD:-${LH_IDP_ADMIN_PASSWORD:-}}"
  if [ -z "$ADMIN_PASSWORD" ]; then
    echo "Errore: per il controllo 'sources' impostare KC_BOOTSTRAP_ADMIN_PASSWORD (o LH_IDP_ADMIN_PASSWORD)." >&2
    exit 1
  fi
  ADMIN="${KEYCLOAK_URL}/admin/realms/${REALM}"

  echo
  echo "== Fonti di ingestion: token client_credentials di ogni client src-* =="
  ADMIN_TOKEN="$(admin_token)" || {
    echo "Errore: impossibile ottenere il token di amministrazione da ${KEYCLOAK_URL}." >&2
    exit 1
  }

  # Solo sul realm di prova: la chiave usa-e-getta non va mai registrata sui client di un realm che serve fonti vere.
  curl -sS -f -G "${ADMIN}/clients" -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    --data-urlencode "clientId=${CLIENT_ID}" > "$WORK/probe.json"
  python3 -c 'import sys,json;sys.exit(0 if json.load(open(sys.argv[1])) else 1)' "$WORK/probe.json" || {
    echo "Errore: nel realm ${REALM} manca il client di prova ${CLIENT_ID}: non e' il realm di prova (o l'overlay non e' stato applicato)." >&2
    echo "  Non registro chiavi di prova sui client src-* di un realm che potrebbe servire fonti vere: applicare prima apply-overlay.sh." >&2
    exit 1
  }

  # Elenco dei client src-* del realm (compresi quelli creati dopo l'installazione, Q-494): l'elenco si legge dal
  # realm e non dal repository, cosi' nessuna fonte resta senza controllo.
  curl -sS -f -G "${ADMIN}/clients" -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    --data-urlencode "clientId=src-" --data-urlencode "search=true" --data-urlencode "max=1000" > "$WORK/clients.json"
  python3 - "$WORK/clients.json" "$WORK/src.list" <<'PY'
import json, sys
clients = sorted((c for c in json.load(open(sys.argv[1])) if c["clientId"].startswith("src-")), key=lambda c: c["clientId"])
errors = []
if not clients:
    errors.append("nessun client src-* nel realm: senza fonti configurate il controllo non verificherebbe nulla (realm anteriore a M8.2f? vedi deploy/idp/README.md)")
for c in clients:
    if not c.get("serviceAccountsEnabled"):
        errors.append(f"{c['clientId']}: service account non attivo, nessun token client_credentials e' possibile")
    elif not c.get("enabled", True):
        print(f"  {c['clientId']}: client disabilitato, nessun token da verificare (saltato)")
if errors:
    for e in errors:
        print("ERRORE:", e, file=sys.stderr)
    sys.exit(1)
with open(sys.argv[2], "w") as f:
    for c in clients:
        if c.get("enabled", True):
            f.write(f"{c['id']} {c['clientId']}\n")
PY
  if [ ! -s "$WORK/src.list" ]; then
    echo "ERRORE: tutti i client src-* sono disabilitati: nessun token da verificare." >&2
    exit 1
  fi

  # Chiave usa-e-getta e relativo JWKS (solo la parte pubblica, RS256).
  openssl genrsa -out "$WORK/src-test-key.pem" 2048 2>/dev/null
  python3 - "$WORK/src-test-key.pem" "$WORK/src-jwks.json" "$WORK/src-kid" <<'PY'
import base64, json, secrets, subprocess, sys
key, out, kid_out = sys.argv[1:4]
modulus = subprocess.run(["openssl", "rsa", "-in", key, "-noout", "-modulus"], capture_output=True, check=True).stdout.decode().strip().split("=", 1)[1]
b64 = lambda b: base64.urlsafe_b64encode(b).rstrip(b"=").decode()
kid = "lh-verify-" + secrets.token_hex(6)
json.dump({"keys": [{"kty": "RSA", "use": "sig", "alg": "RS256", "kid": kid, "n": b64(bytes.fromhex(modulus)), "e": "AQAB"}]}, open(out, "w"))
open(kid_out, "w").write(kid)
PY
  KID="$(cat "$WORK/src-kid")"

  # L'audience dell'asserzione e' l'issuer del realm cosi' come lo dichiara Keycloak (puo' differire da KEYCLOAK_URL).
  ISSUER="$(curl -sS -f "${KEYCLOAK_URL}/realms/${REALM}/.well-known/openid-configuration" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["issuer"])')"

  SOURCES_CHECKED=0
  SOURCES_FAILED=0
  # Il file dell'elenco sta sul descrittore 3 cosi' nessun comando del giro legge per sbaglio dallo stdin del ciclo.
  while read -r CID SRC <&3; do
    ADMIN_TOKEN="$(admin_token)"
    # 1. Corpi della modifica e del ripristino. Il ripristino e' in elenco PRIMA della modifica, cosi' anche un errore a
    #    meta' lascia il client ripristinabile. Valore vuoto = attributo rimosso (era assente).
    curl -sS -f "${ADMIN}/clients/${CID}" -H "Authorization: Bearer ${ADMIN_TOKEN}" > "$WORK/orig-${SRC}.json"
    python3 - "$WORK/orig-${SRC}.json" "$WORK/src-jwks.json" "$WORK/restore-${SRC}.json" "$WORK/register-${SRC}.json" <<'PY'
import json, sys
orig, jwks, restore_out, register_out = sys.argv[1:5]
attrs = json.load(open(orig)).get("attributes") or {}
keys = ("use.jwks.url", "use.jwks.string", "jwks.string")
json.dump({"attributes": {k: attrs.get(k, "") for k in keys}}, open(restore_out, "w"))
json.dump({"attributes": {"use.jwks.url": "false", "use.jwks.string": "true", "jwks.string": open(jwks).read()}}, open(register_out, "w"))
PY
    echo "${CID} ${WORK}/restore-${SRC}.json ${SRC}" >> "$WORK/restore.list"
    # 2. Registrazione della chiave di prova (PUT parziale: solo gli attributi indicati).
    curl -sS -f -o /dev/null -X PUT "${ADMIN}/clients/${CID}" -H "Authorization: Bearer ${ADMIN_TOKEN}" \
      -H 'Content-Type: application/json' --data-binary "@$WORK/register-${SRC}.json"
    # 3. Asserzione firmata (valida 60 s, jti unico) e richiesta del token, in un file e non sulla riga di comando.
    python3 - "$WORK/src-test-key.pem" "$KID" "$SRC" "$ISSUER" "$WORK/src-form" <<'PY'
import base64, json, subprocess, sys, time, urllib.parse, uuid
key, kid, client, issuer, out = sys.argv[1:6]
b64 = lambda b: base64.urlsafe_b64encode(b).rstrip(b"=").decode()
now = int(time.time())
head = {"alg": "RS256", "typ": "JWT", "kid": kid}
body = {"iss": client, "sub": client, "aud": issuer, "jti": str(uuid.uuid4()), "iat": now, "exp": now + 60}
signing_input = b64(json.dumps(head, separators=(",", ":")).encode()) + "." + b64(json.dumps(body, separators=(",", ":")).encode())
sig = subprocess.run(["openssl", "dgst", "-sha256", "-sign", key], input=signing_input.encode(), capture_output=True, check=True).stdout
open(out, "w").write(urllib.parse.urlencode({
    "grant_type": "client_credentials",
    "client_id": client,
    "client_assertion_type": "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
    "client_assertion": signing_input + "." + b64(sig),
}))
PY
    HTTP_CODE="$(curl -sS -o "$WORK/src-token-${SRC}.json" -w '%{http_code}' -X POST \
      "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token" \
      -H 'Content-Type: application/x-www-form-urlencoded' --data-binary "@$WORK/src-form" || true)"
    # 4. Ripristino subito, prima di guardare il risultato.
    restore_pending || {
      echo "Errore: ripristino del client ${SRC} non riuscito: mi fermo, nessun'altra chiave di prova viene registrata." >&2
      exit 1
    }
    SOURCES_CHECKED=$((SOURCES_CHECKED + 1))
    if [ "$HTTP_CODE" != "200" ]; then
      echo "ERRORE: ${SRC}: il token endpoint ha risposto ${HTTP_CODE}: $(python3 -c 'import sys,json;d=json.load(open(sys.argv[1]));print(d.get("error"),"-",d.get("error_description"))' "$WORK/src-token-${SRC}.json" 2>/dev/null || echo 'risposta non JSON')" >&2
      SOURCES_FAILED=$((SOURCES_FAILED + 1))
      continue
    fi
    # 5. Asserzioni sul token: lh_roles = [SOURCE] e nient'altro (mai MEMBER, mai ruoli operatore).
    python3 - "$WORK/src-token-${SRC}.json" "$SRC" <<'PY' || SOURCES_FAILED=$((SOURCES_FAILED + 1))
import base64, json, sys
tok = json.load(open(sys.argv[1]))["access_token"]
payload = tok.split(".")[1]
claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
client = sys.argv[2]
aud = claims.get("aud")
aud_list = aud if isinstance(aud, list) else [aud] if aud else []
roles = claims.get("lh_roles")
errors = []
if "hub" not in aud_list:
    errors.append(f"aud non contiene 'hub': {aud!r}")
if claims.get("azp") != client:
    errors.append(f"azp atteso {client!r}, trovato {claims.get('azp')!r}")
if not isinstance(roles, list):
    errors.append(f"lh_roles assente o non e' una lista: {roles!r}")
elif sorted(roles) != ["SOURCE"]:
    errors.append(f"lh_roles atteso ['SOURCE'], trovato {roles!r}")
    if "MEMBER" in roles:
        errors.append("MEMBER sul token di una fonte: il token varrebbe come token di solo membro e non come token di fonte, l'ingestion risponderebbe 403 (Q-557: default-roles-loyaltyhub assegnato all'utenza di servizio?)")
if errors:
    for e in errors:
        print(f"ERRORE: {client}: {e}", file=sys.stderr)
    sys.exit(1)
print(f"  {client}: lh_roles = {roles!r}, aud contiene 'hub': OK")
PY
  done 3< "$WORK/src.list"

  if [ "$SOURCES_FAILED" -gt 0 ]; then
    echo "ERRORE: controllo 'sources' fallito su ${SOURCES_FAILED} verifica/e su ${SOURCES_CHECKED} client src-*." >&2
    exit 1
  fi
  echo "OK: ${SOURCES_CHECKED} client src-*, ognuno con un token client_credentials con lh_roles = [SOURCE] soltanto."
fi

if want ldap; then
  cat <<EOF

== Broker verso l'IdP aziendale di prova (passo manuale, interattivo) ==
Il broker OIDC richiede un login nel browser: non si verifica in modo non interattivo.
1. Avviare anche l'IdP di prova: docker compose -f deploy/docker-compose.yml --profile idp --profile idp-test up -d
2. Aprire ${KEYCLOAK_URL}/realms/${REALM}/account/ e scegliere "IdP Aziendale".
3. Autenticarsi nel realm idp-test con l'utente di prova di test-realm.json.
4. Verificare che l'utente compaia nel realm ${REALM} collegato al provider test-idp.
F2-IAM-04 resta da spuntare finche' questo passo non e' stato eseguito e registrato.
EOF
fi
