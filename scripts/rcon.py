#!/usr/bin/env python3
"""MapEdit 测试服 RCON 控制台驱动：python scripts/rcon.py "命令..."

连接参数可用环境变量覆盖（默认值对应 run-paper 本地测试服）：
  MAPEDIT_RCON_HOST / MAPEDIT_RCON_PORT / MAPEDIT_RCON_PASSWD
"""
import os
import socket
import struct
import sys

HOST = os.environ.get("MAPEDIT_RCON_HOST", "127.0.0.1")
PORT = int(os.environ.get("MAPEDIT_RCON_PORT", "25575"))
PASSWD = os.environ.get("MAPEDIT_RCON_PASSWD", "mapeditdev")


def packet(req_id: int, ptype: int, body: bytes) -> bytes:
    payload = struct.pack("<ii", req_id, ptype) + body + b"\x00\x00"
    return struct.pack("<i", len(payload)) + payload


def main() -> None:
    cmd = " ".join(sys.argv[1:])
    s = socket.create_connection((HOST, PORT), timeout=5)
    s.sendall(packet(1, 3, PASSWD.encode()))
    auth = s.recv(4096)
    if len(auth) < 10:
        sys.exit("rcon 认证失败")
    s.sendall(packet(2, 2, cmd.encode()))
    out = bytearray()
    try:
        while True:
            chunk = s.recv(65536)
            if not chunk:
                break
            out += chunk
    except socket.timeout:
        pass
    finally:
        s.close()
    sys.stdout.write(out.decode("utf-8", errors="replace").replace("\x00", "").strip())
    print()


if __name__ == "__main__":
    main()
