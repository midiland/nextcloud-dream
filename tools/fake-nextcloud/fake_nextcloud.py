"""Faux serveur Nextcloud minimal pour tester l'économiseur sur un émulateur.

PROPFIND Depth 1, GET/HEAD avec Range, aperçus (core/preview, fichier servi tel quel),
Basic auth. Chaque requête est journalisée sur stderr (sans l'en-tête Authorization) :
la colonne entre crochets donne le client (okhttp = l'application, stagefright = le
lecteur vidéo d'Android).

Identifiants : test / test-password. Dossier : /Photos/ScreenSaver (valeur par défaut
de l'application). Un seul niveau, pas de sous-dossiers, pas de Live Photos par fileid.
Écoute sur 127.0.0.1 seulement : l'émulateur le joint par `adb reverse tcp:8080 tcp:8080`.

Usage : python3 -I fake_nextcloud.py <dossier_des_fichiers> [port]
Normalement lancé par run_emulator_test.sh (voir docs/TESTING_GUIDE.md).
"""
import base64, hashlib, os, re, sys, urllib.parse
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

ROOT = os.path.abspath(sys.argv[1])
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8080
USER, PASSWORD = "test", "test-password"
FOLDER = "/Photos/ScreenSaver"
DAV_PREFIX = f"/remote.php/dav/files/{USER}{FOLDER}/"
MIME = {".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".mp4": "video/mp4", ".mov": "video/quicktime"}


def files():
    return sorted(f for f in os.listdir(ROOT) if os.path.splitext(f)[1].lower() in MIME)


def file_id(name):
    return 1000 + files().index(name)


def etag(path):
    st = os.stat(path)
    return hashlib.md5(f"{path}{st.st_size}{st.st_mtime}".encode()).hexdigest()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        ua = self.headers.get("User-Agent", "")[:30]
        rng = self.headers.get("Range", "")
        sys.stderr.write(f"{self.command} {self.path} {rng} -> {args[1] if len(args) > 1 else ''} [{ua}]\n")
        sys.stderr.flush()

    def authorized(self):
        expected = "Basic " + base64.b64encode(f"{USER}:{PASSWORD}".encode()).decode()
        if self.headers.get("Authorization") == expected:
            return True
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="Nextcloud"')
        self.send_header("Content-Length", "0")
        self.end_headers()
        return False

    def drain(self):
        length = int(self.headers.get("Content-Length", 0) or 0)
        if length:
            self.rfile.read(length)

    def do_PROPFIND(self):
        self.drain()
        if not self.authorized():
            return
        path = urllib.parse.urlparse(self.path).path
        if path.rstrip("/") + "/" != DAV_PREFIX:
            self.send_response(404); self.send_header("Content-Length", "0"); self.end_headers(); return
        parts = [f"<d:response><d:href>{DAV_PREFIX}</d:href><d:propstat><d:prop>"
                 "<d:resourcetype><d:collection/></d:resourcetype></d:prop>"
                 "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"]
        for name in files():
            full = os.path.join(ROOT, name)
            parts.append(
                f"<d:response><d:href>{DAV_PREFIX}{urllib.parse.quote(name)}</d:href><d:propstat><d:prop>"
                f"<d:resourcetype/><d:getetag>\"{etag(full)}\"</d:getetag>"
                f"<d:getcontentlength>{os.path.getsize(full)}</d:getcontentlength>"
                f"<oc:fileid>{file_id(name)}</oc:fileid></d:prop>"
                "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
        body = ('<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" '
                'xmlns:nc="http://nextcloud.org/ns">' + "".join(parts) + "</d:multistatus>").encode()
        self.send_response(207)
        self.send_header("Content-Type", "application/xml; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def resolve(self):
        url = urllib.parse.urlparse(self.path)
        if url.path == "/index.php/core/preview":
            fid = int(urllib.parse.parse_qs(url.query).get("fileId", ["0"])[0])
            names = [n for n in files() if file_id(n) == fid and n.lower().endswith((".jpg", ".jpeg"))]
            return os.path.join(ROOT, names[0]) if names else None
        if url.path.startswith(DAV_PREFIX):
            name = urllib.parse.unquote(url.path[len(DAV_PREFIX):])
            full = os.path.join(ROOT, name)
            if "/" not in name and os.path.isfile(full):
                return full
        return None

    def serve(self, head):
        if not self.authorized():
            return
        full = self.resolve()
        if full is None:
            self.send_response(404); self.send_header("Content-Length", "0"); self.end_headers(); return
        size = os.path.getsize(full)
        start, end, status = 0, size - 1, 200
        m = re.match(r"bytes=(\d*)-(\d*)", self.headers.get("Range", ""))
        if m:
            if m.group(1):
                start = int(m.group(1))
                end = min(int(m.group(2)), size - 1) if m.group(2) else size - 1
            else:  # suffixe : bytes=-N
                start = max(0, size - int(m.group(2)))
            if start >= size:
                self.send_response(416); self.send_header("Content-Range", f"bytes */{size}")
                self.send_header("Content-Length", "0"); self.end_headers(); return
            status = 206
        self.send_response(status)
        self.send_header("Content-Type", MIME.get(os.path.splitext(full)[1].lower(), "application/octet-stream"))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("ETag", f'"{etag(full)}"')
        self.send_header("Content-Length", str(end - start + 1))
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        if head:
            return
        try:
            with open(full, "rb") as f:
                f.seek(start)
                remaining = end - start + 1
                while remaining > 0:
                    chunk = f.read(min(65536, remaining))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    remaining -= len(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass  # le lecteur vidéo coupe souvent ses connexions

    def do_GET(self):
        self.serve(head=False)

    def do_HEAD(self):
        self.serve(head=True)


if __name__ == "__main__":
    print(f"Faux Nextcloud sur :{PORT}, dossier {FOLDER}, {len(files())} fichier(s)", flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
