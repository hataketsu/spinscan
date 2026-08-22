#!/usr/bin/env python3
"""Start the web UI on one socket that answers both IPv4 and IPv6.

uvicorn's own --host :: opens the listener with IPV6_V6ONLY set, so a machine
reached by its LAN name (which resolves to AAAA here) works while the same
machine reached by 192.168.x.y is refused. Building the socket here and handing
it over keeps a single process serving both families.
"""
import os
import socket
import sys

import uvicorn

PORT = int(os.environ.get("PORT", "8000"))
ROOT = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, ROOT)

if socket.has_dualstack_ipv6():
    sock = socket.create_server(("::", PORT), family=socket.AF_INET6,
                                dualstack_ipv6=True, backlog=2048, reuse_port=False)
else:
    sock = socket.create_server(("0.0.0.0", PORT), backlog=2048)

config = uvicorn.Config("server:app", log_level="info")
uvicorn.Server(config).run(sockets=[sock])
