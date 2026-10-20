#!/bin/bash
# panel.sh start|stop|restart - control the Minecraft instance through MCSManager (keeps auto-restart sane).
set -euo pipefail
U=http://127.0.0.1:23333; H='X-Requested-With: XMLHttpRequest'; J=$(mktemp)
trap 'rm -f "$J"' EXIT
USER=$(sed -n 's/^username: //p' ~/minecraft/PANEL-LOGIN.txt); PASS=$(sed -n 's/^password: //p' ~/minecraft/PANEL-LOGIN.txt)
curl -sf -c "$J" -b "$J" -H "$H" -H 'Content-Type: application/json' -d "{\"username\":\"$USER\",\"password\":\"$PASS\"}" "$U/api/auth/login" >/dev/null
T=$(curl -sf -b "$J" -H "$H" "$U/api/auth/token" | python3 -c "import json,sys;print(json.load(sys.stdin)['data'])")
DID=$(basename ~/.local/share/mcsmanager/web/data/RemoteServiceConfig/*.json .json)
IID=$(grep -l '"minecraft/java"' ~/.local/share/mcsmanager/daemon/data/InstanceConfig/*.json | head -1 | xargs basename | sed 's/.json$//')
case "$1" in start) a=open;; stop) a=stop;; restart) a=restart;; *) echo "usage: panel.sh start|stop|restart" >&2; exit 2;; esac
curl -sf -b "$J" -H "$H" -X POST "$U/api/protected_instance/$a?uuid=$IID&daemonId=$DID&token=$T" >/dev/null && echo "panel: $1 sent"
