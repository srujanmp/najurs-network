#!/bin/bash
# Run the server: provisioning starts in the background each boot, and the loop restarts the server when
# provisioning asks for it (e.g. after creating worlds). Memory: MC_MEMORY (default: half your RAM, 2G-6G),
# or put MC_MEMORY=8G in server/local.env.
cd "$(dirname "$0")/../server"
[ -f local.env ] && . ./local.env
JAVA=$(python3 -c "import json;print(json.load(open('.najurs/state.json')).get('jdk') or '')" 2>/dev/null)/java
[ -x "$JAVA" ] || JAVA=java
if [ -z "$MC_MEMORY" ]; then
  kb=$(awk '/MemTotal/{print $2}' /proc/meminfo 2>/dev/null || echo $(( $(sysctl -n hw.memsize 2>/dev/null || echo 8589934592) / 1024 )))
  g=$(( kb / 1024 / 1024 / 2 )); [ $g -lt 2 ] && g=2; [ $g -gt 6 ] && g=6; MC_MEMORY=${g}G
fi
while true; do
  python3 ../tools/najurs.py provision >> logs/provision.log 2>&1 &
  "$JAVA" -Xms1G -Xmx$MC_MEMORY \
    -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions \
    -XX:+DisableExplicitGC -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 \
    -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 \
    -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 \
    -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1 \
    -jar paper.jar --nogui
  code=$?
  wait
  [ -f .najurs/restart ] || exit $code
  rm -f .najurs/restart
  python3 ../tools/najurs.py setup --yes || exit 1   # re-apply world settings for worlds created last boot
done
