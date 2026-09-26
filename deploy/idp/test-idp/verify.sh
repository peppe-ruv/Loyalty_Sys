#!/bin/bash
# Script per verificare l'ottenimento di token dai provider
set -euo pipefail

KEYCLOAK_URL=${KEYCLOAK_URL:-"http://localhost:8080"}
REALM="loyaltyhub"
CLIENT_ID="web"
CLIENT_SECRET=${LH_WEB_CLIENT_SECRET:?"LH_WEB_CLIENT_SECRET deve essere impostata per il client web"}

echo "Verifica broker IdP aziendale..."
echo "Nota: Per ottenere un token reale dal broker è necessario un flusso interattivo OIDC."
echo "Esempio manuale:"
echo "1. Visita ${KEYCLOAK_URL}/realms/${REALM}/account/"
echo "2. Clicca su 'Sign In'"
echo "3. Scegli 'IdP Aziendale' dalla lista dei login provider"
echo "4. Autenticati nel realm idp-test (admin/admin o con un utente di test)"
echo ""

echo "Verifica utente LDAP..."
echo "Richiesta token per l'utente LDAP tramite grant_type=password..."
TOKEN=$(curl -s -f -X POST "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d "client_id=${CLIENT_ID}" \
  -d "client_secret=${CLIENT_SECRET}" \
  -d 'grant_type=password' \
  -d 'username=testuser' \
  -d 'password=testpassword' | grep -oP '"access_token":"\K[^"]+') || {
    echo "Errore: Impossibile ottenere il token via Resource Owner Password grant per l'utente LDAP." >&2
    exit 1
}

if [ -n "$TOKEN" ]; then
    echo "Access token ottenuto con successo per l'utente LDAP."
else
    echo "Errore: Nessun token restituito." >&2
    exit 1
fi
