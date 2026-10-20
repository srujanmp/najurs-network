#!/usr/bin/env python3
"""Tiny RCON client: rcon.py 'cmd1' 'cmd2' ...  or pipe one command per line on stdin.
Port and password come from server/server.properties (written by tools/najurs.py setup)."""
import os, socket, struct, sys

HERE = os.path.dirname(os.path.abspath(__file__))

def settings():
    props = {}
    try:
        for line in open(os.path.join(HERE, "..", "server", "server.properties"), encoding="utf-8"):
            if "=" in line and not line.startswith("#"):
                k, v = line.rstrip("\n").split("=", 1)
                props[k] = v
    except OSError:
        pass
    pw = props.get("rcon.password") or open(os.path.join(HERE, ".rcon-pass")).read().strip()
    return int(props.get("rcon.port") or 25575), pw

def packet(s, rid, kind, body):
    data = struct.pack("<ii", rid, kind) + body.encode() + b"\0\0"
    s.sendall(struct.pack("<i", len(data)) + data)

def read(s):
    n = struct.unpack("<i", s.recv(4, socket.MSG_WAITALL))[0]
    d = s.recv(n, socket.MSG_WAITALL)
    return struct.unpack("<i", d[:4])[0], d[8:-2].decode(errors="replace")

class Rcon:
    """Reusable connection: Rcon().run('list') -> output text."""
    def __init__(self, timeout=60):
        port, pw = settings()
        self.s = socket.create_connection(("127.0.0.1", port), timeout=timeout)
        self.rid = 1
        packet(self.s, self.rid, 3, pw)
        if read(self.s)[0] == -1:
            raise PermissionError("rcon auth failed")

    def run(self, cmd):
        self.rid += 1
        packet(self.s, self.rid, 2, cmd)
        return read(self.s)[1]

    def close(self): self.s.close()

if __name__ == "__main__":
    try:
        r = Rcon()
    except PermissionError as e:
        sys.exit(str(e))
    cmds = sys.argv[1:] or [l.strip() for l in sys.stdin if l.strip() and not l.startswith("#")]
    for c in cmds:
        out = r.run(c)
        if out: print(out)
