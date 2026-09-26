#!/bin/sh

set -e

# Carica secrets da variabili *_FILE
for var in $(env | grep '_FILE=' | awk -F= '{print $1}'); do
    var_name="${var%_FILE}"
    var_file=$(eval echo "\$$var")
    if [ -f "$var_file" ]; then
        export "$var_name"="$(cat "$var_file")"
    fi
done

if [ "$LH_MODE" = "embedded" ]; then
    echo "Errore: la modalita embedded non e' ancora disponibile (in arrivo con M12.1). Usa LH_MODE=external."
    exit 1
fi

if [ "$LH_MODE" != "external" ]; then
    echo "Errore: LH_MODE deve essere 'external'"
    exit 1
fi

ROLE="${LH_ROLE:-all}"

if [ "$ROLE" = "cms" ] || [ "$ROLE" = "idp" ]; then
    echo "Errore: ruolo non ancora disponibile ($ROLE)."
    exit 1
fi

if [ "$ROLE" = "hub" ]; then
    PROFILE="${LH_PROFILE:-demo}"
    export SPRING_PROFILES_ACTIVE="${PROFILE}"
    if [ -n "$LH_SERVICES" ]; then
        export SPRING_PROFILES_ACTIVE="$SPRING_PROFILES_ACTIVE,$LH_SERVICES"
    fi
    exec java -jar /opt/lh/hub/hub.jar
elif [ "$ROLE" = "jobs" ]; then
    echo "Avvio ruolo jobs..."
    PROFILE="${LH_PROFILE:-demo}"
    export SPRING_PROFILES_ACTIVE="${PROFILE}"
    export LH_JOBS_ENABLED=true
    if [ -n "$LH_SERVICES" ]; then
        export SPRING_PROFILES_ACTIVE="$SPRING_PROFILES_ACTIVE,$LH_SERVICES"
    fi
    exec java -jar /opt/lh/hub/hub.jar
elif [ "$ROLE" = "web" ]; then
    export NODE_ENV="production"
    export PORT="3000"
    export HOSTNAME="0.0.0.0"
    exec node /opt/lh/web/server.js
elif [ "$ROLE" = "all" ]; then
    echo "Avvio ruoli all sotto s6-overlay..."
    rm -f /etc/services.d/hub/down /etc/services.d/web/down
    exec /init
else
    echo "Errore: LH_ROLE sconosciuto: $ROLE"
    exit 1
fi