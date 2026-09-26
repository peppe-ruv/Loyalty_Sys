#!/bin/sh

set -e

# Carica i segreti dalla convenzione *_FILE (VAR_FILE=/percorso -> VAR=contenuto del file).
# - solo nomi validi che finiscono in _FILE, letti con printenv (niente eval del contenuto);
# - VAR gia' impostata vince sul file;
# - esclusi i *_FILE di sistema: l'immagine base Wolfi imposta SSL_CERT_FILE sul bundle CA (~220 KiB)
#   e trasformarlo in SSL_CERT superava il limite di 128 KiB per variabile di execve
#   ("exec: java: Argument list too long");
# - un file oltre 64 KiB non e' un segreto: si salta con un avviso invece di rompere l'exec.
LH_SECRET_MAX_BYTES=65536
for var in $(env | sed -n 's/^\([A-Za-z_][A-Za-z0-9_]*_FILE\)=.*/\1/p'); do
    case "$var" in
        SSL_CERT_FILE) continue ;;
    esac
    var_name="${var%_FILE}"
    [ -n "$var_name" ] || continue
    printenv "$var_name" >/dev/null 2>&1 && continue
    var_file="$(printenv "$var" 2>/dev/null)" || continue
    [ -f "$var_file" ] || continue
    if [ "$(wc -c < "$var_file" | tr -d ' ')" -gt "$LH_SECRET_MAX_BYTES" ]; then
        echo "Avviso: $var punta a un file oltre $LH_SECRET_MAX_BYTES byte, $var_name non impostata." >&2
        continue
    fi
    export "$var_name=$(cat "$var_file")"
done

# JVM per percorso assoluto (verificato in build dal Dockerfile), senza dipendere dal PATH.
JAVA_BIN="${JAVA_HOME:-/usr/lib/jvm/default-jvm}/bin/java"

LH_MODE="${LH_MODE:-external}"

if [ "$LH_MODE" = "embedded" ]; then
    echo "Errore: la modalita embedded non e' ancora disponibile (in arrivo con M12.1). Usa LH_MODE=external."
    exit 1
fi

if [ "$LH_MODE" != "external" ]; then
    echo "Errore: LH_MODE deve essere 'external'"
    exit 1
fi

if [ -n "$LH_SERVICES" ] && [ "$LH_SERVICES" != "all" ]; then
    echo "Errore: selezione dei moduli non ancora disponibile"
    exit 1
fi

ROLE="${LH_ROLE:-all}"

if [ "$ROLE" = "cms" ] || [ "$ROLE" = "idp" ] || [ "$ROLE" = "jobs" ]; then
    echo "Errore: ruolo non ancora disponibile ($ROLE)."
    exit 1
fi

if [ "$ROLE" = "hub" ]; then
    PROFILE="${LH_PROFILE:-demo}"
    export SPRING_PROFILES_ACTIVE="${PROFILE}"
    exec "$JAVA_BIN" -jar /opt/lh/hub/hub.jar
elif [ "$ROLE" = "web" ]; then
    export NODE_ENV="production"
    export PORT="3000"
    export HOSTNAME="0.0.0.0"
    exec node /opt/lh/web/server.js
elif [ "$ROLE" = "all" ]; then
    echo "Avvio ruoli all sotto s6-svscan..."
    mkdir -p /var/lib/lh/run/services/hub /var/lib/lh/run/services/web
    cp /opt/lh/s6/hub/run /var/lib/lh/run/services/hub/run
    cp /opt/lh/s6/web/run /var/lib/lh/run/services/web/run
    chmod +x /var/lib/lh/run/services/hub/run /var/lib/lh/run/services/web/run
    exec s6-svscan /var/lib/lh/run/services
else
    echo "Errore: LH_ROLE sconosciuto: $ROLE"
    exit 1
fi