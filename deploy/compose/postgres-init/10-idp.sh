#!/bin/sh
# Compose di riferimento (F2-DIST-03, M8.3): ruolo e database propri di Keycloak, come fa CloudNativePG nel chart.
# Gira una sola volta, alla prima inizializzazione del volume di Postgres (docker-entrypoint-initdb.d).
# - `idp` possiede solo il database `idp`: niente privilegi sul database dell'hub né superutente;
# - il database dell'hub non accetta connessioni da altri ruoli (REVOKE CONNECT FROM PUBLIC);
# - senza LH_IDP_DB_PASSWORD l'inizializzazione fallisce e Postgres non parte (fail-closed). La password arriva a psql
#   come variabile e viene citata da psql stesso (:'pw'): nessun valore dentro il testo SQL.
set -eu

if [ -z "${LH_IDP_DB_PASSWORD:-}" ]; then
    echo "Variabile obbligatoria mancante: LH_IDP_DB_PASSWORD (ruolo idp di Keycloak)" >&2
    exit 1
fi

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v pw="$LH_IDP_DB_PASSWORD" -v appdb="$POSTGRES_DB" <<'SQL'
CREATE ROLE idp LOGIN PASSWORD :'pw' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE DATABASE idp OWNER idp;
REVOKE ALL ON DATABASE idp FROM PUBLIC;
REVOKE CONNECT ON DATABASE :"appdb" FROM PUBLIC;
SQL
