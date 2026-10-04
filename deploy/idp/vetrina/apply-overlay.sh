#!/usr/bin/env bash
# VETRINA ENTERPRISE (F2-IAM-01, F2-IAM-03, ADR-049, ADR-051; Q-618, Q-619, Q-626, Q-671, Q-672, Q-676, Q-677).
# Applica gli overlay di vetrina ai realm gia' avviati da Keycloak, con lo stesso meccanismo di
# test-idp/apply-overlay.sh ma senza segnaposto ne' segreti veri:
#   - realm `master` (master.json, Q-672): sole impostazioni (blocco dei tentativi, eventi di amministrazione con
#     dettagli, sslRequired, politica delle password), lettura + unione + PUT dell'intera rappresentazione e verifica;
#   - realm `loyaltyhub` (realm-vetrina-overlay.json): impostazioni (registrazione chiusa, eventi di amministrazione con
#     dettagli, Q-677): lettura del realm, unione con il frammento dell'overlay e PUT della rappresentazione completa
#     (come `kcadm update`: il partialImport non tocca le impostazioni del realm); client `lh-cli` (Device Authorization
#     Grant per la CLI dell'operatore) con partialImport OVERWRITE; scope mapping dei ruoli del client (il
#     partialImport non li porta: POST /clients/{id}/scope-mappings/realm); il ruolo LH_TEST_USER e gli UTENTI DI TEST
#     FISSI (ADR-051 decisione 1: cinque operatori con password e seme TOTP documentati, vetrina.admin) con
#     partialImport OVERWRITE, id fissi (il `sub` non cambia a ogni ripristino, Q-672);
#   - realm `loyaltyhub-members` (realm-members-vetrina-overlay.json): stesso metodo per le impostazioni (registrazione
#     libera chiusa, ADR-051; eventi di amministrazione con dettagli, Q-677) e per ruolo e utenti di test (Anna, Marco,
#     Giulia, Laura e membri.admin);
#   - ruoli del client `realm-management` degli amministratori di test (Q-671): il partialImport non li porta in modo
#     affidabile, quindi si assegnano con l'API e si rileggono;
#   - SOLO con LH_VETRINA_MASTER_ADMIN_PASSWORD (segreto del codespace del proprietario, ADR-055, Q-727): utente
#     permanente `proprietario` nel realm master con il ruolo `admin` e quella password, non temporanea, verificata con
#     un login vero. E' l'ULTIMO passo e, se fallisce (per esempio la password viola la politica del realm master:
#     almeno 12 caratteri e diversa dal nome utente), lo script termina con codice 3 DOPO aver applicato tutto il resto:
#     vetrina.sh lascia la console master chiusa ma non ferma la vetrina. Il valore non compare mai in un messaggio.
#     Il realm master non riceve piu' il `frontendUrl` della porta 8180 (Q-670, superata): vive sull'indirizzo pubblico.
# `--import-realm` salta un realm gia' esistente, quindi gli overlay non si applicherebbero da soli: vetrina.sh ricrea il
# database di Keycloak a ogni avvio (Q-672) e poi esegue questo script. Lo script e' idempotente, non tocca flussi di
# autenticazione e azioni richieste del realm, e non stampa mai una credenziale: le password e il seme TOTP di prova
# sono PUBBLICI e documentati (runbook e pagina Mintlify), ma restano comunque solo nei file 0600 del processo.
# Alla fine rilegge tutto e FALLISCE se il risultato non e' quello atteso, e verifica che ogni account operatore abbia
# MFA_REQUIRED_ROLE (Q-618): e' il solo ruolo che fa scattare l'OTP nel flusso browser-mfa.
#
# Uso:
#   KC_BOOTSTRAP_ADMIN_PASSWORD=... ./apply-overlay.sh     applica (o LH_IDP_ADMIN_PASSWORD)
#   ./apply-overlay.sh --check                              solo validazione degli overlay, nessuna rete
#   ./apply-overlay.sh --no-test-users                      applica senza ruolo e utenti di test: per una vetrina
#                                                           che non e' in un codespace (ADR-051, Q-676)
#   KC_BOOTSTRAP_ADMIN_PASSWORD=... ./apply-overlay.sh --check-operators
#                                                           solo lettura: fallisce se un account operatore non ha
#                                                           MFA_REQUIRED_ROLE; da rieseguire dopo ogni account creato
# Opzionali: KEYCLOAK_URL (default http://localhost:8080), KC_BOOTSTRAP_ADMIN_USERNAME (default admin),
# LH_VETRINA_MASTER_ADMIN_PASSWORD (password del proprietario nel realm master, ADR-055: solo da ambiente, mai da argomenti;
# vuota o assente = nessun utente del proprietario, console master chiusa).
# Uscita: 0 ok · 1 errore · 2 uso errato · 3 solo il passo del proprietario e' fallito (il resto e' applicato).
set -euo pipefail

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8080}"
REALM="loyaltyhub"
MEMBERS_REALM="loyaltyhub-members"
ADMIN_USER="${KC_BOOTSTRAP_ADMIN_USERNAME:-${LH_IDP_ADMIN_USERNAME:-admin}}"
ADMIN_PASSWORD="${KC_BOOTSTRAP_ADMIN_PASSWORD:-${LH_IDP_ADMIN_PASSWORD:-}}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OVERLAY="${OVERLAY:-$HERE/realm-vetrina-overlay.json}"
MEMBERS_OVERLAY="${MEMBERS_OVERLAY:-$HERE/realm-members-vetrina-overlay.json}"
MASTER_OVERLAY="${MASTER_OVERLAY:-$HERE/master.json}"
# Utente permanente del proprietario nel realm master (ADR-055, Q-727). La password si legge una volta e si toglie
# dall'ambiente: i processi figli (curl, python3) non la ereditano. Sta solo in questa variabile e in file 0600 di $WORK.
OWNER_USER="proprietario"
OWNER_PASSWORD="${LH_VETRINA_MASTER_ADMIN_PASSWORD:-}"
unset LH_VETRINA_MASTER_ADMIN_PASSWORD

# Impostazioni dei realm che gli overlay possono contenere (allowlist: una chiave fuori elenco e' un errore).
# check-realm.mjs verifica che gli elenchi coincidano con le chiavi dei file.
REALM_SETTINGS=(
  registrationAllowed
  adminEventsEnabled
  adminEventsDetailsEnabled
)
MEMBERS_SETTINGS=(
  registrationAllowed
  adminEventsEnabled
  adminEventsDetailsEnabled
)
MASTER_SETTINGS=(
  bruteForceProtected
  permanentLockout
  failureFactor
  maxFailureWaitSeconds
  minimumQuickLoginWaitSeconds
  waitIncrementSeconds
  maxDeltaTimeSeconds
  adminEventsEnabled
  adminEventsDetailsEnabled
  sslRequired
  passwordPolicy
)
# Ruoli degli account operatore (docs/08): chi ne ha uno deve avere anche MFA_REQUIRED_ROLE.
OPERATOR_ROLES=(ADMIN MARKETING LEGAL CARE ANALYST)
MFA_ROLE="MFA_REQUIRED_ROLE"
# Ruolo degli utenti di test (ADR-051 decisione 1, Q-676): hub e BFF li rifiutano salvo LH_TEST_USERS_ALLOWED=true.
TEST_ROLE="LH_TEST_USER"
# Ruoli del client realm-management degli amministratori di test (Q-671): niente manage-realm, manage-clients,
# manage-identity-providers, manage-events, realm-admin ne' impersonation.
MGMT_ROLES=(view-users query-users query-groups manage-users view-events)

MODE="apply"
TEST_USERS=1
for arg in "$@"; do
  case "$arg" in
    --check) MODE="check" ;;
    --check-operators) MODE="operators" ;;
    --no-test-users) TEST_USERS=0 ;;
    *) echo "Uso: $0 [--check | --check-operators] [--no-test-users]" >&2; exit 2 ;;
  esac
done

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

csv() { local IFS=,; echo "$*"; }

# 1. Validazione e scomposizione degli overlay. Nessun segnaposto ammesso (nessun segreto da sostituire). Operatori: il
#    client lh-cli deve essere pubblico, solo device grant, con consenso obbligatorio e ruoli limitati a quelli
#    operatore; gli utenti di test devono avere id fisso, LH_TEST_USER, password non temporanea, nessuna azione richiesta
#    (altrimenti il login dei test si fermerebbe su UPDATE_PASSWORD o CONFIGURE_TOTP), un solo seme TOTP condiviso per gli
#    operatori e, per gli amministratori di test, i soli ruoli di Q-671 e nessun ruolo applicativo.
REALM_SETTINGS_CSV="$(csv "${REALM_SETTINGS[@]}")" MEMBERS_SETTINGS_CSV="$(csv "${MEMBERS_SETTINGS[@]}")" \
MASTER_SETTINGS_CSV="$(csv "${MASTER_SETTINGS[@]}")" OPERATOR_ROLES_CSV="$(csv "${OPERATOR_ROLES[@]}")" \
MGMT_ROLES_CSV="$(csv "${MGMT_ROLES[@]}")" MFA_ROLE="$MFA_ROLE" TEST_ROLE="$TEST_ROLE" \
python3 - "$OVERLAY" "$MEMBERS_OVERLAY" "$MASTER_OVERLAY" "$WORK" <<'PY'
import json, os, re, sys
ops_file, mem_file, master_file, work = sys.argv[1:5]
csvset = lambda k: set(os.environ[k].split(","))
op_settings, mem_settings, master_settings = csvset("REALM_SETTINGS_CSV"), csvset("MEMBERS_SETTINGS_CSV"), csvset("MASTER_SETTINGS_CSV")
operators, mgmt = csvset("OPERATOR_ROLES_CSV"), csvset("MGMT_ROLES_CSV")
mfa, test_role = os.environ["MFA_ROLE"], os.environ["TEST_ROLE"]
UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
write = lambda name, obj: json.dump(obj, open(os.path.join(work, name), "w"))

def load(path, realm):
    raw = open(path).read()
    if "${" in raw:
        sys.exit(f"Errore: l'overlay {os.path.basename(path)} non ammette segnaposto ${{...}} (nessun segreto da sostituire)")
    doc = json.loads(raw)
    doc.pop("_comment", None)
    if doc.pop("realm", None) != realm:
        sys.exit(f"Errore: {os.path.basename(path)} deve riguardare il realm {realm}")
    return doc

def check_roles(doc, name):
    roles = doc.pop("roles", {})
    if set(roles) != {"realm"} or [r.get("name") for r in roles["realm"]] != [test_role]:
        sys.exit(f"Errore: {name}: l'unico ruolo ammesso e' {test_role} (ruolo di realm, nessun ruolo di client)")
    return roles

def check_users(users, name, allowed_roles, with_otp):
    """Utenti di test: id fissi, ruolo LH_TEST_USER, password non temporanee, nessuna azione richiesta."""
    seen_ids, seen_names, seeds, expected, imported = set(), set(), set(), [], []
    allowed_keys = {"id", "username", "enabled", "email", "emailVerified", "firstName", "lastName", "realmRoles", "clientRoles", "credentials"}
    for u in users:
        who = u.get("username", "?")
        if set(u) - allowed_keys:
            sys.exit(f"Errore: {name}: campi non ammessi per {who}: " + ", ".join(sorted(set(u) - allowed_keys)))
        if not UUID.match(u.get("id", "")) or u["id"] in seen_ids:
            sys.exit(f"Errore: {name}: {who} deve avere un id UUID fisso e unico (il sub non cambia a ogni ripristino, Q-672)")
        if not re.match(r"^[a-z0-9.]+$", who) or who in seen_names:
            sys.exit(f"Errore: {name}: nome utente non valido o duplicato: {who}")
        seen_ids.add(u["id"]); seen_names.add(who)
        if u.get("enabled") is not True or not all(u.get(k) for k in ("email", "firstName", "lastName")):
            sys.exit(f"Errore: {name}: {who} deve essere abilitato e avere e-mail, nome e cognome (senza, Keycloak chiede di completare il profilo al login)")
        roles = u.get("realmRoles", [])
        if test_role not in roles or not set(roles) <= allowed_roles:
            sys.exit(f"Errore: {name}: {who}: i ruoli devono includere {test_role} e stare in " + ", ".join(sorted(allowed_roles)))
        creds = u.get("credentials", [])
        types = [c.get("type") for c in creds]
        if types.count("password") != 1 or set(types) - {"password", "otp"} or types.count("otp") > 1:
            sys.exit(f"Errore: {name}: {who}: credenziali ammesse: una password e al piu' un otp")
        for c in creds:
            if c["type"] == "password":
                if set(c) != {"type", "value", "temporary"} or c["temporary"] is not False or len(c["value"]) < 12:
                    sys.exit(f"Errore: {name}: {who}: la password deve essere fissa (non temporanea) e di almeno 12 caratteri")
            else:
                sd, cd = json.loads(c.get("secretData", "{}")), json.loads(c.get("credentialData", "{}"))
                if set(sd) != {"value"} or len(sd["value"]) != 20 or not sd["value"].isascii():
                    sys.exit(f"Errore: {name}: {who}: secretData dell'OTP deve essere un seme ASCII di 20 caratteri")
                if (cd.get("subType"), cd.get("digits"), cd.get("period"), cd.get("algorithm")) != ("totp", 6, 30, "HmacSHA1"):
                    sys.exit(f"Errore: {name}: {who}: l'OTP deve essere TOTP a 6 cifre, 30 secondi, HmacSHA1 (politica del realm)")
                seeds.add(sd["value"])
        has_otp = "otp" in types
        is_operator = bool(set(roles) & operators)
        if with_otp and is_operator and not (mfa in roles and has_otp):
            sys.exit(f"Errore: {name}: l'operatore {who} deve avere {mfa} e la credenziale OTP del seme documentato (nessuna azione richiesta CONFIGURE_TOTP)")
        if not with_otp and (has_otp or mfa in roles or set(roles) & operators):
            sys.exit(f"Errore: {name}: {who}: nel realm dei membri niente OTP ne' ruoli operatore")
        if has_otp and mfa not in roles:
            sys.exit(f"Errore: {name}: {who}: l'OTP senza {mfa} non scatta mai")
        client_roles = u.get("clientRoles", {})
        mg = []
        if client_roles:
            if set(client_roles) != {"realm-management"} or set(client_roles["realm-management"]) != mgmt \
               or len(client_roles["realm-management"]) != len(mgmt):
                sys.exit(f"Errore: {name}: {who}: ruoli di realm-management ammessi solo " + ", ".join(sorted(mgmt)) + " (Q-671)")
            if roles != [test_role] or has_otp:
                sys.exit(f"Errore: {name}: {who}: un amministratore di test non ha ruoli applicativi ne' MFA (non entra nel backoffice, Q-671)")
            mg = sorted(mgmt)
        expected.append({"id": u["id"], "username": who, "realmRoles": roles, "mgmt": mg, "otp": has_otp})
        imported.append({k: v for k, v in u.items() if k != "clientRoles"})
    if with_otp and len(seeds) != 1:
        sys.exit(f"Errore: {name}: gli operatori condividono UN solo seme TOTP documentato (trovati {len(seeds)})")
    if not any(e["mgmt"] for e in expected):
        sys.exit(f"Errore: {name}: manca l'amministratore di test (Q-671)")
    return expected, imported

# --- operatori ---
doc = load(ops_file, "loyaltyhub")
clients = doc.pop("clients", [])
scope_mappings = doc.pop("scopeMappings", [])
roles = check_roles(doc, "operatori")
users = doc.pop("users", [])
unknown = sorted(set(doc) - op_settings)
if unknown:
    sys.exit("Errore: chiavi non ammesse nell'overlay di vetrina: " + ", ".join(unknown))
if doc.get("registrationAllowed") is not False:
    sys.exit("Errore: registrationAllowed deve essere false nell'overlay di vetrina (Q-619)")
if doc.get("adminEventsEnabled") is not True or doc.get("adminEventsDetailsEnabled") is not True:
    sys.exit("Errore: gli eventi di amministrazione con i dettagli devono essere attivi (Q-677)")
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
op_expected, op_users = check_users(users, "operatori", operators | {mfa, test_role}, True)
write("realm-settings.json", doc)
write("partial.json", {"ifResourceExists": "OVERWRITE", "clients": clients})
write("expected.json", {"clients": clients, "scopeMappings": scope_mappings})
write("ops-roles.json", {"ifResourceExists": "OVERWRITE", "roles": roles})
write("ops-users.json", {"ifResourceExists": "OVERWRITE", "users": op_users})
write("ops-expected-users.json", op_expected)

# --- membri ---
mdoc = load(mem_file, "loyaltyhub-members")
mroles = check_roles(mdoc, "membri")
musers = mdoc.pop("users", [])
unknown = sorted(set(mdoc) - mem_settings)
if unknown:
    sys.exit("Errore: chiavi non ammesse nell'overlay dei membri: " + ", ".join(unknown))
if mdoc.get("registrationAllowed") is not False:
    sys.exit("Errore: registrationAllowed deve essere false nell'overlay dei membri (ADR-051, Q-673)")
if mdoc.get("adminEventsEnabled") is not True or mdoc.get("adminEventsDetailsEnabled") is not True:
    sys.exit("Errore: gli eventi di amministrazione con i dettagli devono essere attivi nel realm dei membri (Q-677)")
mem_expected, mem_users = check_users(musers, "membri", {"MEMBER", test_role, "default-roles-loyaltyhub-members"}, False)
write("members-settings.json", mdoc)
write("mem-roles.json", {"ifResourceExists": "OVERWRITE", "roles": mroles})
write("mem-users.json", {"ifResourceExists": "OVERWRITE", "users": mem_users})
write("mem-expected-users.json", mem_expected)

# --- master ---
ms = load(master_file, "master")
unknown = sorted(set(ms) - master_settings)
if unknown:
    sys.exit("Errore: chiavi non ammesse in master.json (niente utenti, client o frontendUrl: l'utente del proprietario viene da LH_VETRINA_MASTER_ADMIN_PASSWORD, ADR-055): " + ", ".join(unknown))
if not (ms.get("bruteForceProtected") is True and ms.get("adminEventsEnabled") is True and ms.get("adminEventsDetailsEnabled") is True
        and ms.get("sslRequired") in ("external", "all") and ms.get("passwordPolicy")):
    sys.exit("Errore: master.json deve attivare blocco dei tentativi, eventi di amministrazione con dettagli, sslRequired external e una politica delle password")
write("master-settings.json", ms)
print("  overlay validi: operatori=%d utenti, membri=%d utenti, master=%d impostazioni, client=%s"
      % (len(op_users), len(mem_users), len(ms), ",".join(c["clientId"] for c in clients) or "-"))
PY
chmod 600 "$WORK"/*.json
if [ "$MODE" = "check" ]; then
  echo "Overlay di vetrina validi (nessuna modifica applicata)."
  exit 0
fi

# 2. Token di amministrazione (nome utente e password codificati e passati da file, non sulla riga di comando).
printf 'client_id=admin-cli&grant_type=password&username=' > "$WORK/login"
printf '%s' "$ADMIN_USER" | urlenc >> "$WORK/login"
printf '&password=' >> "$WORK/login"
printf '%s' "$ADMIN_PASSWORD" | urlenc >> "$WORK/login"
chmod 600 "$WORK/login"
# La password resta solo nel file 0600 $WORK/login (rimosso all'uscita): serve a ripetere il login dopo il frontendUrl.
# Keycloak puo' risultare pronto (/health/ready) mentre finisce ancora l'avvio: importa i realm e crea l'amministratore
# temporaneo, e intanto risponde 503 («Request received during bootstrapping»). Si riprova finche' arriva un token, fino
# a KC_TOKEN_WAIT_S secondi (default 180, un tentativo ogni KC_TOKEN_RETRY_S, default 5); una risposta 401 (credenziali
# sbagliate) non si riprova.
fetch_token() {
  local token="" code waited=0 limit="${KC_TOKEN_WAIT_S:-180}" step="${KC_TOKEN_RETRY_S:-5}"
  while :; do
    code="$(curl -sS -o "$WORK/token.json" -w '%{http_code}' -X POST "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
      -H 'Content-Type: application/x-www-form-urlencoded' --data-binary "@$WORK/login" 2>/dev/null || true)"
    if [ "$code" = 200 ]; then
      token="$(python3 -c 'import sys,json;print(json.load(open(sys.argv[1]))["access_token"])' "$WORK/token.json" 2>/dev/null || true)"
    fi
    rm -f "$WORK/token.json"
    [ -n "$token" ] && break
    if [ "$code" = 401 ] || [ "$waited" -ge "$limit" ]; then
      echo "Errore: impossibile ottenere il token di amministrazione da ${KEYCLOAK_URL} (HTTP ${code:-000} dopo ${waited} s)." >&2
      exit 1
    fi
    [ "$waited" = 0 ] && echo "  Keycloak non e' ancora pronto (HTTP ${code:-000}): riprovo ogni ${step} s per al massimo ${limit} s"
    sleep "$step"
    waited=$((waited + step))
    [ "$step" -gt 0 ] || waited=$((waited + 1))
  done
  # Il token sta in un file 0600 e si passa con -H @file: non compare negli argomenti dei processi (curl >= 7.55).
  printf 'Authorization: Bearer %s\n' "$token" > "$WORK/auth"
  chmod 600 "$WORK/auth"
}
fetch_token
unset ADMIN_PASSWORD
KC_ROOT="${KEYCLOAK_URL}/admin/realms"

# GET dell'API di amministrazione di un realm: kc_get_r <realm> <percorso> <file di uscita> [opzioni di curl...]
kc_get_r() { local realm="$1" path="$2" out="$3"; shift 3; curl -sS -f -G "${KC_ROOT}/${realm}${path:+/${path}}" -H "@$WORK/auth" "$@" > "$out"; }
# Lo stesso sul realm degli operatori.
kc_get() { local path="$1" out="$2"; shift 2; kc_get_r "$REALM" "$path" "$out" "$@"; }
# Scrittura JSON dal file indicato: kc_send <metodo> <realm> <percorso> <file> <codici ammessi> <cosa>
kc_send() {
  local method="$1" realm="$2" path="$3" file="$4" ok="$5" what="$6" code
  code="$(curl -sS -o "$WORK/send-result.json" -w '%{http_code}' -X "$method" "${KC_ROOT}/${realm}${path:+/${path}}" \
    -H "@$WORK/auth" -H 'Content-Type: application/json' --data-binary "@$file")"
  case " $ok " in
    *" $code "*) ;;
    *) echo "Errore: ${what} ha risposto ${code}: $(head -c 300 "$WORK/send-result.json")" >&2; exit 1 ;;
  esac
}

# partialImport con OVERWRITE: partial_import <realm> <file> <cosa>. Stampa i conteggi e i nomi delle risorse.
partial_import() {
  local code
  code="$(curl -sS -o "$WORK/partial-result.json" -w '%{http_code}' -X POST "${KC_ROOT}/$1/partialImport" \
    -H "@$WORK/auth" -H 'Content-Type: application/json' --data-binary "@$2")"
  if [ "$code" != "200" ]; then
    echo "Errore: partialImport ($3, realm $1) ha risposto ${code}: $(head -c 300 "$WORK/partial-result.json")" >&2
    exit 1
  fi
  python3 -c 'import sys,json;r=json.load(sys.stdin);print("  aggiunti=%s sovrascritti=%s saltati=%s"%(r.get("added"),r.get("overwritten"),r.get("skipped")));[print("  -",x["resourceType"],x["resourceName"],x["action"]) for x in r.get("results",[])]' < "$WORK/partial-result.json"
}

# Impostazioni di un realm: lettura della rappresentazione corrente, unione col frammento dell'overlay e PUT dell'intera
# rappresentazione (come kcadm update): put_realm <realm> <file delle impostazioni> <file di uscita con lo stato prima>
put_realm() {
  local realm="$1" settings="$2" before="$3" code
  kc_get_r "$realm" "" "$before" || { echo "Errore: realm ${realm} assente (importato da --import-realm?)." >&2; exit 1; }
  python3 - "$before" "$settings" "$WORK/realm-put.json" <<'PY'
import json, sys
realm = json.load(open(sys.argv[1]))
realm.update(json.load(open(sys.argv[2])))
json.dump(realm, open(sys.argv[3], "w"))
PY
  chmod 600 "$WORK/realm-put.json"
  code="$(curl -sS -o "$WORK/put-result.json" -w '%{http_code}' -X PUT "${KC_ROOT}/${realm}" \
    -H "@$WORK/auth" -H 'Content-Type: application/json' --data-binary "@$WORK/realm-put.json")"
  if [ "$code" != "204" ] && [ "$code" != "200" ]; then
    echo "Errore: l'aggiornamento del realm ${realm} ha risposto ${code}: $(head -c 300 "$WORK/put-result.json")" >&2
    exit 1
  fi
}

# Rilettura e verifica delle impostazioni: ogni chiave dell'overlay ha il valore voluto e le impostazioni di sicurezza
# non previste dall'overlay sono uguali a prima del PUT: verify_realm <realm> <stato prima> <impostazioni> <etichetta>
verify_realm() {
  local realm="$1" before="$2" settings="$3" label="$4"
  kc_get_r "$realm" "" "$WORK/realm-now.json"
  python3 - "$before" "$WORK/realm-now.json" "$settings" "$label" <<'PY'
import json, sys
before, now, want, label = json.load(open(sys.argv[1])), json.load(open(sys.argv[2])), json.load(open(sys.argv[3])), sys.argv[4]
errors = [f"{k} non e' {v!r}" for k, v in want.items() if now.get(k) != v]
# Impostazioni di sicurezza che il PUT non deve aver toccato (salvo quelle volute dall'overlay).
for k in ("bruteForceProtected", "failureFactor", "maxFailureWaitSeconds", "otpPolicyType", "otpPolicyAlgorithm", "otpPolicyDigits",
          "otpPolicyPeriod", "otpPolicyCodeReusable", "eventsEnabled", "adminEventsEnabled", "adminEventsDetailsEnabled", "accessTokenLifespan",
          "ssoSessionIdleTimeout", "ssoSessionMaxLifespan", "verifyEmail", "registrationAllowed", "registrationFlow", "directGrantFlow",
          "browserFlow", "passwordPolicy", "sslRequired", "loginWithEmailAllowed", "webAuthnPolicyUserVerificationRequirement"):
    if k not in want and before.get(k) != now.get(k):
        errors.append(f"impostazione cambiata dal PUT: {k}")
if errors:
    sys.exit(f"Errore: verifica del realm {label} fallita: " + "; ".join(errors))
print(f"  verifica: realm {label} con " + ", ".join(sorted(want)))
PY
}

# Utenti di test di un realm (ADR-051 decisione 1, Q-671, Q-672): ruolo LH_TEST_USER e utenti con partialImport
# OVERWRITE, ruoli di realm-management degli amministratori con l'API, poi rilettura di ogni utente per id.
# apply_users <realm> <prefisso dei file di lavoro>
apply_users() {
  local realm="$1" prefix="$2" rm_uuid uid
  partial_import "$realm" "$WORK/${prefix}-roles.json" "ruolo ${TEST_ROLE}"
  partial_import "$realm" "$WORK/${prefix}-users.json" "utenti di test"
  kc_get_r "$realm" clients "$WORK/rm-client.json" --data-urlencode "clientId=realm-management"
  rm_uuid="$(python3 -c 'import sys,json;c=json.load(open(sys.argv[1]));print(c[0]["id"] if len(c)==1 else "")' "$WORK/rm-client.json")"
  [ -n "$rm_uuid" ] || { echo "Errore: client realm-management assente nel realm ${realm}." >&2; exit 1; }
  kc_get_r "$realm" "clients/${rm_uuid}/roles" "$WORK/rm-roles.json" --data-urlencode "max=500"
  python3 - "$WORK/rm-roles.json" "$WORK/${prefix}-expected-users.json" "$WORK" "$(csv "${MGMT_ROLES[@]}")" <<'PY'
import json, os, sys
roles, expected, work, wanted = json.load(open(sys.argv[1])), json.load(open(sys.argv[2])), sys.argv[3], sys.argv[4].split(",")
by_name = {r["name"]: r for r in roles}
missing = [n for n in wanted if n not in by_name]
if missing:
    sys.exit("Errore: ruoli di realm-management assenti in Keycloak: " + ", ".join(missing))
json.dump([by_name[n] for n in wanted], open(os.path.join(work, "mgmt-roles.json"), "w"))
open(os.path.join(work, "mgmt-users.txt"), "w").write("".join(e["id"] + "\n" for e in expected if e["mgmt"]))
open(os.path.join(work, "all-users.txt"), "w").write("".join(e["id"] + "\n" for e in expected))
PY
  chmod 600 "$WORK/mgmt-roles.json"
  # Gli id sono fissi (Q-672): un utente non trovato per id vuol dire che Keycloak non li ha rispettati.
  while IFS= read -r uid; do
    kc_get_r "$realm" "users/${uid}" "$WORK/u-${uid}-user.json" || { echo "Errore: utente di test ${uid} assente in ${realm}: l'id fisso non e' stato rispettato." >&2; exit 1; }
  done < "$WORK/all-users.txt"
  while IFS= read -r uid; do
    kc_send POST "$realm" "users/${uid}/role-mappings/clients/${rm_uuid}" "$WORK/mgmt-roles.json" "204 200" "l'assegnazione dei ruoli di realm-management"
  done < "$WORK/mgmt-users.txt"
  while IFS= read -r uid; do
    kc_get_r "$realm" "users/${uid}/role-mappings" "$WORK/u-${uid}-roles.json"
    kc_get_r "$realm" "users/${uid}/credentials" "$WORK/u-${uid}-creds.json"
  done < "$WORK/all-users.txt"
  python3 - "$WORK" "$WORK/${prefix}-expected-users.json" "$realm" <<'PY'
import json, os, sys
w, expected, realm = sys.argv[1], json.load(open(sys.argv[2])), sys.argv[3]
load = lambda n: json.load(open(os.path.join(w, n)))
errors = []
for e in expected:
    i, who = e["id"], e["username"]
    u, rm, creds = load(f"u-{i}-user.json"), load(f"u-{i}-roles.json"), load(f"u-{i}-creds.json")
    if u.get("username") != who or u.get("enabled") is not True: errors.append(f"{who}: nome o stato diversi dall'overlay")
    if u.get("requiredActions"): errors.append(f"{who}: azioni richieste presenti (bloccherebbero il login)")
    have = {r["name"] for r in rm.get("realmMappings") or []}
    if set(e["realmRoles"]) - have: errors.append(f"{who}: mancano i ruoli " + ",".join(sorted(set(e["realmRoles"]) - have)))
    extra = have - set(e["realmRoles"]) - {f"default-roles-{realm}", "offline_access", "uma_authorization"}
    if extra: errors.append(f"{who}: ruoli non previsti " + ",".join(sorted(extra)))
    clients = rm.get("clientMappings") or {}
    got = {r["name"] for r in (clients.get("realm-management") or {}).get("mappings") or []}
    if got != set(e["mgmt"]): errors.append(f"{who}: ruoli di realm-management diversi da quelli di Q-671")
    if set(clients) - {"realm-management"}: errors.append(f"{who}: ruoli di client non previsti")
    types = {c.get("type") for c in creds}
    if "password" not in types: errors.append(f"{who}: manca la password")
    if ("otp" in types) != e["otp"]: errors.append(f"{who}: credenziale OTP " + ("assente (import dell'OTP non riuscito)" if e["otp"] else "inattesa"))
if errors:
    sys.exit(f"Errore: verifica degli utenti di test del realm {realm} fallita: " + "; ".join(errors))
print(f"  verifica: {len(expected)} utenti di test in {realm} con id fissi, ruoli e credenziali attesi")
PY
}

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

# Q-618 (decisa il 2026-09-30): ogni account operatore deve avere MFA_REQUIRED_ROLE, il solo ruolo che fa
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
  echo "Verifica della MFA degli account operatore (Q-618, decisa il 2026-09-30)..."
  check_operators
  exit 0
fi

# 3. Realm master (Q-672): blocco dei tentativi, eventi di amministrazione con dettagli, sslRequired, politica delle
#    password. Nessun utente e nessun segreto: la password dell'amministratore resta KC_BOOTSTRAP_ADMIN_PASSWORD.
echo "Realm master: impostazioni di sicurezza (master.json, Q-672)..."
put_realm master "$WORK/master-settings.json" "$WORK/master-before.json"
verify_realm master "$WORK/master-before.json" "$WORK/master-settings.json" "master"

# 4. Impostazioni del realm degli operatori (registrazione chiusa, Q-619; eventi di amministrazione, Q-677).
echo "Impostazioni del realm ${REALM} (registrazione chiusa, Q-619; eventi di amministrazione, Q-677)..."
put_realm "$REALM" "$WORK/realm-settings.json" "$WORK/realm-before.json"

# 5. Client (lh-cli): partialImport con OVERWRITE, poi gli scope mapping dei ruoli (il client viene ricreato).
echo "partialImport (OVERWRITE) dei client..."
partial_import "$REALM" "$WORK/partial.json" "client"

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
kc_send POST "$REALM" "clients/${CLIENT_UUID}/scope-mappings/realm" "$WORK/role-reps.json" "204 200" "gli scope mapping di lh-cli"

# 6. Verifica: rilegge realm e client; qualunque scostamento e' un errore.
verify_realm "$REALM" "$WORK/realm-before.json" "$WORK/realm-settings.json" "$REALM"
kc_get "clients" "$WORK/client-now.json" --data-urlencode "clientId=lh-cli"
kc_get "clients/${CLIENT_UUID}/optional-client-scopes" "$WORK/optional-scopes.json"
kc_get "clients/${CLIENT_UUID}/default-client-scopes" "$WORK/default-scopes.json"
kc_get "clients/${CLIENT_UUID}/scope-mappings/realm" "$WORK/scope-now.json"
python3 - "$WORK" <<'PY'
import json, os, sys
w = sys.argv[1]
load = lambda n: json.load(open(os.path.join(w, n)))
realm, clients = load("realm-now.json"), load("client-now.json")
optional, default, scope_now = load("optional-scopes.json"), load("default-scopes.json"), load("scope-now.json")
exp = load("expected.json")
want = exp["clients"][0]
errors = []
if realm.get("browserFlow") != "browser-mfa":
    errors.append("browserFlow non e' browser-mfa (MFA degli operatori)")
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
print("  verifica: browserFlow=browser-mfa, lh-cli pubblico, consenso obbligatorio, solo device grant, ruoli operatore soli, nessuno scope opzionale")
PY

# 7. Utenti di test del realm degli operatori (ADR-051 decisione 1, Q-671, Q-672): id fissi, password e seme TOTP
#    documentati, nessuna azione richiesta.
if [ "$TEST_USERS" = 1 ]; then
  echo "Utenti di test del realm ${REALM} (operatori con MFA fissa e vetrina.admin)..."
  apply_users "$REALM" ops
else
  echo "Utenti di test del realm ${REALM}: saltati (--no-test-users, solo il codespace li ammette)."
fi

# 8. MFA degli account operatore gia' presenti (Q-618, decisa il 2026-09-30): include gli operatori di test.
echo "Verifica della MFA degli account operatore..."
check_operators

# 9. Realm dei membri (ADR-051, Q-673, Q-677): nella vetrina la registrazione libera di account resta chiusa, perche'
#    Keycloak torna alla configurazione del repo a ogni avvio e gli account creati sparirebbero. Stesso metodo del
#    passo 4, poi verifica; poi i membri di test e membri.admin. Il realm base lascia la registrazione aperta.
echo "Realm dei membri: registrazione libera chiusa, eventi di amministrazione (ADR-051, Q-677)..."
put_realm "$MEMBERS_REALM" "$WORK/members-settings.json" "$WORK/members-before.json"
verify_realm "$MEMBERS_REALM" "$WORK/members-before.json" "$WORK/members-settings.json" "$MEMBERS_REALM"
if [ "$TEST_USERS" = 1 ]; then
  echo "Utenti di test del realm ${MEMBERS_REALM} (Anna, Marco, Giulia, Laura e membri.admin)..."
  apply_users "$MEMBERS_REALM" mem
else
  echo "Utenti di test del realm ${MEMBERS_REALM}: saltati (--no-test-users)."
fi


# 10. Utente del proprietario nel realm master (ADR-055, Q-727), solo con LH_VETRINA_MASTER_ADMIN_PASSWORD e SEMPRE per
#     ultimo: se fallisce, tutto il resto e' gia' applicato e lo script esce con 3 (vetrina.sh lascia la console master
#     chiusa e non ferma la vetrina). Utente permanente `proprietario` con il ruolo `admin` e la password del segreto,
#     NON temporanea; la verifica rilegge utente, ruolo e credenziale e fa un login vero con la password. Nessun messaggio
#     contiene il valore: Keycloak risponde alla violazione della politica con il solo motivo (es. lunghezza minima).
#     Gira in una sotto-shell dentro un `if`, dove `set -e` non vale: ogni passo controlla l'esito da se'.
owner_step() {
  local uid code lower_pw lower_user
  lower_pw="${OWNER_PASSWORD,,}"
  lower_user="${OWNER_USER,,}"
  if [ "${#OWNER_PASSWORD}" -lt 12 ] || [ "$lower_pw" = "$lower_user" ]; then
    echo "Errore: LH_VETRINA_MASTER_ADMIN_PASSWORD non rispetta la politica del realm master (almeno 12 caratteri e diversa dal nome utente ${OWNER_USER}). Console master chiusa." >&2
    return 1
  fi
  kc_get_r master users "$WORK/o-find.json" --data-urlencode "username=${OWNER_USER}" --data-urlencode "exact=true" \
    || { echo "Errore: elenco utenti del realm master non leggibile." >&2; return 1; }
  uid="$(python3 -c 'import sys,json;u=[x for x in json.load(open(sys.argv[1])) if x.get("username")==sys.argv[2]];print(u[0]["id"] if u else "")' "$WORK/o-find.json" "$OWNER_USER")" || return 1
  if [ -z "$uid" ]; then
    python3 -c 'import sys,json;json.dump({"username":sys.argv[1],"enabled":True,"emailVerified":True,"requiredActions":[]},open(sys.argv[2],"w"))' "$OWNER_USER" "$WORK/o-new.json" || return 1
    chmod 600 "$WORK/o-new.json"
    kc_send POST master users "$WORK/o-new.json" "201" "la creazione dell'utente ${OWNER_USER} nel realm master" || return 1
    kc_get_r master users "$WORK/o-find.json" --data-urlencode "username=${OWNER_USER}" --data-urlencode "exact=true" || return 1
    uid="$(python3 -c 'import sys,json;u=[x for x in json.load(open(sys.argv[1])) if x.get("username")==sys.argv[2]];print(u[0]["id"] if u else "")' "$WORK/o-find.json" "$OWNER_USER")" || return 1
    [ -n "$uid" ] || { echo "Errore: utente ${OWNER_USER} non trovato dopo la creazione." >&2; return 1; }
  fi
  # Utente gia' presente (riavvio): abilitato, senza azioni richieste, conservando il resto della rappresentazione.
  kc_get_r master "users/${uid}" "$WORK/o-user.json" || return 1
  python3 - "$WORK/o-user.json" "$WORK/o-user-put.json" <<'PY' || return 1
import json, sys
u = json.load(open(sys.argv[1]))
u.update({"enabled": True, "emailVerified": True, "requiredActions": []})
json.dump(u, open(sys.argv[2], "w"))
PY
  chmod 600 "$WORK/o-user-put.json"
  kc_send PUT master "users/${uid}" "$WORK/o-user-put.json" "204 200" "l'aggiornamento dell'utente ${OWNER_USER}" || return 1
  # Password non temporanea: il corpo sta in un file 0600, scritto da un builtin (printf) e da python3 via stdin, mai in argv.
  printf '%s' "$OWNER_PASSWORD" | python3 -c 'import sys,json;json.dump({"type":"password","value":sys.stdin.read(),"temporary":False},open(sys.argv[1],"w"))' "$WORK/o-pw.json" || return 1
  chmod 600 "$WORK/o-pw.json"
  code="$(curl -sS -o "$WORK/o-pw-result.json" -w '%{http_code}' -X PUT "${KC_ROOT}/master/users/${uid}/reset-password" \
    -H "@$WORK/auth" -H 'Content-Type: application/json' --data-binary "@$WORK/o-pw.json")" || code=000
  rm -f "$WORK/o-pw.json"
  if [ "$code" != "204" ] && [ "$code" != "200" ]; then
    # La risposta di Keycloak e' il motivo (codice e descrizione della politica), non contiene la password.
    echo "Errore: Keycloak ha rifiutato la password del proprietario (HTTP ${code}): $(python3 -c 'import sys,json;d=json.load(open(sys.argv[1]));print((d.get("error_description") or d.get("errorMessage") or d.get("error") or "motivo non indicato").rstrip("."))' "$WORK/o-pw-result.json" 2>/dev/null | head -c 200). Console master chiusa." >&2
    return 1
  fi
  kc_get_r master roles/admin "$WORK/o-role.json" || { echo "Errore: ruolo admin assente nel realm master." >&2; return 1; }
  python3 -c 'import sys,json;json.dump([json.load(open(sys.argv[1]))],open(sys.argv[2],"w"))' "$WORK/o-role.json" "$WORK/o-role-list.json" || return 1
  chmod 600 "$WORK/o-role-list.json"
  kc_send POST master "users/${uid}/role-mappings/realm" "$WORK/o-role-list.json" "204 200" "l'assegnazione del ruolo admin a ${OWNER_USER}" || return 1
  # Verifica: rilettura di utente, ruoli e credenziali, poi un login vero con la password (se fosse temporanea o
  # l'utente avesse azioni richieste, il login di prova fallirebbe).
  kc_get_r master "users/${uid}" "$WORK/o-user-now.json" || return 1
  kc_get_r master "users/${uid}/role-mappings" "$WORK/o-roles-now.json" || return 1
  kc_get_r master "users/${uid}/credentials" "$WORK/o-creds-now.json" || return 1
  python3 - "$WORK/o-user-now.json" "$WORK/o-roles-now.json" "$WORK/o-creds-now.json" <<'PY' || return 1
import json, sys
user, roles, creds = (json.load(open(p)) for p in sys.argv[1:4])
errors = []
if user.get("enabled") is not True: errors.append("utente non abilitato")
if user.get("requiredActions"): errors.append("azioni richieste presenti")
if "admin" not in {r["name"] for r in roles.get("realmMappings") or []}: errors.append("ruolo admin mancante")
if "password" not in {c.get("type") for c in creds}: errors.append("credenziale password mancante")
if errors:
    sys.exit("Errore: verifica dell'utente del proprietario fallita: " + "; ".join(errors) + ". Console master chiusa.")
PY
  printf 'client_id=admin-cli&grant_type=password&username=' > "$WORK/o-login"
  printf '%s' "$OWNER_USER" | urlenc >> "$WORK/o-login"
  printf '&password=' >> "$WORK/o-login"
  printf '%s' "$OWNER_PASSWORD" | urlenc >> "$WORK/o-login"
  chmod 600 "$WORK/o-login"
  code="$(curl -sS -o "$WORK/o-login-result.json" -w '%{http_code}' -X POST "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' --data-binary "@$WORK/o-login")" || code=000
  rm -f "$WORK/o-login" "$WORK/o-login-result.json"
  if [ "$code" != "200" ]; then
    echo "Errore: il login di prova dell'utente ${OWNER_USER} sul realm master non e' riuscito (HTTP ${code}). Console master chiusa." >&2
    return 1
  fi
  echo "  verifica: utente ${OWNER_USER} nel realm master con ruolo admin e password non temporanea (login di prova riuscito)"
}

OWNER_STATUS=skipped
if [ -n "$OWNER_PASSWORD" ]; then
  echo "Realm master: utente del proprietario ${OWNER_USER} (ADR-055, Q-727)..."
  if ( owner_step ); then OWNER_STATUS=ok; else OWNER_STATUS=failed; fi
else
  echo "Realm master: utente del proprietario saltato (segreto LH_VETRINA_MASTER_ADMIN_PASSWORD assente): console master chiusa."
fi
unset OWNER_PASSWORD

echo "Overlay di vetrina applicato ai realm master, ${REALM} e ${MEMBERS_REALM}."
if [ "$OWNER_STATUS" = failed ]; then
  echo "Avviso: l'utente del proprietario non e' pronto (messaggio sopra): il resto e' applicato, la console master resta chiusa." >&2
  exit 3
fi
