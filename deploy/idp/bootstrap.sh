#!/bin/bash
# Script di bootstrap per impostare le password temporanee degli operatori demo.
# ISTRUZIONI:
# Esportare le variabili d'ambiente con le password desiderate (o usare il default temporaneo)
# ed eseguire questo script dopo l'avvio di Keycloak.

KEYCLOAK_URL=${KEYCLOAK_URL:-"http://localhost:8080"}
REALM="loyaltyhub"
ADMIN_USER=${KC_BOOTSTRAP_ADMIN_USERNAME:-"admin"}
ADMIN_PASSWORD=${KC_BOOTSTRAP_ADMIN_PASSWORD:-"admin"}

# Le password da impostare
TEMP_PASS_MARTA=${TEMP_PASS_MARTA:-"cambiami123"}
TEMP_PASS_LUCA=${TEMP_PASS_LUCA:-"cambiami123"}
TEMP_PASS_ELENA=${TEMP_PASS_ELENA:-"cambiami123"}
TEMP_PASS_PAOLO=${TEMP_PASS_PAOLO:-"cambiami123"}
TEMP_PASS_SARA=${TEMP_PASS_SARA:-"cambiami123"}

echo "Ottengo l'access token di amministrazione..."
TOKEN=$(curl -s -X POST "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=admin-cli" \
  -d "username=${ADMIN_USER}" \
  -d "password=${ADMIN_PASSWORD}" \
  -d "grant_type=password" | grep -oP '"access_token":"\K[^"]+')

if [ -z "$TOKEN" ]; then
    echo "Errore: Impossibile ottenere il token di amministrazione."
    # do not use exit in this script block creation to avoid shell problems.
else
    declare -A USERS=(
        ["marta.admin"]=$TEMP_PASS_MARTA
        ["luca.marketing"]=$TEMP_PASS_LUCA
        ["elena.legal"]=$TEMP_PASS_ELENA
        ["paolo.care"]=$TEMP_PASS_PAOLO
        ["sara.analyst"]=$TEMP_PASS_SARA
    )

    for USERNAME in "${!USERS[@]}"; do
        PASSWORD=${USERS[$USERNAME]}

        echo "Cerco l'utente $USERNAME..."
        USER_ID=$(curl -s -X GET "${KEYCLOAK_URL}/admin/realms/${REALM}/users?username=${USERNAME}" \
          -H "Authorization: Bearer $TOKEN" | grep -oP '"id":"\K[^"]+' | head -1)

        if [ -n "$USER_ID" ]; then
            echo "Imposto la password per $USERNAME (ID: $USER_ID)..."
            curl -s -X PUT "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${USER_ID}/reset-password" \
              -H "Authorization: Bearer $TOKEN" \
              -H "Content-Type: application/json" \
              -d "{\"type\":\"password\",\"value\":\"${PASSWORD}\",\"temporary\":true}"
            echo "Password impostata con successo per $USERNAME (azione UPDATE_PASSWORD richiesta al primo login)."
        else
            echo "Attenzione: Utente $USERNAME non trovato nel realm."
        fi
    done

    echo "Bootstrap delle password completato."
fi
