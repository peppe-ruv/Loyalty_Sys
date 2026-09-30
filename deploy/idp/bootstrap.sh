#!/bin/bash
# Script di bootstrap per impostare le password temporanee degli operatori demo (F2-IAM-01, regola 20, ADR-049).
# ISTRUZIONI:
# Esportare le variabili d'ambiente con le password desiderate (o lasciare che siano generate)
# ed eseguire questo script dopo l'avvio di Keycloak.
#
# Le password generate NON vengono mai stampate su stdout ne' nei log: vanno in un file con permessi 0600
# (creato in modo atomico con umask 077, poi rinominato a fine lavoro) e lo script stampa solo il percorso.
# Se lo script fallisce a meta', il file non viene creato: rilanciarlo reimposta tutte le password. Le password fornite da TEMP_PASS_*
# non vengono riscritte nel file. Anche verso Keycloak le password passano da stdin e da file 0600, mai come
# argomenti di un processo.
#   LH_IDP_BOOTSTRAP_OUT   percorso del file delle credenziali generate
#                          (default: deploy/idp/.secrets/bootstrap-passwords.txt, cartella ignorata da git)
#
# Nella vetrina enterprise (ADR-049, Q-618) gli account operatore sono nominativi e si creano a mano: questo
# script riguarda i soli cinque utenti demo del realm base e non va eseguito per pubblicare credenziali.

set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KEYCLOAK_URL=${KEYCLOAK_URL:-"http://localhost:8080"}
REALM="loyaltyhub"
ADMIN_USER=${KC_BOOTSTRAP_ADMIN_USERNAME:-"admin"}
ADMIN_PASSWORD=${KC_BOOTSTRAP_ADMIN_PASSWORD:?"KC_BOOTSTRAP_ADMIN_PASSWORD deve essere impostata"}
CRED_FILE="${LH_IDP_BOOTSTRAP_OUT:-${SCRIPT_DIR}/.secrets/bootstrap-passwords.txt}"

command -v python3 >/dev/null || { echo "Errore: serve python3." >&2; exit 1; }
command -v curl >/dev/null || { echo "Errore: serve curl." >&2; exit 1; }
command -v openssl >/dev/null || { echo "Errore: serve openssl." >&2; exit 1; }

# Il file delle credenziali si crea in modo atomico: si scrive in un file temporaneo nella stessa cartella
# (mktemp lo crea con O_EXCL e, con umask 077, 0600, senza seguire collegamenti simbolici) e solo a fine lavoro,
# con tutte le password impostate, lo si rinomina sul percorso finale con rename(2), che sostituisce un eventuale
# collegamento simbolico invece di seguirlo. Nessun file con la sola intestazione resta se l'autenticazione fallisce.
CRED_DIR="$(dirname "$CRED_FILE")"
if [ -L "$CRED_DIR" ]; then
  echo "Errore: ${CRED_DIR} e' un collegamento simbolico." >&2
  exit 1
fi
mkdir -p -m 700 "$CRED_DIR"
if [ -L "$CRED_FILE" ] || [ -d "$CRED_FILE" ]; then
  echo "Errore: ${CRED_FILE} e' un collegamento simbolico o una cartella." >&2
  exit 1
fi
CRED_TMP="$(mktemp "${CRED_DIR}/.bootstrap-passwords.XXXXXX")"

WORK="$(mktemp -d)"
chmod 700 "$WORK"
trap 'rm -rf "$WORK"; rm -f "$CRED_TMP"' EXIT
printf '# Password temporanee generate da bootstrap.sh (UPDATE_PASSWORD al primo accesso). Consegnare fuori banda e cancellare.\n' >> "$CRED_TMP"

# Codifica per application/x-www-form-urlencoded: legge da stdin, scrive su stdout (nome utente e password).
urlenc() { python3 -c 'import sys,urllib.parse;sys.stdout.write(urllib.parse.quote(sys.stdin.read(),safe=""))'; }

echo "Ottengo l'access token di amministrazione..."
printf 'client_id=admin-cli&grant_type=password&username=' > "$WORK/login"
printf '%s' "$ADMIN_USER" | urlenc >> "$WORK/login"
printf '&password=' >> "$WORK/login"
printf '%s' "$ADMIN_PASSWORD" | urlenc >> "$WORK/login"
TOKEN="$(curl -s -f -X POST "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" --data-binary "@$WORK/login" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')" || {
    echo "Errore: Impossibile ottenere il token di amministrazione." >&2
    exit 1
}
# Il token sta in un file 0600 e si passa con -H @file: non compare negli argomenti dei processi (curl >= 7.55).
printf 'Authorization: Bearer %s\n' "$TOKEN" > "$WORK/auth"
unset TOKEN ADMIN_PASSWORD

# Funzione per generare o usare password: stampa il valore solo per chi lo cattura con $(...), mai su stdout dello script
get_pass() {
    local env_val="${1:-}"
    if [ -n "$env_val" ]; then
        printf '%s\n' "$env_val"
    else
        openssl rand -base64 18
    fi
}

# Le password da impostare (e se sono state generate da questo script)
PASS_MARTA=$(get_pass "${TEMP_PASS_MARTA:-}")
PASS_LUCA=$(get_pass "${TEMP_PASS_LUCA:-}")
PASS_ELENA=$(get_pass "${TEMP_PASS_ELENA:-}")
PASS_PAOLO=$(get_pass "${TEMP_PASS_PAOLO:-}")
PASS_SARA=$(get_pass "${TEMP_PASS_SARA:-}")

declare -A USERS=(
    ["marta.admin"]=$PASS_MARTA
    ["luca.marketing"]=$PASS_LUCA
    ["elena.legal"]=$PASS_ELENA
    ["paolo.care"]=$PASS_PAOLO
    ["sara.analyst"]=$PASS_SARA
)
declare -A SUPPLIED=(
    ["marta.admin"]=${TEMP_PASS_MARTA:-}
    ["luca.marketing"]=${TEMP_PASS_LUCA:-}
    ["elena.legal"]=${TEMP_PASS_ELENA:-}
    ["paolo.care"]=${TEMP_PASS_PAOLO:-}
    ["sara.analyst"]=${TEMP_PASS_SARA:-}
)
unset PASS_MARTA PASS_LUCA PASS_ELENA PASS_PAOLO PASS_SARA

for USERNAME in "${!USERS[@]}"; do
    echo "Cerco l'utente $USERNAME..."
    USER_ID=$(curl -s -f -X GET "${KEYCLOAK_URL}/admin/realms/${REALM}/users?username=${USERNAME}&exact=true" \
      -H "@$WORK/auth" | python3 -c 'import sys,json;u=json.load(sys.stdin);print(u[0]["id"] if u else "")') || true

    if [ -n "$USER_ID" ]; then
        echo "Imposto la password per $USERNAME (ID: $USER_ID)..."
        # Corpo JSON costruito da python3 leggendo la password da stdin (nessun escape a mano, nessun argomento di processo).
        printf '%s' "${USERS[$USERNAME]}" \
          | python3 -c 'import sys,json;json.dump({"type":"password","value":sys.stdin.read(),"temporary":True},sys.stdout)' \
          | curl -s -f -X PUT "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${USER_ID}/reset-password" \
              -H "@$WORK/auth" -H "Content-Type: application/json" --data-binary @- || {
            echo "Errore nell'impostare la password per $USERNAME" >&2
            exit 1
        }
        if [ -n "${SUPPLIED[$USERNAME]}" ]; then
            printf '%s: (fornita da TEMP_PASS_*, non registrata qui)\n' "$USERNAME" >> "$CRED_TMP"
        else
            printf '%s: %s\n' "$USERNAME" "${USERS[$USERNAME]}" >> "$CRED_TMP"
        fi
        echo "Password impostata con successo per $USERNAME (azione UPDATE_PASSWORD richiesta al primo login)."
    else
        echo "Attenzione: Utente $USERNAME non trovato nel realm." >&2
    fi
done

# Tutte le password sono impostate: il file temporaneo diventa il file delle credenziali (rename atomico, 0600).
python3 -c 'import os,sys;os.replace(sys.argv[1],sys.argv[2])' "$CRED_TMP" "$CRED_FILE"

echo "Bootstrap delle password completato."
echo "Password temporanee generate scritte in: ${CRED_FILE} (permessi 0600). Consegnarle fuori banda e cancellare il file."
