# Stand-in for HomeCloud Web, for the end-to-end test only: answers the login API shelld checks credentials against
# (user nathan, password "se&cr et"), and relays /shell (HTTP upgrade) to the shelld unix socket like the real relay does.
import json, socket, threading, os, sys
SOCK = sys.argv[1]
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def send(self, code, obj):
        b = json.dumps(obj, separators=(",",":")).encode(); self.send_response(code); self.send_header("Content-Type","application/json"); self.send_header("Content-Length",str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_GET(self):
        if self.path == "/api/ping": self.send(200, {"app":"HomeCloud"})
        elif self.path == "/api/disks": self.send(200, [{"id":"d1","personal":True,"owner":"nathan"}])
        else: self.send(404, {})
    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0); q = parse_qs(self.rfile.read(n).decode())
        if self.path == "/api/login":
            if q.get("user")==["nathan"] and q.get("pass")==["se&cr et"]: self.send(200, {"token":"t123","admin":False})
            else: self.send(401, {"error":"no"})
        else: self.send(200, {})
threading.Thread(target=ThreadingHTTPServer(("127.0.0.1",18680),H).serve_forever, daemon=True).start()

# relay: what HomeCloud Web does for /shell: HTTP upgrade, then frames piped to the unix socket (after a F_PEER frame)
def frame(t, p): return bytes([t]) + len(p).to_bytes(4,"big") + p
def pipe(a, b):
    try:
        while True:
            d = a.recv(65536)
            if not d: break
            b.sendall(d)
    except OSError: pass
    for s in (a,b):
        try: s.shutdown(socket.SHUT_RDWR)
        except OSError: pass
def handle(c):
    head = b""
    while not head.endswith(b"\r\n\r\n"):
        ch = c.recv(1)
        if not ch: return
        head += ch
    if b"GET /shell" not in head or b"Upgrade: hcshell" not in head: c.sendall(b"HTTP/1.1 404 Not Found\r\n\r\n"); c.close(); return
    u = socket.socket(socket.AF_UNIX); u.connect(SOCK)
    u.sendall(frame(ord("I"), b"192.168.1.50"))
    c.sendall(b"HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: hcshell\r\n\r\n")
    threading.Thread(target=pipe, args=(c,u), daemon=True).start(); pipe(u,c)
srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); srv.bind(("127.0.0.1",18681)); srv.listen(16)
while True:
    c,_ = srv.accept(); threading.Thread(target=handle, args=(c,), daemon=True).start()
