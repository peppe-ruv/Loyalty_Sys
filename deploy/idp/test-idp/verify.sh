#!/bin/bash
# Script per verificare l'ottenimento di token dai provider

echo "Verifica broker IdP aziendale..."
echo "Nota: Per ottenere un token reale dal broker è necessario un flusso interattivo OIDC."
echo "Esempio manuale:"
echo "1. Visita http://localhost:8080/realms/loyaltyhub/account/"
echo "2. Clicca su 'Sign In'"
echo "3. Scegli 'IdP Aziendale' dalla lista dei login provider"
echo "4. Autenticati nel realm idp-test (admin/admin o con un utente di test)"
echo ""

echo "Verifica utente LDAP..."
echo "Esempio richiesta token per l'utente LDAP tramite grant_type=password (se abilitato per il client):"
echo "curl -X POST http://localhost:8080/realms/loyaltyhub/protocol/openid-connect/token \\"
echo "  -H 'Content-Type: application/x-www-form-urlencoded' \\"
echo "  -d 'client_id=web' \\"
echo "  -d 'client_secret=<WEB_CLIENT_SECRET>' \\"
echo "  -d 'grant_type=password' \\"
echo "  -d 'username=testuser' \\"
echo "  -d 'password=testpassword'"
