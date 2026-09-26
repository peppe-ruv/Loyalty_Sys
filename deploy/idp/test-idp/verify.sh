#!/usr/bin/env bash
# SOLO PROVA (F2-IAM-04, ADR-027). Verifica non interattiva della federazione LDAP e istruzioni per il broker.
#
# Prerequisiti: realm `loyaltyhub` avviato e overlay applicato con apply-overlay.sh (che crea il client di
# prova `lh-ldap-test`). Il client di produzione `web` ha i direct access grants disattivati di proposito:
# qui si usa solo `lh-ldap-test`.
#
# Variabili: LH_LDAP_TEST_CLIENT_SECRET (richiesta), KEYCLOAK_URL (default http://localhost:8080),
#            LH_LDAP_TEST_USERNAME / LH_LDAP_TEST_PASSWORD (default: utente del seed ldap-seed.ldif).
set -euo pipefail

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8080}"
REALM="loyaltyhub"
CLIENT_ID="lh-ldap-test"
CLIENT_SECRET="${LH_LDAP_TEST_CLIENT_SECRET:?LH_LDAP_TEST_CLIENT_SECRET deve essere impostata (vedi apply-overlay.sh)}"
TEST_USER="${LH_LDAP_TEST_USERNAME:-testuser}"
# Password del solo utente fittizio di ldap-seed.ldif (dato di prova, non un segreto).
TEST_PASSWORD="${LH_LDAP_TEST_PASSWORD:-testpassword}"

WORK="$(mktemp -d)"
chmod 700 "$WORK"
trap 'rm -rf "$WORK"' EXIT

echo "== Federazione LDAP: grant password per ${TEST_USER} con il client di prova ${CLIENT_ID} =="
# Credenziali in un file (non sulla riga di comando), codificate come form.
CLIENT_SECRET="$CLIENT_SECRET" TEST_USER="$TEST_USER" TEST_PASSWORD="$TEST_PASSWORD" CLIENT_ID="$CLIENT_ID" \
  python3 -c 'import os,urllib.parse;print(urllib.parse.urlencode({"grant_type":"password","client_id":os.environ["CLIENT_ID"],"client_secret":os.environ["CLIENT_SECRET"],"username":os.environ["TEST_USER"],"password":os.environ["TEST_PASSWORD"],"scope":"openid"}),end="")' \
  > "$WORK/form"
HTTP_CODE="$(curl -sS -o "$WORK/token.json" -w '%{http_code}' -X POST \
  "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' --data-binary "@$WORK/form")"
if [ "$HTTP_CODE" != "200" ]; then
  echo "Errore: il token endpoint ha risposto ${HTTP_CODE}: $(python3 -c 'import sys,json;d=json.load(open(sys.argv[1]));print(d.get("error"),"-",d.get("error_description"))' "$WORK/token.json" 2>/dev/null || echo 'risposta non JSON')" >&2
  exit 1
fi

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

cat <<EOF

== Broker verso l'IdP aziendale di prova (passo manuale, interattivo) ==
Il broker OIDC richiede un login nel browser: non si verifica in modo non interattivo.
1. Avviare anche l'IdP di prova: docker compose -f deploy/docker-compose.yml --profile idp --profile idp-test up -d
2. Aprire ${KEYCLOAK_URL}/realms/${REALM}/account/ e scegliere "IdP Aziendale".
3. Autenticarsi nel realm idp-test con l'utente di prova di test-realm.json.
4. Verificare che l'utente compaia nel realm ${REALM} collegato al provider test-idp.
F2-IAM-04 resta da spuntare finche' questo passo non e' stato eseguito e registrato.
EOF
