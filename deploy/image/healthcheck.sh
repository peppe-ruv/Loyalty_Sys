#!/bin/sh

ROLE="${LH_ROLE:-all}"

if [ "$ROLE" = "hub" ]; then
    wget -qO- http://localhost:8080/actuator/health | grep '"status":"UP"' || exit 1
elif [ "$ROLE" = "web" ]; then
    # Il web e' sano se risponde: /api/demo/status risponde sempre (200) con lo stato aggregato
    # ("UP"/"DOWN"/"SLEEPING"), anche quando l'hub non e' raggiungibile.
    wget -qO- http://localhost:3000/api/demo/status | grep -q '"checkedAt"' || exit 1
elif [ "$ROLE" = "all" ]; then
    wget -qO- http://localhost:8080/actuator/health | grep '"status":"UP"' || exit 1
    wget -qO- http://localhost:3000/api/demo/status | grep -q '"checkedAt"' || exit 1
else
    exit 0
fi