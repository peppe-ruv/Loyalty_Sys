#!/usr/bin/env bash
# VETRINA ENTERPRISE (F2-IAM-01, F2-IAM-03, ADR-049; Q-619 e Q-626, default proposti, APERTE).
# Applica realm-vetrina-overlay.json al realm `loyaltyhub` gia' avviato, con lo stesso meccanismo di
# test-idp/apply-overlay.sh ma senza segnaposto ne' credenziali:
#   - impostazioni del realm (oggi solo `registrationAllowed: false`): PUT /admin/realms/loyaltyhub con il
#     solo frammento dell'overlay (il partialImport non tocca le impostazioni del realm);
#   - client (oggi `lh-cli`, Device Authorization Grant per la CLI dell'operatore): POST
#     /admin/realms/loyaltyhub/partialImport con ifResourceExists=OVERWRITE.
# `--import-realm` salta un realm gia' esistente, quindi l'overlay non si applicherebbe da solo. Lo script e'
# idempotente, non crea utenti, non tocca flussi di autenticazione, azioni richieste (UPDATE_PASSWORD) e MFA,
# e non stampa mai credenziali. Alla fine rilegge il realm e FALLISCE se il risultato non e' quello atteso.
#
# Uso:
#   KC_BOOTSTRAP_ADMIN_PASSWORD=... ./apply-overlay.sh     applica (o LH_IDP_ADMIN_PASSWORD)
#   ./apply-overlay.sh --check                              solo validazione dell'overlay, nessuna rete
# Opzionali: KEYCLOAK_URL (default http://localhost:8080), KC_BOOTSTRAP_ADMIN_USERNAME (default admin).
set -euo pipefail

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8080}"
REALM="loyaltyhub"
ADMIN_USER="${KC_BOOTSTRAP_ADMIN_USERNAME:-${LH_IDP_ADMIN_USERNAME:-admin}}"
ADMIN_PASSWORD="${KC_BOOTSTRAP_ADMIN_PASSWORD:-${LH_IDP_ADMIN_PASSWORD:-}}"
OVERLAY="${OVERLAY:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/realm-vetrina-overlay.json}"

# Impostazioni del realm che l'overlay puo' contenere (allowlist: una chiave fuori elenco e' un errore).
# check-realm.mjs verifica che coincida con le chiavi dell'overlay.
REALM_SETTINGS=(
  registrationAllowed
)

MODE="apply"
case "${1:-}" in
  "") ;;
  --check) MODE="check" ;;
  *) echo "Uso: $0 [--check]" >&2; exit 2 ;;
esac

command -v python3 >/dev/null || { echo "Errore: serve python3." >&2; exit 1; }
if [ "$MODE" = "apply" ]; then
  command -v curl >/dev/null || { echo "Errore: serve curl." >&2; exit 1; }
  if [ -z "${ADMIN_PASSWORD}" ]; then
    echo "Errore: impostare KC_BOOTSTRAP_ADMIN_PASSWORD (o LH_IDP_ADMIN_PASSWORD)." >&2
    exit 1
  fi
fi

WORK="$(mktemp -d)"
chmod 700 "$WORK"
trap 'rm -rf "$WORK"' EXIT

# 1. Validazione e scomposizione dell'overlay. Nessun segnaposto ammesso (l'overlay non ha segreti da sostituire),
#    nessun utente, flusso o azione richiesta, e il client lh-cli deve essere pubblico e solo device grant.
REALM_SETTINGS_CSV="$(IFS=,; echo "${REALM_SETTINGS[*]}")" python3 - "$OVERLAY" "$WORK" <<'PY'
import json, os, sys
src, work = sys.argv[1], sys.argv[2]
settings = set(os.environ["REALM_SETTINGS_CSV"].split(","))
raw = open(src).read()
if "${" in raw:
    sys.exit("Errore: l'overlay di vetrina non ammette segnaposto ${...} (nessun segreto da sostituire)")
doc = json.loads(raw)
doc.pop("_comment", None)
if doc.pop("realm", None) != "loyaltyhub":
    sys.exit("Errore: l'overlay deve riguardare il realm loyaltyhub")
clients = doc.pop("clients", [])
unknown = sorted(set(doc) - settings)
if unknown:
    sys.exit("Errore: chiavi non ammesse nell'overlay di vetrina: " + ", ".join(unknown))
if doc.get("registrationAllowed") is not False:
    sys.exit("Errore: registrationAllowed deve essere false nell'overlay di vetrina (Q-619)")
for c in clients:
    cid = c.get("clientId")
    if cid != "lh-cli":
        sys.exit(f"Errore: client non ammesso nell'overlay di vetrina: {cid}")
    a = c.get("attributes", {})
    if not (c.get("publicClient") is True and a.get("oauth2.device.authorization.grant.enabled") == "true"
            and not any(c.get(k) for k in ("directAccessGrantsEnabled", "standardFlowEnabled", "implicitFlowEnabled", "serviceAccountsEnabled"))
            and "secret" not in c and not c.get("redirectUris")):
        sys.exit("Errore: lh-cli deve essere pubblico, solo device grant, senza segreto ne' redirect URI")
json.dump(doc, open(os.path.join(work, "realm-settings.json"), "w"))
json.dump({"ifResourceExists": "OVERWRITE", "clients": clients}, open(os.path.join(work, "partial.json"), "w"))
print("  overlay valido: impostazioni=%s client=%s" % (",".join(sorted(doc)) or "-", ",".join(c["clientId"] for c in clients) or "-"))
PY
chmod 600 "$WORK"/*.json
if [ "$MODE" = "check" ]; then
  echo "Overlay di vetrina valido (nessuna modifica applicata)."
  exit 0
fi

# 2. Token di amministrazione (password passata da file, non sulla riga di comando).
printf 'client_id=admin-cli&grant_type=password&username=%s&password=' "$ADMIN_USER" > "$WORK/login"
printf '%s' "$ADMIN_PASSWORD" | python3 -c 'import sys,urllib.parse;sys.stdout.write(urllib.parse.quote(sys.stdin.read(),safe=""))' >> "$WORK/login"
chmod 600 "$WORK/login"
TOKEN="$(curl -sS -f -X POST "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' --data-binary "@$WORK/login" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')" || {
  echo "Errore: impossibile ottenere il token di amministrazione da ${KEYCLOAK_URL}." >&2
  exit 1
}
# Il token sta in un file 0600 e si passa con -H @file: non compare negli argomenti dei processi (curl >= 7.55).
printf 'Authorization: Bearer %s\n' "$TOKEN" > "$WORK/auth"
chmod 600 "$WORK/auth"
unset TOKEN ADMIN_PASSWORD
ADMIN="${KEYCLOAK_URL}/admin/realms/${REALM}"

# 3. Impostazioni del realm: PUT parziale (Keycloak aggiorna solo i campi presenti nel frammento).
echo "Impostazioni del realm (registrazione chiusa, Q-619)..."
HTTP_CODE="$(curl -sS -o "$WORK/put-result.json" -w '%{http_code}' -X PUT "${ADMIN}" \
  -H "@$WORK/auth" -H 'Content-Type: application/json' --data-binary "@$WORK/realm-settings.json")"
if [ "$HTTP_CODE" != "204" ] && [ "$HTTP_CODE" != "200" ]; then
  echo "Errore: l'aggiornamento del realm ha risposto ${HTTP_CODE}: $(head -c 300 "$WORK/put-result.json")" >&2
  exit 1
fi

# 4. Client (lh-cli): partialImport con OVERWRITE.
echo "partialImport (OVERWRITE) dei client..."
HTTP_CODE="$(curl -sS -o "$WORK/partial-result.json" -w '%{http_code}' -X POST "${ADMIN}/partialImport" \
  -H "@$WORK/auth" -H 'Content-Type: application/json' --data-binary "@$WORK/partial.json")"
if [ "$HTTP_CODE" != "200" ]; then
  echo "Errore: partialImport ha risposto ${HTTP_CODE}: $(head -c 300 "$WORK/partial-result.json")" >&2
  exit 1
fi
python3 -c 'import sys,json;r=json.load(sys.stdin);print("  aggiunti=%s sovrascritti=%s saltati=%s"%(r.get("added"),r.get("overwritten"),r.get("skipped")));[print("  -",x["resourceType"],x["resourceName"],x["action"]) for x in r.get("results",[])]' < "$WORK/partial-result.json"

# 5. Verifica: rilegge il realm e il client; qualunque scostamento e' un errore.
curl -sS -f "${ADMIN}" -H "@$WORK/auth" > "$WORK/realm-now.json"
curl -sS -f -G "${ADMIN}/clients" -H "@$WORK/auth" --data-urlencode "clientId=lh-cli" > "$WORK/client-now.json"
python3 - "$WORK/realm-now.json" "$WORK/client-now.json" <<'PY'
import json, sys
realm, clients = json.load(open(sys.argv[1])), json.load(open(sys.argv[2]))
errors = []
if realm.get("registrationAllowed") is not False:
    errors.append("registrationAllowed non e' false")
if realm.get("browserFlow") != "browser-mfa":
    errors.append("browserFlow non e' browser-mfa (MFA degli operatori)")
if len(clients) != 1:
    errors.append("client lh-cli assente")
else:
    c = clients[0]
    if c.get("publicClient") is not True: errors.append("lh-cli non e' pubblico")
    if c.get("attributes", {}).get("oauth2.device.authorization.grant.enabled") != "true": errors.append("lh-cli senza device grant")
    for k in ("directAccessGrantsEnabled", "standardFlowEnabled", "implicitFlowEnabled", "serviceAccountsEnabled"):
        if c.get(k): errors.append(f"lh-cli: {k} attivo")
    if c.get("redirectUris"): errors.append("lh-cli: redirect URI presenti")
if errors:
    sys.exit("Errore: verifica dell'overlay fallita: " + "; ".join(errors))
print("  verifica: registrationAllowed=false, browserFlow=browser-mfa, lh-cli pubblico con solo device grant")
PY

echo "Overlay di vetrina applicato al realm ${REALM}."
