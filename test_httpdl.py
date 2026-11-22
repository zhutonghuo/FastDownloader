# -*- coding: utf-8 -*-
"""HTTP 多线程下载引擎自检（本地服务器，离线）"""
import os, sys, time, threading, tempfile, http.server, socketserver, functools
import http_dl

PORT = 28765
DATA = bytes((i * 37 + 11) % 256 for i in range(6 * 1024 * 1024 + 7))  # ~6MB 可校验数据

class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_HEAD(self):
        self.send_response(200)
        self.send_header("Content-Length", str(len(DATA)))
        self.send_header("Accept-Ranges", "bytes")
        self.end_headers()
    def do_GET(self):
        rg = self.headers.get("Range")
        if rg and rg.startswith("bytes="):
            a, b = rg[6:].split("-")
            s = int(a); e = int(b) if b else len(DATA) - 1
            chunk = DATA[s:e+1]
            self.send_response(206)
            self.send_header("Content-Range", "bytes %d-%d/%d" % (s, e, len(DATA)))
            self.send_header("Content-Length", str(len(chunk)))
            self.end_headers()
            for i in range(0, len(chunk), 65536):
                self.wfile.write(chunk[i:i+65536])
                self.wfile.flush()
                time.sleep(0.03)  # 限速：让暂停/断点测试有真实时间窗口
        else:
            self.send_response(200)
            self.send_header("Content-Length", str(len(DATA)))
            self.end_headers()
            for i in range(0, len(DATA), 65536):
                self.wfile.write(DATA[i:i+65536])
                self.wfile.flush()
                time.sleep(0.03)

srv = socketserver.TCPServer(("127.0.0.1", PORT), H)
srv.daemon_threads = True
threading.Thread(target=srv.serve_forever, daemon=True).start()
time.sleep(0.3)

url = "http://127.0.0.1:%d/test.bin" % PORT
ok = 0; fail = 0
def check(name, cond, extra=""):
    global ok, fail
    if cond: ok += 1; print("[OK]  " + name)
    else: fail += 1; print("[FAIL] " + name + " " + extra)

# 1 probe
fn, total, rng, final = http_dl.probe(url)
check("probe 文件名", fn == "test.bin", fn)
check("probe 总大小", total == len(DATA), str(total))
check("probe 支持 Range", rng is True)

# 2 8线程下载字节一致
out = os.path.join(tempfile.gettempdir(), "zdl_test_8.bin")
for f in (out, out + ".part", out + ".partmeta"):
    if os.path.exists(f): os.remove(f)
finished = {}
t = http_dl.Task(url, out, threads=8,
                 on_done=lambda o, m: finished.setdefault("v", (o, m))).start()
for _ in range(200):
    if "v" in finished: break
    time.sleep(0.05)
check("8线程下载完成", finished.get("v", (False, ""))[0], str(finished))
check("8线程字节一致", os.path.exists(out) and open(out, "rb").read() == DATA)
check("临时文件已清理", not os.path.exists(out + ".part") and not os.path.exists(out + ".partmeta"))

# 3 4线程 + 暂停/继续
out2 = os.path.join(tempfile.gettempdir(), "zdl_test_pause.bin")
for f in (out2, out2 + ".part", out2 + ".partmeta"):
    if os.path.exists(f): os.remove(f)
fin2 = {}
t2 = http_dl.Task(url, out2, threads=4,
                  on_done=lambda o, m: fin2.setdefault("v", (o, m))).start()
for _ in range(100):
    if t2.state == "下载中":
        break
    time.sleep(0.02)
state_before = t2.state
t2.pause()
time.sleep(0.1)
check("暂停状态生效", state_before == "下载中" and t2.state == "暂停", "%s->%s" % (state_before, t2.state))
paused_done = t2.done
time.sleep(0.3)
check("暂停后进度不再增长", abs(t2.done - paused_done) < 65536 * 8, "%d vs %d" % (paused_done, t2.done))
t2.resume()
for _ in range(200):
    if "v" in fin2: break
    time.sleep(0.05)
check("恢复后下载完成", fin2.get("v", (False, ""))[0], str(fin2))
check("暂停恢复后字节一致", os.path.exists(out2) and open(out2, "rb").read() == DATA)

# 4 断点续传：手动构造已有 meta（下载一半），再续
out3 = os.path.join(tempfile.gettempdir(), "zdl_test_resume.bin")
for f in (out3, out3 + ".part", out3 + ".partmeta"):
    if os.path.exists(f): os.remove(f)
fin3 = {}
t3 = http_dl.Task(url, out3, threads=8,
                  on_done=lambda o, m: fin3.setdefault("v", (o, m))).start()
# 等分片下载真正写入一部分进度（确保断点有内容）
for _ in range(200):
    if os.path.exists(out3 + ".partmeta") and t3.done > 1024 * 1024:
        break
    time.sleep(0.02)
time.sleep(0.1)
t3.cancel()
time.sleep(0.3)
resume_bytes = os.path.getsize(out3 + ".partmeta") if os.path.exists(out3 + ".partmeta") else 0
check("取消后保留断点元数据", resume_bytes > 0)
fin4 = {}
t4 = http_dl.Task(url, out3, threads=8,
                  on_done=lambda o, m: fin4.setdefault("v", (o, m))).start()
for _ in range(200):
    if "v" in fin4: break
    time.sleep(0.05)
check("断点续传完成", fin4.get("v", (False, ""))[0], str(fin4))
check("断点续传字节一致", os.path.exists(out3) and open(out3, "rb").read() == DATA)

srv.shutdown()
print("\n=== 结果: %d 通过 / %d 失败 ===" % (ok, fail))
sys.exit(0 if fail == 0 else 1)
