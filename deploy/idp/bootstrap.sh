#!/bin/bash
# Script di bootstrap per impostare le password temporanee degli operatori demo.
# ISTRUZIONI:
# Esportare le variabili d'ambiente con le password desiderate (o usare il default temporaneo)
# ed eseguire questo script dopo l'avvio di Keycloak.

set -euo pipefail

KEYCLOAK_URL=${KEYCLOAK_URL:-"http://localhost:8080"}
REALM="loyaltyhub"
ADMIN_USER=${KC_BOOTSTRAP_ADMIN_USERNAME:-"admin"}
ADMIN_PASSWORD=${KC_BOOTSTRAP_ADMIN_PASSWORD:?"KC_BOOTSTRAP_ADMIN_PASSWORD deve essere impostata"}

echo "Ottengo l'access token di amministrazione..."
TOKEN=$(curl -s -f -X POST "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=admin-cli" \
  -d "username=${ADMIN_USER}" \
  -d "password=${ADMIN_PASSWORD}" \
  -d "grant_type=password" | grep -oP '"access_token":"\K[^"]+') || {
    echo "Errore: Impossibile ottenere il token di amministrazione." >&2
    exit 1
}

# Funzione per generare o usare password
get_pass() {
    local env_val="${1:-}"
    if [ -n "$env_val" ]; then
        echo "$env_val"
    else
        openssl rand -base64 18
    fi
}

# Le password da impostare
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

echo "--- Credenziali generate ---"
for USERNAME in "${!USERS[@]}"; do
    echo "Utente: $USERNAME -> Password: ${USERS[$USERNAME]}"
done
echo "----------------------------"

for USERNAME in "${!USERS[@]}"; do
    PASSWORD=${USERS[$USERNAME]}

    echo "Cerco l'utente $USERNAME..."
    USER_ID=$(curl -s -f -X GET "${KEYCLOAK_URL}/admin/realms/${REALM}/users?username=${USERNAME}" \
      -H "Authorization: Bearer $TOKEN" | grep -oP '"id":"\K[^"]+' | head -1) || true

    if [ -n "$USER_ID" ]; then
        echo "Imposto la password per $USERNAME (ID: $USER_ID)..."
        curl -s -f -X PUT "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${USER_ID}/reset-password" \
          -H "Authorization: Bearer $TOKEN" \
          -H "Content-Type: application/json" \
          -d "{\"type\":\"password\",\"value\":\"${PASSWORD}\",\"temporary\":true}" || {
            echo "Errore nell'impostare la password per $USERNAME" >&2
            exit 1
        }
        echo "Password impostata con successo per $USERNAME (azione UPDATE_PASSWORD richiesta al primo login)."
    else
        echo "Attenzione: Utente $USERNAME non trovato nel realm." >&2
    fi
done

echo "Bootstrap delle password completato."
