#!/bin/bash
# backup.sh - tar.gz the worlds (server/lobby) and plugin data (no jars) into $NAJURS_BACKUP_DIR (default: <repo>/backups).
# Keeps the newest 7. Safe while running: autosave is paused over RCON and always switched back on.
# Cron example: 0 4 * * * ~/minecraft/tools/backup.sh
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
dir=${NAJURS_BACKUP_DIR:-$root/backups}
mkdir -p "$dir"
if ss -Hltn 'sport = :25565' | grep -q .; then
  trap 'python3 "$root/tools/rcon.py" "save-on" >/dev/null || true' EXIT
  python3 "$root/tools/rcon.py" "save-off" "save-all flush" >/dev/null
fi
out="$dir/najurs-$(date +%Y%m%d-%H%M).tar.gz"
tar -czf "$out" --exclude='*.jar' -C "$root/server" lobby plugins
ls -1t "$dir"/najurs-*.tar.gz | tail -n +8 | xargs -r rm -f
echo "backup: $out ($(du -h "$out" | cut -f1))"
