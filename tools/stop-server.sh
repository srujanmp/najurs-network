#!/bin/bash
# Save and stop the server over RCON, then wait until the game port is closed.
"$(dirname "$0")/rcon.py" "save-all flush" "stop" >/dev/null 2>&1 || true
for _ in $(seq 120); do ss -Hltn 'sport = :25565' | grep -q . || { echo "server stopped"; exit 0; }; sleep 1; done
echo "server still running after 120s" >&2; exit 1
