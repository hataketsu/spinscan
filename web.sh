#!/usr/bin/env bash
# Start the web UI on the LAN so a phone can upload photos to this machine.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORT="${PORT:-8000}"

ip="$(ip -4 addr show scope global | grep -oP 'inet \K[\d.]+' | grep -E '^(192\.168|10\.|172\.(1[6-9]|2[0-9]|3[01]))' | head -1)"
echo "COLMAP rig  ->  http://${ip:-localhost}:$PORT"
echo "            ->  http://$(hostname):$PORT"
echo "Mở link đó trên điện thoại (cùng WiFi)."
echo
# serve.py, not uvicorn directly: this host's name resolves to IPv6 on the LAN
# while the web UI is reached by IPv4 address, and one dual-stack socket serves
# both. uvicorn's own --host :: would set IPV6_V6ONLY and refuse the IPv4 side.
exec "$HERE/.venv/bin/python" "$HERE/serve.py"
