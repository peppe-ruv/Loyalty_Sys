#!/bin/sh

ROLE="${LH_ROLE:-all}"

if [ "$ROLE" = "hub" ] || [ "$ROLE" = "jobs" ]; then
    wget -qO- http://localhost:8080/actuator/health | grep '"status":"UP"' || exit 1
elif [ "$ROLE" = "web" ]; then
    wget -qO- http://localhost:3000/api/demo/status | grep '"up"' || exit 1
elif [ "$ROLE" = "all" ]; then
    wget -qO- http://localhost:8080/actuator/health | grep '"status":"UP"' || exit 1
    wget -qO- http://localhost:3000/api/demo/status | grep '"up"' || exit 1
else
    exit 0
fi