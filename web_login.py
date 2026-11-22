# -*- coding: utf-8 -*-
import re
import os
import traceback
from PyQt5.QtCore import QUrl, pyqtSignal
from PyQt5.QtGui import QIcon
from PyQt5.QtNetwork import QNetworkCookie
from PyQt5.QtWidgets import (
    QDialog, QVBoxLayout, QHBoxLayout, QLabel, QPushButton, QLineEdit, QFrame,
)
from PyQt5.QtWebEngineWidgets import QWebEngineView, QWebEngineProfile, QWebEnginePage

LOGIN_URLS = {
    "quark": "https://pan.quark.cn",
    "uc": "https://drive.uc.cn",
    "xunlei": "https://pan.xunlei.com",
    "baidu": "https://pan.baidu.com",
    "c139": "https://yun.139.com",
    "pan123": "https://www.123pan.com",
    "pan115": "https://115.com",
    "guangya": "https://www.guangyapan.com",
    "ilanzou": "https://www.ilanzou.com",
    "lanzou": "https://www.lanzoui.com",
}


def _log(msg):
    try:
        p = os.path.join(os.path.expanduser("~"), ".jisudownloader", "weblogin.log")
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "a", encoding="utf-8") as f:
            f.write(str(msg) + "\n")
    except Exception:
        pass


_warm_view = None


def warm_up():
    global _warm_view
    if _warm_view is not None:
        return
    try:
        _warm_view = QWebEngineView()
        _warm_view.setFixedSize(1, 1)
        _warm_view.load(QUrl("about:blank"))
        _log("warmup done")
    except Exception:
        _log("warmup ERR " + traceback.format_exc())


class WebLoginDialog(QDialog):
    cookies_ready = pyqtSignal(str)

    def __init__(self, platform, parent=None, dark=False):
        super().__init__(parent)
        _log("WebLoginDialog init " + platform)
        self.platform = platform
        self._cookies = {}
        self.setWindowTitle("网盘登录 · " + platform)
        self.resize(1000, 720)
        self.setModal(True)
        v = QVBoxLayout(self)
        v.setContentsMargins(0, 0, 0, 0)
        v.setSpacing(0)

        bar = QFrame(); bar.setFixedHeight(46)
        bl = QHBoxLayout(bar); bl.setContentsMargins(14, 0, 12, 0)
        self.title_lbl = QLabel("登录 %s · 正在加载…" % platform)
        self.title_lbl.setStyleSheet("font-size:14px;font-weight:700;")
        bl.addWidget(self.title_lbl); bl.addStretch(1)
        done = QPushButton("完成登录"); done.setObjectName("primary")
        done.clicked.connect(self._finish); bl.addWidget(done)
        cancel = QPushButton("取消"); cancel.setObjectName("gray")
        cancel.clicked.connect(self.reject); bl.addWidget(cancel)
        v.addWidget(bar)

        self.view = QWebEngineView()
        self.profile = QWebEngineProfile.defaultProfile()
        self.profile.setHttpUserAgent(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
        v.addWidget(self.view, 1)
        _log("profile ready")

        hint = QLabel("提示：在网页里正常登录（扫码 / 账号密码均可）。登录后点右上角「完成登录」，程序会自动提取该站 Cookie。")
        hint.setObjectName("sub"); hint.setWordWrap(True)
        hint.setStyleSheet("padding:6px 14px;color:#7b869c;font-size:12px;")
        v.addWidget(hint)

        cs = self.profile.cookieStore()
        cs.cookieAdded.connect(self._on_cookie)
        self.view.urlChanged.connect(self._on_url)
        self.view.loadProgress.connect(self._on_progress)
        self.view.loadFinished.connect(self._on_loaded)
        _log("cookie store connected")

        try:
            self.view.load(QUrl(LOGIN_URLS.get(platform, "https://www.baidu.com")))
            _log("load called")
        except Exception:
            _log("load ERR " + traceback.format_exc())

    def _on_progress(self, pct):
        self.title_lbl.setText("登录 %s · 加载中 %d%%" % (self.platform, pct))

    def _on_loaded(self, ok):
        self.title_lbl.setText("登录 %s · 完成后点「完成登录」" % self.platform)
        _log("load finished ok=%s" % ok)

    def _on_cookie(self, cookie):
        try:
            name = bytes(cookie.name()).decode("utf-8", "replace")
            value = bytes(cookie.value()).decode("utf-8", "replace")
            if name and value and len(name) < 80 and len(value) < 2000:
                self._cookies[name] = value
        except Exception:
            pass

    def _on_url(self, url):
        pass

    def _finish(self):
        cookie_str = "; ".join("%s=%s" % (k, v) for k, v in self._cookies.items())
        if not cookie_str:
            cookie_str = self._from_storage()
        self.cookies_ready.emit(cookie_str)
        self.accept()

    def _from_storage(self):
        out = {}
        host = (LOGIN_URLS.get(self.platform, "").replace("https://", "").split("/")[0])
        for domain in [host, "." + host]:
            for name, value in self._collect(domain):
                out[name] = value
        return "; ".join("%s=%s" % (k, v) for k, v in out.items())

    def _collect(self, domain):
        return []
