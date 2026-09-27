# -*- coding: utf-8 -*-
# 临时服务：静态托管 design 目录 + 接收页面 POST 保存 SVG 文件（生成 Figma 可导入的矢量稿）
import os
import re
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote

ROOT = r"D:\work\android\aidemo\commonApp\design"
OUT = os.path.join(ROOT, "figma")
os.makedirs(OUT, exist_ok=True)


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=ROOT, **kwargs)

    def do_POST(self):
        name = unquote(self.path.rstrip("/").split("/")[-1])
        if not re.match(r"^[a-z0-9-]+\.svg$", name):
            self.send_response(400)
            self.end_headers()
            self.wfile.write(b"bad name")
            return
        length = int(self.headers.get("Content-Length", 0))
        data = self.rfile.read(length)
        with open(os.path.join(OUT, name), "wb") as f:
            f.write(data)
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.end_headers()
        self.wfile.write(("saved " + name + " " + str(len(data))).encode("utf-8"))

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 8138), Handler).serve_forever()
