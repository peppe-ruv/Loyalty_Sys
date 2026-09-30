#!/bin/bash
# Script di bootstrap per impostare le password temporanee degli operatori demo (F2-IAM-01, regola 20, ADR-049).
# ISTRUZIONI:
# Esportare le variabili d'ambiente con le password desiderate (o lasciare che siano generate)
# ed eseguire questo script dopo l'avvio di Keycloak.
#
# Le password generate NON vengono mai stampate su stdout ne' nei log: vanno in un file con permessi 0600
# (umask 077 prima della creazione) e lo script stampa solo il percorso. Le password fornite da TEMP_PASS_*
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

WORK="$(mktemp -d)"
chmod 700 "$WORK"
trap 'rm -rf "$WORK"' EXIT

# File delle credenziali: crea la cartella (0700 se nuova), rifiuta un collegamento simbolico, crea il file
# vuoto con umask 077 e forza 0600 anche se esisteva gia' con permessi piu' larghi.
CRED_DIR="$(dirname "$CRED_FILE")"
mkdir -p -m 700 "$CRED_DIR"
if [ -L "$CRED_FILE" ]; then
  echo "Errore: ${CRED_FILE} e' un collegamento simbolico." >&2
  exit 1
fi
: > "$CRED_FILE"
chmod 600 "$CRED_FILE"
printf '# Password temporanee generate da bootstrap.sh (UPDATE_PASSWORD al primo accesso). Consegnare fuori banda e cancellare.\n' >> "$CRED_FILE"

echo "Ottengo l'access token di amministrazione..."
printf 'client_id=admin-cli&grant_type=password&username=%s&password=' "$ADMIN_USER" > "$WORK/login"
printf '%s' "$ADMIN_PASSWORD" | python3 -c 'import sys,urllib.parse;sys.stdout.write(urllib.parse.quote(sys.stdin.read(),safe=""))' >> "$WORK/login"
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
            printf '%s: (fornita da TEMP_PASS_*, non registrata qui)\n' "$USERNAME" >> "$CRED_FILE"
        else
            printf '%s: %s\n' "$USERNAME" "${USERS[$USERNAME]}" >> "$CRED_FILE"
        fi
        echo "Password impostata con successo per $USERNAME (azione UPDATE_PASSWORD richiesta al primo login)."
    else
        echo "Attenzione: Utente $USERNAME non trovato nel realm." >&2
    fi
done

echo "Bootstrap delle password completato."
echo "Password temporanee generate scritte in: ${CRED_FILE} (permessi 0600). Consegnarle fuori banda e cancellare il file."
