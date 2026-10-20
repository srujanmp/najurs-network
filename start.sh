#!/bin/bash
# NAJURS NETWORK: one command to install, update and run the server.
#   ./start.sh            first run asks for your Minecraft name and the EULA, then builds everything
#   git pull && ./start.sh  update to the newest version
set -e
cd "$(dirname "$0")"
command -v python3 >/dev/null || { echo "Please install Python 3 first."; exit 1; }
python3 tools/najurs.py setup "$@"
mkdir -p server/logs
exec tools/run.sh
