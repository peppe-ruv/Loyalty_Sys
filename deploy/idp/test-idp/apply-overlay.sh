#!/usr/bin/env bash
# SOLO PROVA (F2-IAM-04, ADR-027). Applica realm-test-overlay.json al realm `loyaltyhub` gia' avviato:
#   - broker `test-idp` e client di prova `lh-ldap-test`: POST /admin/realms/loyaltyhub/partialImport
#     con ifResourceExists=OVERWRITE;
#   - federazione LDAP (components): API /components, perche' partialImport non gestisce i componenti.
#     Il provider `ldap` esistente viene rimosso e ricreato (stessa semantica di OVERWRITE).
#
# Perche' uno script: `--import-realm` legge solo i file al primo livello della cartella di import e salta
# un realm gia' esistente, quindi l'overlay non verrebbe mai applicato. Le API admin NON sostituiscono i
# segnaposto ${VAR}: lo fa questo script, solo per le variabili elencate in OVERLAY_VARS, e fallisce se
# una manca. I valori non vengono mai stampati.
#
# Variabili richieste: KC_BOOTSTRAP_ADMIN_PASSWORD (o LH_IDP_ADMIN_PASSWORD) e quelle in OVERLAY_VARS.
# Opzionali: KEYCLOAK_URL (default http://localhost:8080), KC_BOOTSTRAP_ADMIN_USERNAME (default admin).
set -euo pipefail

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8080}"
REALM="loyaltyhub"
ADMIN_USER="${KC_BOOTSTRAP_ADMIN_USERNAME:-${LH_IDP_ADMIN_USERNAME:-admin}}"
ADMIN_PASSWORD="${KC_BOOTSTRAP_ADMIN_PASSWORD:-${LH_IDP_ADMIN_PASSWORD:-}}"
OVERLAY="${OVERLAY:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/realm-test-overlay.json}"

OVERLAY_VARS=(
  LH_TEST_IDP_AUTH_URL
  LH_TEST_IDP_TOKEN_URL
  LH_TEST_IDP_USERINFO_URL
  LH_TEST_IDP_ISSUER
  LH_TEST_IDP_SECRET
  LH_LDAP_BIND_CREDENTIAL
  LH_LDAP_TEST_CLIENT_SECRET
)

if [ -z "${ADMIN_PASSWORD}" ]; then
  echo "Errore: impostare KC_BOOTSTRAP_ADMIN_PASSWORD (o LH_IDP_ADMIN_PASSWORD)." >&2
  exit 1
fi
command -v python3 >/dev/null || { echo "Errore: serve python3." >&2; exit 1; }
command -v curl >/dev/null || { echo "Errore: serve curl." >&2; exit 1; }

missing=()
for v in "${OVERLAY_VARS[@]}"; do
  [ -n "${!v:-}" ] || missing+=("$v")
done
if [ "${#missing[@]}" -gt 0 ]; then
  echo "Errore: variabili mancanti per l'overlay: ${missing[*]}" >&2
  exit 1
fi

WORK="$(mktemp -d)"
chmod 700 "$WORK"
trap 'rm -rf "$WORK"' EXIT

# 1. Sostituzione dei segnaposto (solo OVERLAY_VARS) sui valori JSON gia' interpretati, cosi' un valore
#    con virgolette o barre non rompe il documento. Qualunque ${LH_...} residuo e' un errore.
OVERLAY_VARS_CSV="$(IFS=,; echo "${OVERLAY_VARS[*]}")" python3 - "$OVERLAY" "$WORK" <<'PY'
import json, os, re, sys
src, work = sys.argv[1], sys.argv[2]
allowed = set(os.environ["OVERLAY_VARS_CSV"].split(","))
pat = re.compile(r"\$\{([A-Z0-9_]+)\}")
def sub(v):
    if isinstance(v, str):
        def rep(m):
            name = m.group(1)
            if name not in allowed:
                sys.exit(f"Errore: segnaposto ${{{name}}} non ammesso nell'overlay")
            return os.environ[name]
        return pat.sub(rep, v)
    if isinstance(v, list):
        return [sub(x) for x in v]
    if isinstance(v, dict):
        return {k: sub(x) for k, x in v.items()}
    return v
doc = json.load(open(src))
doc.pop("_comment", None)
doc = sub(doc)
partial = {"ifResourceExists": "OVERWRITE",
           "identityProviders": doc.get("identityProviders", []),
           "clients": doc.get("clients", [])}
json.dump(partial, open(os.path.join(work, "partial.json"), "w"))
json.dump(doc.get("components", {}), open(os.path.join(work, "components.json"), "w"))
PY
chmod 600 "$WORK"/*.json

# 2. Token di amministrazione (password passata da file, non sulla riga di comando).
printf 'client_id=admin-cli&grant_type=password&username=%s&password=' "$ADMIN_USER" > "$WORK/login"
printf '%s' "$ADMIN_PASSWORD" | python3 -c 'import sys,urllib.parse;sys.stdout.write(urllib.parse.quote(sys.stdin.read(),safe=""))' >> "$WORK/login"
TOKEN="$(curl -sS -f -X POST "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' --data-binary "@$WORK/login" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')" || {
  echo "Errore: impossibile ottenere il token di amministrazione da ${KEYCLOAK_URL}." >&2
  exit 1
}
AUTH=(-H "Authorization: Bearer ${TOKEN}")
ADMIN="${KEYCLOAK_URL}/admin/realms/${REALM}"

# 3. Broker e client di prova.
echo "partialImport (OVERWRITE) di identityProviders e clients..."
HTTP_CODE="$(curl -sS -o "$WORK/partial-result.json" -w '%{http_code}' -X POST "${ADMIN}/partialImport" \
  "${AUTH[@]}" -H 'Content-Type: application/json' --data-binary "@$WORK/partial.json")"
if [ "$HTTP_CODE" != "200" ]; then
  echo "Errore: partialImport ha risposto ${HTTP_CODE}: $(head -c 500 "$WORK/partial-result.json")" >&2
  echo "Suggerimento: con sslRequired=external gli URL del broker verso host non locali devono essere https." >&2
  exit 1
fi
python3 -c 'import sys,json;r=json.load(sys.stdin);print("  aggiunti=%s sovrascritti=%s saltati=%s"%(r.get("added"),r.get("overwritten"),r.get("skipped")));[print("  -",x["resourceType"],x["resourceName"],x["action"]) for x in r.get("results",[])]' < "$WORK/partial-result.json"

# 4. Federazione LDAP (e relativi mapper) via API components.
REALM_ID="$(curl -sS -f "${ADMIN}" "${AUTH[@]}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')"
python3 - "$WORK/components.json" "$REALM_ID" "$WORK" <<'PY'
import json, os, sys
comps, realm_id, work = json.load(open(sys.argv[1])), sys.argv[2], sys.argv[3]
plan = []
for ptype, items in comps.items():
    for c in items:
        subs = c.pop("subComponents", {}) or {}
        c.pop("id", None)
        c.update({"providerType": ptype, "parentId": realm_id})
        plan.append({"component": c, "subs": [dict(s, providerType=st) for st, ss in subs.items() for s in ss]})
json.dump(plan, open(os.path.join(work, "plan.json"), "w"))
PY
chmod 600 "$WORK/plan.json"
COUNT="$(python3 -c 'import sys,json;print(len(json.load(open(sys.argv[1]))))' "$WORK/plan.json")"
for i in $(seq 0 $((COUNT - 1))); do
  python3 -c 'import sys,json;json.dump(json.load(open(sys.argv[1]))[int(sys.argv[2])]["component"],sys.stdout)' \
    "$WORK/plan.json" "$i" > "$WORK/comp.json"
  NAME="$(python3 -c 'import sys,json;print(json.load(open(sys.argv[1]))["name"])' "$WORK/comp.json")"
  PTYPE="$(python3 -c 'import sys,json;print(json.load(open(sys.argv[1]))["providerType"])' "$WORK/comp.json")"
  # OVERWRITE: rimuove il componente omonimo (e i suoi mapper) prima di ricrearlo.
  OLD_IDS="$(curl -sS -f -G "${ADMIN}/components" "${AUTH[@]}" --data-urlencode "parent=${REALM_ID}" \
      --data-urlencode "type=${PTYPE}" --data-urlencode "name=${NAME}" \
      | python3 -c 'import sys,json;[print(c["id"]) for c in json.load(sys.stdin)]')"
  for old in $OLD_IDS; do
    curl -sS -f -X DELETE "${ADMIN}/components/${old}" "${AUTH[@]}"
    echo "  componente ${NAME} esistente rimosso"
  done
  LOCATION="$(curl -sS -f -X POST "${ADMIN}/components" "${AUTH[@]}" -H 'Content-Type: application/json' \
    --data-binary "@$WORK/comp.json" -D - -o /dev/null | tr -d '\r' | awk 'tolower($1)=="location:"{print $2}')"
  PARENT_ID="${LOCATION##*/}"
  [ -n "$PARENT_ID" ] || { echo "Errore: creazione del componente ${NAME} fallita." >&2; exit 1; }
  echo "  componente ${NAME} (${PTYPE##*.}) creato"
  # Mapper: Keycloak crea i mapper predefiniti del provider; quelli dell'overlay li aggiornano o si aggiungono.
  curl -sS -f -G "${ADMIN}/components" "${AUTH[@]}" --data-urlencode "parent=${PARENT_ID}" > "$WORK/existing.json"
  python3 - "$WORK/plan.json" "$i" "$PARENT_ID" "$WORK/existing.json" "$WORK" <<'PY'
import json, os, sys
plan = json.load(open(sys.argv[1]))[int(sys.argv[2])]
parent, existing, work = sys.argv[3], json.load(open(sys.argv[4])), sys.argv[5]
by_name = {e["name"]: e for e in existing}
ops = []
for s in plan["subs"]:
    s = dict(s, parentId=parent)
    s.pop("id", None)
    if s["name"] in by_name:
        s["id"] = by_name[s["name"]]["id"]
        ops.append(["PUT", s["id"], s])
    else:
        ops.append(["POST", "", s])
for n, (method, cid, body) in enumerate(ops):
    json.dump(body, open(os.path.join(work, f"sub{n}.json"), "w"))
open(os.path.join(work, "subops"), "w").write("".join(f"{m} {c or '-'} sub{n}.json {b['name']}\n" for n, (m, c, b) in enumerate(ops)))
PY
  while read -r METHOD CID FILE SUBNAME; do
    if [ "$METHOD" = "PUT" ]; then
      curl -sS -f -X PUT "${ADMIN}/components/${CID}" "${AUTH[@]}" -H 'Content-Type: application/json' --data-binary "@$WORK/${FILE}"
    else
      curl -sS -f -X POST "${ADMIN}/components" "${AUTH[@]}" -H 'Content-Type: application/json' --data-binary "@$WORK/${FILE}" -o /dev/null
    fi
    echo "  mapper ${SUBNAME}: ${METHOD}"
  done < "$WORK/subops"
done

echo "Overlay di prova applicato al realm ${REALM}."
