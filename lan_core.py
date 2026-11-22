# -*- coding: utf-8 -*-
"""
极速下载器 Windows 版 —— 局域网互传协议核心
完整复刻安卓版 LanHub(服务端) + LanClient(客户端) 的 HTTP 协议，
从而与安卓 App / 浏览器访客双向互通。纯 Python 标准库实现。
"""
import os
import re
import io
import json
import time
import uuid
import socket
import random
import threading
import mimetypes
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

VERSION = "1.7"

# ---------------- 工具（对齐安卓 LanUtil / Fmt） ----------------

def local_ipv4():
    """取局域网 IPv4（与安卓 LanUtil.localIpv4 等价）"""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("223.5.5.5", 80))  # 仅选路，不发包
        ip = s.getsockname()[0]
        s.close()
        if ip and not ip.startswith("127."):
            return ip
    except Exception:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127."):
                return ip
    except Exception:
        pass
    return "127.0.0.1"

def pick_port():
    for _ in range(120):
        p = 20000 + random.randint(0, 39999)
        s = socket.socket()
        try:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(("0.0.0.0", p))
            return p
        except Exception:
            pass
        finally:
            s.close()
    return 0

def random_code(length, port):
    """数字口令：首位非0、不等于端口、非全相同（对齐安卓 LanUtil.randomCode）"""
    c = ""
    for _ in range(300):
        c = "".join(str(random.randint(0, 9)) for _ in range(length))
        if c[0] == "0":
            continue
        if c == str(port):
            continue
        if len(set(c)) == 1:
            continue
        return c
    return c

def safe_name(name):
    name = os.path.basename(str(name or ""))
    name = re.sub(r'[\\/:*?"<>|\r\n\t]', "_", name).strip(" .")
    return (name or "file")[:180]

def mime(name):
    m, _ = mimetypes.guess_type(name)
    return m or "application/octet-stream"

def enc(s):
    return urllib.parse.quote(str(s if s is not None else ""), safe="")

def dec(s):
    try:
        return urllib.parse.unquote(str(s if s is not None else ""))
    except Exception:
        return str(s or "")

def enc_hdr(s):
    return urllib.parse.quote(str(s or ""), safe="")

def rid(n=12):
    return uuid.uuid4().hex[:n]

def media_class(name):
    d = (name or "").rfind(".")
    if d < 0:
        return "other"
    e = name[d + 1:].lower()
    if e in ("jpg","jpeg","png","gif","webp","bmp","heic","heif","wbmp"): return "image"
    if e in ("mp4","m4v","mov","avi","mkv","webm","3gp","3gpp","flv","wmv"): return "video"
    if e in ("mp3","wav","ogg","oga","m4a","aac","flac","wma","amr","opus"): return "audio"
    return "other"

def fmt_size(b):
    try:
        b = float(b)
    except Exception:
        return "未知"
    if b <= 0:
        return "0 B"
    u = ["B", "KB", "MB", "GB", "TB"]
    i = 0
    while b >= 1024 and i < len(u) - 1:
        b /= 1024; i += 1
    return (str(int(b)) if b >= 100 else ("%.1f" % b if b >= 10 else "%.2f" % b)) + " " + u[i]

# ---------------- 数据模型 ----------------

class RoomFile:
    def __init__(self, fid, name, from_id, from_name, size, path):
        self.id, self.name, self.fromId, self.fromName = fid, name, from_id, from_name
        self.size, self.time, self.path = size, int(time.time() * 1000), path

class ChatMsg:
    def __init__(self, mid, kind, from_id, from_name, text, file_id, file_name, file_size):
        self.id, self.kind, self.fromId, self.fromName = mid, kind, from_id, from_name
        self.text, self.fileId, self.fileName, self.fileSize = text, file_id, file_name, file_size
        self.time = int(time.time() * 1000)
    def as_dict(self):
        return {"id": self.id, "kind": self.kind, "fromId": self.fromId,
                "fromName": self.fromName, "text": self.text, "fileId": self.fileId,
                "fileName": self.fileName, "fileSize": self.fileSize, "time": self.time}

# ---------------- 服务端（复刻 LanHub，供安卓/浏览器加入） ----------------

class LanHub:
    def __init__(self, code_len=4, page_bytes=None, storage_dir=None):
        self.port = pick_port()
        if not self.port:
            raise IOError("没有可用端口")
        self.code = random_code(code_len, self.port)
        self.ip = local_ipv4()
        self.self_id = rid(10)
        self.dir = storage_dir or os.path.join(os.path.expanduser("~"), ".zdown_lanroom")
        self.files = []
        self.msgs = []
        self.peers = {}
        self.page = page_bytes or b"<h1>page missing</h1>"
        self.lock = threading.RLock()
        self.server = None
        self.thread = None
        self.running = False
        self.last_error = ""
        os.makedirs(self.dir, exist_ok=True)

    def url(self):
        return "http://%s:%d" % (self.ip, self.port)

    def start(self):
        hub = self
        class H(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"
            def log_message(self, *a):
                pass
            def do_OPTIONS(self):
                self.send_response(204)
                self._cors()
                self.send_header("Content-Length", "0")
                self.end_headers()
            def do_GET(self):
                H._route(hub, self, False)
            def do_POST(self):
                H._route(hub, self, True)
            def _cors(self):
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                self.send_header("Access-Control-Allow-Headers", "*")
            def _send(self, code, ctype, body, extra=None):
                self.send_response(code)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Connection", "close")
                self.send_header("Cache-Control", "no-store")
                self._cors()
                if extra:
                    for k, v in extra.items():
                        self.send_header(k, v)
                self.end_headers()
                if body:
                    try:
                        self.wfile.write(body)
                    except Exception:
                        pass
            def _json(self, code, obj):
                self._send(code, "application/json; charset=UTF-8",
                           json.dumps(obj, ensure_ascii=False).encode("utf-8"))
            def _body(self):
                te = self.headers.get("transfer-encoding", "")
                if "chunked" in te.lower():
                    out = bytearray()
                    while True:
                        line = self.rfile.readline().strip()
                        if not line:
                            continue
                        try:
                            n = int(line.split(b";")[0].strip(), 16)
                        except Exception:
                            break
                        if n == 0:
                            while True:
                                t = self.rfile.readline()
                                if not t or t.strip() == b"":
                                    break
                            break
                        out += self.rfile.read(n)
                        self.rfile.read(2)
                    return bytes(out)
                cl = self.headers.get("content-length", "0")
                try:
                    n = int(cl)
                except Exception:
                    n = 0
                return self.rfile.read(n) if n > 0 else b""
            def _form(self):
                s = self._body().decode("utf-8", "replace")
                m = {}
                for p in s.split("&"):
                    if "=" in p:
                        k, v = p.split("=", 1)
                        m[dec(k)] = dec(v)
                return m

            @staticmethod
            def _route(hub, self, is_post):
                try:
                    raw = self.path
                    u = urllib.parse.urlsplit(raw)
                    path = u.path or "/"
                    q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query, keep_blank_values=True).items()}
                    if path in ("/", "/index.html"):
                        self._send(200, "text/html; charset=UTF-8", hub.page)
                        return
                    if path == "/api/ping":
                        self._json(200, {"ok": True, "name": "极速下载器", "port": hub.port})
                        return
                    if q.get("code") != hub.code:
                        self._json(403, {"ok": False, "msg": "口令错误"})
                        return
                    who = (q.get("name") or "").strip()
                    if who:
                        with hub.lock:
                            hub.peers[who] = time.time()
                    if path == "/api/join":
                        self._json(200, {"ok": True, "msg": "已加入房间", "ip": hub.ip,
                                         "port": hub.port, "code": hub.code})
                    elif path == "/api/list":
                        with hub.lock:
                            fa = [{"id": f.id, "name": f.name, "size": f.size, "time": f.time,
                                   "from": f.fromName, "fromId": f.fromId} for f in hub.files]
                            now = time.time()
                            for k in list(hub.peers):
                                if now - hub.peers[k] > 90:
                                    hub.peers.pop(k, None)
                            pa = sorted(hub.peers.keys())
                        self._json(200, {"ok": True, "files": fa, "peers": pa,
                                         "code": hub.code, "port": hub.port})
                    elif path == "/api/chat":
                        if is_post:
                            f = self._form()
                            # 兼容安卓/网页两种传法：me/name 可能在 body，也可能只在 query
                            me = f.get("me") or f.get("from") or q.get("me") or q.get("from") or "anon"
                            nm = f.get("name") or q.get("name") or "匿名"
                            text = f.get("text") or ""
                            if not text.strip():
                                self._json(400, {"ok": False, "msg": "内容为空"})
                            else:
                                hub.send_text(me, nm, text)
                                self._json(200, {"ok": True})
                        else:
                            with hub.lock:
                                msgs = [m.as_dict() for m in hub.msgs]
                            self._json(200, {"ok": True, "msgs": msgs})
                    elif path == "/api/upload":
                        name = safe_name(dec(q.get("filename") or ""))
                        if name == "download":
                            name = "file_" + str(int(time.time() * 1000))
                        me = q.get("me") or q.get("from") or "anon"
                        disp = q.get("name") or "匿名"
                        data = self._body()
                        rf = hub.store(data, name, me, disp)
                        if rf is None or rf.size == 0:
                            if rf and os.path.exists(rf.path):
                                os.remove(rf.path)
                            self._json(400, {"ok": False, "msg": "空文件"})
                        else:
                            with hub.lock:
                                hub.files.append(rf)
                                hub.msgs.append(ChatMsg(rid(), "file", me, disp, "", rf.id, rf.name, rf.size))
                            self._json(200, {"ok": True, "id": rf.id, "name": rf.name})
                    elif path == "/api/download":
                        fid = q.get("id")
                        with hub.lock:
                            rf = next((f for f in hub.files if f.id == fid), None)
                        if not rf or not os.path.exists(rf.path):
                            self._json(404, {"ok": False, "msg": "文件不存在"})
                            return
                        size = os.path.getsize(rf.path)
                        inline = q.get("inline") == "1"
                        disp = ("inline" if inline else "attachment") + "; filename*=UTF-8''" + enc_hdr(rf.name)
                        self.send_response(200)
                        self.send_header("Content-Type", mime(rf.name))
                        self.send_header("Content-Length", str(size))
                        self.send_header("Connection", "close")
                        self.send_header("Cache-Control", "no-store")
                        self.send_header("Access-Control-Allow-Origin", "*")
                        self.send_header("Content-Disposition", disp)
                        self.end_headers()
                        with open(rf.path, "rb") as fh:
                            while True:
                                b = fh.read(65536)
                                if not b:
                                    break
                                self.wfile.write(b)
                    elif path == "/api/qr":
                        try:
                            import qrcode
                            img = qrcode.make(hub.url() + "/?code=" + hub.code, box_size=8, border=2)
                            bio = io.BytesIO()
                            img.save(bio, format="PNG")
                            self._send(200, "image/png", bio.getvalue(),
                                       {"Content-Disposition": 'inline; filename="lan-room.png"'})
                        except Exception as e:
                            hub.last_error = str(e)
                            self._json(500, {"ok": False, "msg": "二维码生成失败"})
                    elif path == "/api/recall":
                        f = self._form()
                        ok = hub.recall(f.get("me"), f.get("id"))
                        self._json(200 if ok else 400, {"ok": ok, "msg": "已撤回" if ok else "只能撤回自己发送的内容"})
                    else:
                        self._json(404, {"ok": False, "msg": "不存在"})
                except Exception as e:
                    hub.last_error = str(e)

        srv = ThreadingHTTPServer(("0.0.0.0", self.port), H)
        srv.daemon_threads = True
        self.server = srv
        self.running = True
        self.thread = threading.Thread(target=srv.serve_forever, daemon=True, name="lan-hub-win")
        self.thread.start()

    def store(self, data, name, from_id, from_name):
        fid = rid()
        path = os.path.join(self.dir, fid + "_" + name)
        with open(path, "wb") as f:
            f.write(data)
        return RoomFile(fid, name, from_id, from_name, len(data), path)

    def add_local_file(self, path, from_id=None, from_name="本机"):
        with open(path, "rb") as fh:
            data = fh.read()
        name = safe_name(os.path.basename(path))
        rf = self.store(data, name, from_id or self.self_id, from_name)
        with self.lock:
            self.files.append(rf)
            self.msgs.append(ChatMsg(rid(), "file", from_id or self.self_id, from_name, "", rf.id, rf.name, rf.size))
        return rf

    def send_text(self, from_id, from_name, text):
        text = (text or "")[:4000]
        m = ChatMsg(rid(), "text", from_id, from_name, text, None, None, 0)
        with self.lock:
            self.msgs.append(m)
        return m

    def recall(self, me, msg_id):
        with self.lock:
            target = next((m for m in self.msgs if m.id == msg_id), None)
            if not target or target.fromId != me:
                return False
            self.msgs.remove(target)
            if target.kind == "file" and target.fileId:
                rf = next((f for f in self.files if f.id == target.fileId), None)
                if rf:
                    self.files.remove(rf)
                    try:
                        os.remove(rf.path)
                    except Exception:
                        pass
            return True

    def chat_json(self):
        with self.lock:
            return [m.as_dict() for m in self.msgs]

    def stop(self):
        self.running = False
        if self.server:
            try:
                self.server.shutdown()
                self.server.server_close()
            except Exception:
                pass
        with self.lock:
            for f in self.files:
                try:
                    os.remove(f.path)
                except Exception:
                    pass
            self.files.clear(); self.msgs.clear(); self.peers.clear()
        try:
            import shutil
            shutil.rmtree(self.dir, ignore_errors=True)
        except Exception:
            pass

# ---------------- 客户端（复刻 LanClient，加入安卓房间） ----------------

class Result:
    def __init__(self, ok=False, msg=""):
        self.ok, self.msg = ok, msg
        self.msgs, self.files, self.peers = [], [], []
        self.ip, self.port = "", 0

def _open(url, method="GET", timeout=10):
    req = urllib.request.Request(url, method=method)
    return urllib.request.urlopen(req, timeout=timeout)

def _http_json(base, path, params=None, method="GET", body=None, ctype=None, timeout=12):
    base = (base or "").strip().rstrip("/")
    if not base.startswith("http://") and not base.startswith("https://"):
        base = "http://" + base
    url = base + path
    if params:
        url += "?" + "&".join(enc(k) + "=" + enc(v) for k, v in params.items())
    data = None
    headers = {}
    if body is not None:
        data = body if isinstance(body, bytes) else body.encode("utf-8")
    if ctype:
        headers["Content-Type"] = ctype
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read().decode("utf-8", "replace")
    return json.loads(raw)

class LanClient:
    def __init__(self, base, code, me, name):
        self.base = base.strip().rstrip("/")
        if not self.base.startswith("http"):
            self.base = "http://" + self.base
        self.code, self.me, self.name = code, me, name

    def join(self):
        r = Result()
        try:
            d = _http_json(self.base, "/api/join", {"code": self.code, "name": self.name})
            r.ok = bool(d.get("ok")); r.msg = d.get("msg", "" if r.ok else "口令错误")
            r.ip, r.port = d.get("ip", ""), d.get("port", 0)
        except Exception as e:
            r.msg = "连接失败：" + str(e)[:80]
        return r

    def chat(self):
        r = Result()
        try:
            d = _http_json(self.base, "/api/chat", {"code": self.code, "name": self.name, "me": self.me})
            r.ok = bool(d.get("ok")); r.msgs = d.get("msgs", []) if r.ok else []
            if not r.ok:
                r.msg = d.get("msg", "口令失效")
        except Exception as e:
            r.msg = "断开了：" + str(e)[:80]
        return r

    def send_text(self, text):
        r = Result()
        try:
            # me/name 同时放 body（兼容安卓从 body 读）和 query（兼容网页从 query 读）
            body = "text=" + enc(text) + "&me=" + enc(self.me) + "&name=" + enc(self.name)
            d = _http_json(self.base, "/api/chat", {"code": self.code, "me": self.me, "name": self.name},
                           method="POST", body=body, ctype="application/x-www-form-urlencoded")
            r.ok = bool(d.get("ok")); r.msg = d.get("msg", "")
        except Exception as e:
            r.msg = "发送失败：" + str(e)[:80]
        return r

    def upload(self, file_path, rel_name=None, progress=None):
        r = Result()
        name = rel_name or os.path.basename(file_path)
        try:
            with open(file_path, "rb") as fh:
                data = fh.read()
            d = _http_json(self.base, "/api/upload",
                           {"code": self.code, "me": self.me, "name": self.name, "filename": name},
                           method="POST", body=data, ctype="application/octet-stream", timeout=120)
            r.ok = bool(d.get("ok")); r.msg = d.get("msg", "")
        except Exception as e:
            r.msg = "上传失败：" + str(e)[:80]
        return r

    def recall(self, msg_id):
        r = Result()
        try:
            body = "me=" + enc(self.me) + "&id=" + enc(msg_id)
            d = _http_json(self.base, "/api/recall", {"code": self.code}, method="POST",
                           body=body, ctype="application/x-www-form-urlencoded")
            r.ok = bool(d.get("ok")); r.msg = d.get("msg", "")
        except Exception as e:
            r.msg = "撤回失败：" + str(e)[:80]
        return r

    def download(self, file_id, inline=False):
        url = self.base + "/api/download?" + "&".join(
            enc(k) + "=" + enc(v) for k, v in
            {"code": self.code, "id": file_id, "inline": "1" if inline else "0"}.items())
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.read()
