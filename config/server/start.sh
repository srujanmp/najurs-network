#!/bin/bash
# Entry point used by panels (e.g. MCSManager) that start the server from this folder: same as ../start.sh.
cd "$(dirname "$0")/.."
exec ./start.sh --yes
