#!/usr/bin/env bash
# VETRINA ENTERPRISE (F2-IAM-01, F2-IAM-03, ADR-049; Q-618, Q-619 e Q-626, default proposti, APERTE).
# Applica realm-vetrina-overlay.json al realm `loyaltyhub` gia' avviato, con lo stesso meccanismo di
# test-idp/apply-overlay.sh ma senza segnaposto ne' credenziali:
#   - impostazioni del realm (oggi solo `registrationAllowed: false`): lettura del realm, unione con il frammento
#     dell'overlay e PUT /admin/realms/loyaltyhub della rappresentazione completa (come fa `kcadm update`: il
#     partialImport non tocca le impostazioni del realm e un PUT parziale potrebbe far ricalcolare alcune policy);
#   - client (oggi `lh-cli`, Device Authorization Grant per la CLI dell'operatore): POST
#     /admin/realms/loyaltyhub/partialImport con ifResourceExists=OVERWRITE;
#   - scope mapping dei ruoli del client (`scopeMappings` dell'overlay, con fullScopeAllowed=false): il
#     partialImport non li porta, quindi POST /clients/{id}/scope-mappings/realm.
# `--import-realm` salta un realm gia' esistente, quindi l'overlay non si applicherebbe da solo. Lo script e'
# idempotente, non crea utenti, non tocca flussi di autenticazione, azioni richieste (UPDATE_PASSWORD) e MFA,
# e non stampa mai credenziali. Alla fine rilegge il realm e il client e FALLISCE se il risultato non e' quello
# atteso, e verifica che ogni account operatore abbia MFA_REQUIRED_ROLE (Q-618, default proposto, APERTA): e' il
# solo ruolo che fa scattare l'OTP nel flusso browser-mfa, quindi un operatore senza sarebbe un ADMIN senza MFA.
#
# Uso:
#   KC_BOOTSTRAP_ADMIN_PASSWORD=... ./apply-overlay.sh     applica (o LH_IDP_ADMIN_PASSWORD)
#   ./apply-overlay.sh --check                              solo validazione dell'overlay, nessuna rete
#   KC_BOOTSTRAP_ADMIN_PASSWORD=... ./apply-overlay.sh --check-operators
#                                                           solo lettura: fallisce se un account operatore non ha
#                                                           MFA_REQUIRED_ROLE; da rieseguire dopo ogni account creato
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
# Ruoli degli account operatore (docs/08): chi ne ha uno deve avere anche MFA_REQUIRED_ROLE.
OPERATOR_ROLES=(ADMIN MARKETING LEGAL CARE ANALYST)
MFA_ROLE="MFA_REQUIRED_ROLE"

MODE="apply"
case "${1:-}" in
  "") ;;
  --check) MODE="check" ;;
  --check-operators) MODE="operators" ;;
  *) echo "Uso: $0 [--check | --check-operators]" >&2; exit 2 ;;
esac

command -v python3 >/dev/null || { echo "Errore: serve python3." >&2; exit 1; }
if [ "$MODE" != "check" ]; then
  command -v curl >/dev/null || { echo "Errore: serve curl." >&2; exit 1; }
  if [ -z "${ADMIN_PASSWORD}" ]; then
    echo "Errore: impostare KC_BOOTSTRAP_ADMIN_PASSWORD (o LH_IDP_ADMIN_PASSWORD)." >&2
    exit 1
  fi
fi

WORK="$(mktemp -d)"
chmod 700 "$WORK"
trap 'rm -rf "$WORK"' EXIT

# Codifica per application/x-www-form-urlencoded: legge da stdin, scrive su stdout (nome utente e password).
urlenc() { python3 -c 'import sys,urllib.parse;sys.stdout.write(urllib.parse.quote(sys.stdin.read(),safe=""))'; }

# 1. Validazione e scomposizione dell'overlay. Nessun segnaposto ammesso (l'overlay non ha segreti da sostituire),
#    nessun utente, flusso o azione richiesta, e il client lh-cli deve essere pubblico, solo device grant, con
#    consenso obbligatorio e ruoli limitati a quelli operatore.
REALM_SETTINGS_CSV="$(IFS=,; echo "${REALM_SETTINGS[*]}")" OPERATOR_ROLES_CSV="$(IFS=,; echo "${OPERATOR_ROLES[*]}")" \
python3 - "$OVERLAY" "$WORK" <<'PY'
import json, os, sys
src, work = sys.argv[1], sys.argv[2]
settings = set(os.environ["REALM_SETTINGS_CSV"].split(","))
operators = set(os.environ["OPERATOR_ROLES_CSV"].split(","))
raw = open(src).read()
if "${" in raw:
    sys.exit("Errore: l'overlay di vetrina non ammette segnaposto ${...} (nessun segreto da sostituire)")
doc = json.loads(raw)
doc.pop("_comment", None)
if doc.pop("realm", None) != "loyaltyhub":
    sys.exit("Errore: l'overlay deve riguardare il realm loyaltyhub")
clients = doc.pop("clients", [])
scope_mappings = doc.pop("scopeMappings", [])
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
    if c.get("consentRequired") is not True:
        sys.exit("Errore: lh-cli deve avere consentRequired true (l'operatore vede quale client chiede l'accesso, RFC 8628 par. 5.4)")
    if c.get("fullScopeAllowed") is not False:
        sys.exit("Errore: lh-cli deve avere fullScopeAllowed false (ruoli del token limitati a quelli operatore)")
    if c.get("optionalClientScopes"):
        sys.exit("Errore: lh-cli non deve avere scope opzionali (offline_access)")
if clients and not (len(scope_mappings) == 1 and scope_mappings[0].get("client") == "lh-cli"
                    and set(scope_mappings[0].get("roles", [])) == operators and "clientRoles" not in scope_mappings[0]):
    sys.exit("Errore: scopeMappings deve limitare lh-cli ai soli ruoli operatore: " + ", ".join(sorted(operators)))
json.dump(doc, open(os.path.join(work, "realm-settings.json"), "w"))
json.dump({"ifResourceExists": "OVERWRITE", "clients": clients}, open(os.path.join(work, "partial.json"), "w"))
json.dump({"clients": clients, "scopeMappings": scope_mappings}, open(os.path.join(work, "expected.json"), "w"))
print("  overlay valido: impostazioni=%s client=%s" % (",".join(sorted(doc)) or "-", ",".join(c["clientId"] for c in clients) or "-"))
PY
chmod 600 "$WORK"/*.json
if [ "$MODE" = "check" ]; then
  echo "Overlay di vetrina valido (nessuna modifica applicata)."
  exit 0
fi

# 2. Token di amministrazione (nome utente e password codificati e passati da file, non sulla riga di comando).
printf 'client_id=admin-cli&grant_type=password&username=' > "$WORK/login"
printf '%s' "$ADMIN_USER" | urlenc >> "$WORK/login"
printf '&password=' >> "$WORK/login"
printf '%s' "$ADMIN_PASSWORD" | urlenc >> "$WORK/login"
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

# GET dell'API di amministrazione del realm: kc_get <percorso> <file di uscita> [opzioni di curl...]
kc_get() { local path="$1" out="$2"; shift 2; curl -sS -f -G "${ADMIN}${path:+/${path}}" -H "@$WORK/auth" "$@" > "$out"; }

# Nomi utente (uno per riga) con un ruolo del realm, a pagine, senza le utenze di servizio (non fanno il login
# da browser). Si guardano le assegnazioni dirette del ruolo: il README impone di assegnarlo all'utente.
role_users() {
  local role="$1" out="$2" first=0 size=200 n
  : > "$out"
  while :; do
    kc_get "roles/${role}/users" "$WORK/page.json" --data-urlencode "first=${first}" --data-urlencode "max=${size}"
    n="$(python3 -c 'import sys,json
page=json.load(open(sys.argv[1]))
with open(sys.argv[2],"a") as f:
    for u in page:
        if not u.get("serviceAccountClientId"): f.write(u["username"]+"\n")
print(len(page))' "$WORK/page.json" "$out")"
    [ "$n" -lt "$size" ] && break
    first=$((first + size))
  done
}

# Q-618 (default proposto, APERTA): ogni account operatore deve avere MFA_REQUIRED_ROLE, il solo ruolo che fa
# scattare l'OTP (mfa-conditional usa conditional-user-role). Senza, l'account e' un operatore senza MFA, sia
# nel backoffice sia con lh-cli. Fallisce elencando gli account da correggere.
check_operators() {
  local r
  role_users "$MFA_ROLE" "$WORK/mfa-users.txt"
  : > "$WORK/operators.txt"
  for r in "${OPERATOR_ROLES[@]}"; do
    role_users "$r" "$WORK/op-${r}.txt"
    cat "$WORK/op-${r}.txt" >> "$WORK/operators.txt"
  done
  python3 - "$WORK/operators.txt" "$WORK/mfa-users.txt" "$MFA_ROLE" <<'PY'
import sys
ops = {l.strip() for l in open(sys.argv[1]) if l.strip()}
mfa = {l.strip() for l in open(sys.argv[2]) if l.strip()}
missing = sorted(ops - mfa)
if missing:
    sys.exit("Errore: account operatore senza %s (senza, niente OTP): %s. Assegnare il ruolo %s all'utente, "
             "vedi il README (Creare un account operatore nominativo)." % (sys.argv[3], ", ".join(missing), sys.argv[3]))
print("  operatori: %d account, tutti con %s" % (len(ops), sys.argv[3]))
PY
}

if [ "$MODE" = "operators" ]; then
  echo "Verifica della MFA degli account operatore (Q-618, default proposto, APERTA)..."
  check_operators
  exit 0
fi

# 3. Impostazioni del realm: si legge la rappresentazione corrente, si unisce il frammento dell'overlay e si
#    rimanda intera (come kcadm update); le impostazioni non previste dall'overlay devono restare uguali.
echo "Impostazioni del realm (registrazione chiusa, Q-619)..."
kc_get "" "$WORK/realm-before.json"
python3 - "$WORK" <<'PY'
import json, os, sys
w = sys.argv[1]
realm = json.load(open(os.path.join(w, "realm-before.json")))
realm.update(json.load(open(os.path.join(w, "realm-settings.json"))))
json.dump(realm, open(os.path.join(w, "realm-put.json"), "w"))
PY
chmod 600 "$WORK/realm-put.json"
HTTP_CODE="$(curl -sS -o "$WORK/put-result.json" -w '%{http_code}' -X PUT "${ADMIN}" \
  -H "@$WORK/auth" -H 'Content-Type: application/json' --data-binary "@$WORK/realm-put.json")"
if [ "$HTTP_CODE" != "204" ] && [ "$HTTP_CODE" != "200" ]; then
  echo "Errore: l'aggiornamento del realm ha risposto ${HTTP_CODE}: $(head -c 300 "$WORK/put-result.json")" >&2
  exit 1
fi

# 4. Client (lh-cli): partialImport con OVERWRITE, poi gli scope mapping dei ruoli (il client viene ricreato).
echo "partialImport (OVERWRITE) dei client..."
HTTP_CODE="$(curl -sS -o "$WORK/partial-result.json" -w '%{http_code}' -X POST "${ADMIN}/partialImport" \
  -H "@$WORK/auth" -H 'Content-Type: application/json' --data-binary "@$WORK/partial.json")"
if [ "$HTTP_CODE" != "200" ]; then
  echo "Errore: partialImport ha risposto ${HTTP_CODE}: $(head -c 300 "$WORK/partial-result.json")" >&2
  exit 1
fi
python3 -c 'import sys,json;r=json.load(sys.stdin);print("  aggiunti=%s sovrascritti=%s saltati=%s"%(r.get("added"),r.get("overwritten"),r.get("skipped")));[print("  -",x["resourceType"],x["resourceName"],x["action"]) for x in r.get("results",[])]' < "$WORK/partial-result.json"

kc_get "clients" "$WORK/client-now.json" --data-urlencode "clientId=lh-cli"
CLIENT_UUID="$(python3 -c 'import sys,json;c=json.load(open(sys.argv[1]));print(c[0]["id"] if len(c)==1 else "")' "$WORK/client-now.json")"
if [ -z "$CLIENT_UUID" ]; then
  echo "Errore: client lh-cli assente dopo il partialImport." >&2
  exit 1
fi
echo "Scope mapping dei ruoli di lh-cli (solo ruoli operatore, fullScopeAllowed false)..."
for r in "${OPERATOR_ROLES[@]}"; do kc_get "roles/${r}" "$WORK/role-${r}.json"; done
python3 - "$WORK" "${OPERATOR_ROLES[@]}" <<'PY'
import json, os, sys
w, roles = sys.argv[1], sys.argv[2:]
json.dump([json.load(open(os.path.join(w, f"role-{r}.json"))) for r in roles], open(os.path.join(w, "role-reps.json"), "w"))
PY
HTTP_CODE="$(curl -sS -o "$WORK/scope-result.json" -w '%{http_code}' -X POST "${ADMIN}/clients/${CLIENT_UUID}/scope-mappings/realm" \
  -H "@$WORK/auth" -H 'Content-Type: application/json' --data-binary "@$WORK/role-reps.json")"
if [ "$HTTP_CODE" != "204" ] && [ "$HTTP_CODE" != "200" ]; then
  echo "Errore: gli scope mapping di lh-cli hanno risposto ${HTTP_CODE}: $(head -c 300 "$WORK/scope-result.json")" >&2
  exit 1
fi

# 5. Verifica: rilegge realm e client; qualunque scostamento e' un errore.
kc_get "" "$WORK/realm-now.json"
kc_get "clients" "$WORK/client-now.json" --data-urlencode "clientId=lh-cli"
kc_get "clients/${CLIENT_UUID}/optional-client-scopes" "$WORK/optional-scopes.json"
kc_get "clients/${CLIENT_UUID}/default-client-scopes" "$WORK/default-scopes.json"
kc_get "clients/${CLIENT_UUID}/scope-mappings/realm" "$WORK/scope-now.json"
python3 - "$WORK" <<'PY'
import json, os, sys
w = sys.argv[1]
load = lambda n: json.load(open(os.path.join(w, n)))
before, realm, clients = load("realm-before.json"), load("realm-now.json"), load("client-now.json")
optional, default, scope_now = load("optional-scopes.json"), load("default-scopes.json"), load("scope-now.json")
exp = load("expected.json")
want = exp["clients"][0]
errors = []
if realm.get("registrationAllowed") is not False:
    errors.append("registrationAllowed non e' false")
if realm.get("browserFlow") != "browser-mfa":
    errors.append("browserFlow non e' browser-mfa (MFA degli operatori)")
# Impostazioni di sicurezza del realm che il PUT non deve aver toccato.
for k in ("bruteForceProtected", "failureFactor", "maxFailureWaitSeconds", "otpPolicyType", "otpPolicyAlgorithm", "otpPolicyDigits",
          "otpPolicyPeriod", "otpPolicyCodeReusable", "eventsEnabled", "adminEventsEnabled", "accessTokenLifespan",
          "ssoSessionIdleTimeout", "ssoSessionMaxLifespan", "verifyEmail", "registrationFlow", "directGrantFlow",
          "passwordPolicy", "sslRequired", "loginWithEmailAllowed", "webAuthnPolicyUserVerificationRequirement"):
    if before.get(k) != realm.get(k):
        errors.append(f"impostazione del realm cambiata dal PUT: {k}")
if len(clients) != 1:
    errors.append("client lh-cli assente")
else:
    c = clients[0]
    a, wa = c.get("attributes", {}), want.get("attributes", {})
    if c.get("publicClient") is not True: errors.append("lh-cli non e' pubblico")
    if c.get("secret"): errors.append("lh-cli ha un segreto")
    if c.get("consentRequired") is not True: errors.append("lh-cli senza consenso obbligatorio")
    if c.get("fullScopeAllowed") is not False: errors.append("lh-cli con fullScopeAllowed attivo")
    if a.get("oauth2.device.authorization.grant.enabled") != "true": errors.append("lh-cli senza device grant")
    for k in ("directAccessGrantsEnabled", "standardFlowEnabled", "implicitFlowEnabled", "serviceAccountsEnabled"):
        if c.get(k): errors.append(f"lh-cli: {k} attivo")
    if c.get("redirectUris"): errors.append("lh-cli: redirect URI presenti")
    for k, v in wa.items():
        if str(a.get(k)) != str(v): errors.append(f"lh-cli: attributo {k} diverso dall'overlay")
    if optional: errors.append("lh-cli: scope opzionali presenti (" + ",".join(s.get("name", "?") for s in optional) + ")")
    names = {s.get("name") for s in default}
    if "offline_access" in names: errors.append("lh-cli: offline_access tra gli scope predefiniti")
    for s in want.get("defaultClientScopes", []):
        if s not in names: errors.append(f"lh-cli: manca lo scope predefinito {s}")
    if {r.get("name") for r in scope_now} != set(exp["scopeMappings"][0]["roles"]):
        errors.append("lh-cli: scope mapping dei ruoli diversi dai soli ruoli operatore")
if errors:
    sys.exit("Errore: verifica dell'overlay fallita: " + "; ".join(errors))
print("  verifica: registrationAllowed=false, browserFlow=browser-mfa, lh-cli pubblico, consenso obbligatorio, solo device grant, ruoli operatore soli, nessuno scope opzionale")
PY

# 6. MFA degli account operatore gia' presenti (Q-618, default proposto, APERTA).
echo "Verifica della MFA degli account operatore..."
check_operators

echo "Overlay di vetrina applicato al realm ${REALM}."
