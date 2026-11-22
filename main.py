# -*- coding: utf-8 -*-
"""
极速下载器 Windows 版 —— 与安卓版互通的局域网互传工具
纯 PyQt5 原生控件 + 现代 QSS（非 WebView、非 win32 原生丑控件）
支持：开房间 / 加入房间、聊天、文件与文件夹上传、下载、内置预览、仅撤回自己、深浅主题
"""
import os
import sys
import json
import traceback
import time
import uuid
import threading
import webbrowser
import urllib.request

from PyQt5 import QtWebEngineWidgets
from PyQt5.QtCore import Qt, QTimer, QThread, pyqtSignal, QUrl, QPoint, QSize
from PyQt5.QtGui import QFont, QPixmap, QImage, QIcon, QCursor, QPainter, QColor
from PyQt5.QtWidgets import (
    QApplication, QWidget, QLabel, QPushButton, QLineEdit, QVBoxLayout, QHBoxLayout,
    QFrame, QStackedWidget, QScrollArea, QFileDialog, QMessageBox, QProgressBar,
    QDialog, QSizePolicy, QGraphicsDropShadowEffect, QComboBox, QCheckBox, QMenu,
    QListWidget, QListWidgetItem, QAbstractItemView
)
from PyQt5.QtMultimedia import QMediaPlayer, QMediaContent
from PyQt5.QtMultimediaWidgets import QVideoWidget

import lan_core as L
import http_dl
import theme
from bubble import Bubble

APP_VERSION = L.VERSION
ME = uuid.uuid4().hex[:10]
MY_NAME = "Windows-" + ME[:4]

def res_path(name):
    """兼容 pyinstaller 单文件：资源在 sys._MEIPASS"""
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    p = os.path.join(base, name)
    return p if os.path.exists(p) else os.path.join(os.path.dirname(os.path.abspath(__file__)), name)

# ---------------- 自绘确认对话框（页面内风格，非系统原生） ----------------

class ConfirmDialog(QDialog):
    def __init__(self, title, message, dark=False, parent=None):
        super().__init__(parent)
        self.setWindowFlags(Qt.FramelessWindowHint | Qt.Dialog)
        self.setAttribute(Qt.WA_TranslucentBackground)
        self.setModal(True)
        self._dark = dark
        self.result_ok = False
        outer = QVBoxLayout(self)
        outer.setContentsMargins(20, 20, 20, 20)
        card = QFrame()
        card.setObjectName("card")
        card.setMinimumWidth(330)
        self._paint_card(card)
        lay = QVBoxLayout(card)
        lay.setContentsMargins(22, 20, 22, 18)
        lay.setSpacing(12)
        t = QLabel(title); t.setObjectName("h2"); t.setStyleSheet("font-size:16px;font-weight:700;")
        m = QLabel(message); m.setWordWrap(True)
        m.setStyleSheet("color:%s;font-size:13px;" % ("#aeb9cf" if dark else "#55627a"))
        lay.addWidget(t); lay.addWidget(m)
        row = QHBoxLayout(); row.addStretch(1)
        no = QPushButton("取消"); no.setObjectName("gray")
        yes = QPushButton("确认"); yes.setObjectName("primary")
        no.clicked.connect(self._no); yes.clicked.connect(self._yes)
        row.addWidget(no); row.addWidget(yes)
        lay.addLayout(row)
        outer.addWidget(card)

    def _paint_card(self, card):
        bg = "#1e2536" if self._dark else "#ffffff"
        bd = "#2c3547" if self._dark else "#e4e9f2"
        fg = "#e6ebf5" if self._dark else "#1e2536"
        card.setStyleSheet("QFrame{background:%s;border:1px solid %s;border-radius:16px;} QLabel{color:%s;}" % (bg, bd, fg))
        sh = QGraphicsDropShadowEffect(card)
        sh.setBlurRadius(30); sh.setOffset(0, 8); sh.setColor(QColor(0, 0, 0, 120))
        card.setGraphicsEffect(sh)

    def _yes(self):
        self.result_ok = True; self.accept()

    def _no(self):
        self.result_ok = False; self.reject()

    def mousePressEvent(self, e):
        self._drag = e.globalPos() - self.frameGeometry().topLeft()

    def mouseMoveEvent(self, e):
        if e.buttons() & Qt.LeftButton and hasattr(self, "_drag"):
            self.move(e.globalPos() - self._drag)

# ---------------- 内置播放器对话框 ----------------

class PreviewDialog(QDialog):
    def __init__(self, file_path, media, dark=False, parent=None):
        super().__init__(parent)
        self.setWindowTitle("预览 · " + os.path.basename(file_path))
        self.resize(880, 620)
        self._dark = dark
        self.player = None
        lay = QVBoxLayout(self)
        lay.setContentsMargins(12, 12, 12, 12)
        if media == "image":
            pic = QLabel(); pic.setAlignment(Qt.AlignCenter)
            pm = QPixmap(file_path)
            if not pm.isNull():
                pm = pm.scaled(840, 560, Qt.KeepAspectRatio, Qt.SmoothTransformation)
                pic.setPixmap(pm)
            else:
                pic.setText("图片加载失败")
            lay.addWidget(pic)
        elif media in ("video", "audio"):
            if media == "video":
                self.vw = QVideoWidget()
                lay.addWidget(self.vw, 1)
            self.player = QMediaPlayer()
            if media == "video":
                self.player.setVideoOutput(self.vw)
            self.player.setMedia(QMediaContent(QUrl.fromLocalFile(file_path)))
            bar = QHBoxLayout()
            play = QPushButton("播放/暂停")
            play.clicked.connect(self._toggle)
            bar.addWidget(play)
            bar.addStretch(1)
            lay.addLayout(bar)
            self.player.play()
        else:
            lab = QLabel("此类型无法内置预览，请下载后打开"); lay.addWidget(lab)

    def _toggle(self):
        if self.player:
            if self.player.state() == QMediaPlayer.PlayingState:
                self.player.pause()
            else:
                self.player.play()

    def closeEvent(self, e):
        if self.player:
            self.player.stop()
        super().closeEvent(e)

# ---------------- 下载任务卡片 ----------------

class DownloadTaskCard(QFrame):
    def __init__(self, path, name, threads, dark=False, parent=None):
        super().__init__(parent)
        self.path, self.name, self.threads = path, name, threads
        self.finished = False
        self.task = None
        self.setObjectName("card")
        bg = "#1e2536" if dark else "#ffffff"
        bd = "#2c3547" if dark else "#e4e9f2"
        self.setStyleSheet("QFrame#card{background:%s;border:1px solid %s;border-radius:12px;}" % (bg, bd))
        v = QVBoxLayout(self); v.setContentsMargins(12, 10, 12, 10); v.setSpacing(6)

        row = QHBoxLayout()
        self.name_lbl = QLabel("📥 " + name)
        self.name_lbl.setStyleSheet("font-weight:600;font-size:13px;")
        self.name_lbl.setWordWrap(True)
        row.addWidget(self.name_lbl, 1)
        self.state_lbl = QLabel("准备中")
        self.state_lbl.setObjectName("badge")
        row.addWidget(self.state_lbl)
        v.addLayout(row)

        self.bar = QProgressBar(); self.bar.setRange(0, 100); self.bar.setValue(0)
        v.addWidget(self.bar)

        meta = QHBoxLayout()
        self.speed_lbl = QLabel("0 B/s · %d 线程" % threads)
        self.speed_lbl.setObjectName("sub")
        meta.addWidget(self.speed_lbl)
        meta.addStretch(1)
        self.open_btn = QPushButton("打开文件夹")
        self.open_btn.setObjectName("small")
        self.open_btn.setStyleSheet("padding:5px 11px;font-size:12px;border-radius:7px;background:#eaf1ff;color:#2f6fed;")
        self.open_btn.clicked.connect(self.open_folder)
        self.open_btn.hide()
        meta.addWidget(self.open_btn)
        v.addLayout(meta)

    def update_progress(self, done, total, speed, state):
        pct = int(done * 100 / total) if total else 0
        self.bar.setValue(max(0, min(100, pct)))
        self.state_lbl.setText(state)
        self.speed_lbl.setText("%s / %s · %s/s · %d 线程" % (
            L.fmt_size(done), L.fmt_size(total), L.fmt_size(speed), self.threads))

    def finish(self, ok, message):
        self.finished = True
        self.state_lbl.setText("✓ 完成" if ok else "✕ " + message[:16])
        if ok:
            self.bar.setValue(100)
            self.open_btn.show()

    def set_dark(self, dark):
        bg = "#1e2536" if dark else "#ffffff"
        bd = "#2c3547" if dark else "#e4e9f2"
        self.setStyleSheet("QFrame#card{background:%s;border:1px solid %s;border-radius:12px;}" % (bg, bd))

    def open_folder(self):
        import subprocess
        folder = os.path.dirname(self.path)
        try:
            subprocess.Popen(["explorer", folder])
        except Exception:
            pass

# ---------------- 通用工具 ----------------

def pick_save_path(default_name, parent):
    path, _ = QFileDialog.getSaveFileName(parent, "保存到", default_name, "所有文件 (*.*)")
    return path or None

# ---------------- 主窗口 ----------------

class MainWindow(QWidget):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("极速下载器 Windows · HTTP 高速下载 + 局域网互传（与安卓互通）")
        self.resize(1180, 800)
        self.setMinimumSize(960, 660)
        self.dark = False
        self.hub = None            # 本机开的房间（host）
        self.client = None         # 加入安卓房间（guest）
        self.mode = "download"     # download / host / guest
        self.room_on = False
        self.dl_tasks = []         # HTTP 下载任务列表
        self.msgs = []
        self.last_sig = ""
        self.clear_ts = 0
        self.seen_ts = 0
        self.transfer_threads = []
        self._drag_pos = None
        self.setObjectName("root")
        self._build()
        self.apply_theme()

        self.poll_timer = QTimer(self)
        self.poll_timer.timeout.connect(self.poll)
        self.poll_timer.start(2500)

    # ---------- 构建 UI ----------
    def _build(self):
        root = QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)

        # 自绘标题栏
        titlebar = QFrame(); titlebar.setFixedHeight(52)
        titlebar.setStyleSheet("background:transparent;")
        tb = QHBoxLayout(titlebar); tb.setContentsMargins(18, 0, 12, 0)
        brand = QLabel("⚡ 极速下载器")
        brand.setStyleSheet("font-size:16px;font-weight:800;color:#2f6fed;")
        sub = QLabel("Windows 版 · HTTP 多线程下载 + 与安卓互通")
        sub.setObjectName("sub")
        tb.addWidget(brand); tb.addWidget(sub); tb.addStretch(1)
        theme_btn = QPushButton("◐ 主题")
        theme_btn.setObjectName("ghost"); theme_btn.clicked.connect(self.toggle_theme)
        tb.addWidget(theme_btn)
        mn = QPushButton("—"); mn.setFixedWidth(34); mn.clicked.connect(self.showMinimized)
        cls = QPushButton("✕"); cls.setFixedWidth(34); cls.setObjectName("red")
        cls.clicked.connect(self.close)
        tb.addWidget(mn); tb.addWidget(cls)
        root.addWidget(titlebar)
        self.titlebar = titlebar

        # 顶部主模式切换（限宽居中，与内容区对齐）
        tabbar = QWidget(); tabbar.setMaximumWidth(1180)
        modetabs = QHBoxLayout(tabbar); modetabs.setContentsMargins(16, 0, 16, 8); modetabs.setSpacing(8)
        self.tab_dl = QPushButton("⬇ HTTP 高速下载"); self.tab_dl.setCheckable(True); self.tab_dl.setChecked(True)
        self.tab_pan = QPushButton("☁ 网盘下载"); self.tab_pan.setCheckable(True)
        self.tab_host = QPushButton("📡 开房间"); self.tab_host.setCheckable(True)
        self.tab_guest = QPushButton("🔗 加入房间"); self.tab_guest.setCheckable(True)
        for t in (self.tab_dl, self.tab_pan, self.tab_host, self.tab_guest):
            t.setObjectName("tab"); modetabs.addWidget(t)
        self.tab_dl.clicked.connect(lambda: self.switch_mode("download"))
        self.tab_pan.clicked.connect(lambda: self.switch_mode("pan"))
        self.tab_host.clicked.connect(lambda: self.switch_mode("host"))
        self.tab_guest.clicked.connect(lambda: self.switch_mode("guest"))
        modetabs.addStretch(1)
        tabbar_outer = QHBoxLayout(); tabbar_outer.setContentsMargins(0, 0, 0, 0)
        tabbar_outer.addStretch(1); tabbar_outer.addWidget(tabbar, 0); tabbar_outer.addStretch(1)
        root.addLayout(tabbar_outer)

        # 主内容区：限宽居中，避免宽屏上左右拉散、一大片空白
        content = QWidget()
        content.setMaximumWidth(1180)
        content.setSizePolicy(QSizePolicy.Preferred, QSizePolicy.Expanding)
        body = QHBoxLayout(content); body.setContentsMargins(16, 0, 16, 16); body.setSpacing(14)

        left = QFrame(); left.setObjectName("card")
        left.setFixedWidth(330)
        left.setSizePolicy(QSizePolicy.Fixed, QSizePolicy.Expanding)
        lv = QVBoxLayout(left); lv.setContentsMargins(16, 16, 16, 16); lv.setSpacing(9)

        self.stack = QStackedWidget()  # 四个模式面板：download / pan / host / guest
        self.stack.addWidget(self._build_download_panel())
        self.stack.addWidget(self._build_pan_panel())
        self.stack.addWidget(self._build_host_panel())
        self.stack.addWidget(self._build_guest_panel())
        lv.addWidget(self.stack, 1)
        body.addWidget(left, 0)   # 左栏不强行拉伸，靠 minimumWidth 保证可读

        # 右侧：三合一堆叠（下载任务 / 聊天 / 未连接占位）
        self.right_stack = QStackedWidget()
        self.right_stack.addWidget(self._build_download_tasks())
        self.right_stack.addWidget(self._build_chat_area())
        self.right_stack.addWidget(self._build_idle_placeholder())
        self.right_stack.setCurrentIndex(0)   # 默认 HTTP 下载模式：显示下载任务页
        body.addWidget(self.right_stack, 1)

        outer = QHBoxLayout(); outer.setContentsMargins(0, 0, 0, 0)
        outer.addStretch(1); outer.addWidget(content, 0); outer.addStretch(1)
        root.addLayout(outer, 1)

    def _build_pan_panel(self):
        w = QWidget(); v = QVBoxLayout(w); v.setContentsMargins(0, 0, 0, 0); v.setSpacing(9)
        tip = QLabel("先选网盘平台，再粘贴分享链接解析文件，选中后交给高速引擎下载。")
        tip.setObjectName("sub"); tip.setWordWrap(True); v.addWidget(tip)

        prow = QHBoxLayout(); prow.addWidget(QLabel("网盘平台"))
        self.pan_platform_combo = QComboBox()
        self.pan_platform_combo.addItem("自动识别", None)
        for _pid, _pname in [("quark", "夸克网盘"), ("uc", "UC网盘"), ("baidu", "百度网盘"),
                             ("xunlei", "迅雷网盘"), ("c139", "139和彩云"), ("pan123", "123云盘"),
                             ("pan115", "115网盘"), ("guangya", "光鸭云盘"),
                             ("ilanzou", "蓝奏云优享"), ("lanzou", "蓝奏云"), ("github", "GitHub")]:
            self.pan_platform_combo.addItem(_pname, _pid)
        self.pan_platform_combo.currentIndexChanged.connect(self._on_pan_platform_changed)
        prow.addWidget(self.pan_platform_combo, 1); v.addLayout(prow)

        v.addWidget(QLabel("分享链接（可整段粘贴，自动提取提取码）"))
        self.pan_url = QLineEdit(); self.pan_url.setPlaceholderText("https://pan.quark.cn/s/xxxx 提取码: abcd")
        self.pan_url.setMinimumHeight(38); v.addWidget(self.pan_url)

        self.pan_platform = QLabel("已选平台：自动识别（粘贴链接后自动判断）"); self.pan_platform.setObjectName("sub")
        v.addWidget(self.pan_platform)

        self.pan_parse_btn = QPushButton("解析文件列表"); self.pan_parse_btn.clicked.connect(self.parse_pan)
        v.addWidget(self.pan_parse_btn)

        crow = QHBoxLayout()
        crow.addWidget(QLabel("Cookie / Token（需登录的网盘）"))
        crow.addStretch(1)
        self.pan_login_btn = QPushButton("网页登录"); self.pan_login_btn.setObjectName("small")
        self.pan_login_btn.clicked.connect(self.open_web_login); crow.addWidget(self.pan_login_btn)
        self.pan_myfiles_btn = QPushButton("我的文件"); self.pan_myfiles_btn.setObjectName("small")
        self.pan_myfiles_btn.clicked.connect(self.open_my_files); crow.addWidget(self.pan_myfiles_btn)
        v.addLayout(crow)
        self.pan_cookie = QLineEdit(); self.pan_cookie.setPlaceholderText("登录后自动填入，或手动粘贴 Cookie；GitHub 可填 Token")
        self.pan_cookie.setEchoMode(QLineEdit.Password); v.addWidget(self.pan_cookie)

        self.pan_files = QListWidget(); self.pan_files.setSelectionMode(QAbstractItemView.ExtendedSelection)
        self.pan_files.setMinimumHeight(120); v.addWidget(self.pan_files, 1)

        self.pan_dl_btn = QPushButton("下载选中文件"); self.pan_dl_btn.clicked.connect(self.download_pan_files)
        self.pan_dl_btn.setEnabled(False); v.addWidget(self.pan_dl_btn)

        hint = QLabel("提示：GitHub / 蓝奏云免登录；夸克 / UC 点「网页登录」后解析。百度等接口适配中。登录 Cookie 自动填入，也可手动粘贴。仅下载你有权获取的内容。")
        hint.setObjectName("sub"); hint.setWordWrap(True); v.addWidget(hint)
        return w

    def _build_download_panel(self):
        w = QWidget(); v = QVBoxLayout(w); v.setContentsMargins(0, 0, 0, 0); v.setSpacing(8)
        tip = QLabel("多线程分片下载 HTTP / HTTPS 直链，支持断点续传。")
        tip.setObjectName("sub"); tip.setWordWrap(True); v.addWidget(tip)

        v.addWidget(QLabel("下载链接（http / https）"))
        self.dl_url = QLineEdit(); self.dl_url.setPlaceholderText("https://example.com/file.zip")
        self.dl_url.setMinimumHeight(38); v.addWidget(self.dl_url)

        th = QHBoxLayout(); th.addWidget(QLabel("线程数"))
        self.dl_threads = QComboBox(); self.dl_threads.addItems(["2", "4", "8", "16", "32"])
        self.dl_threads.setCurrentText("8"); th.addWidget(self.dl_threads); th.addStretch(1)
        v.addLayout(th)

        self.dl_btn = QPushButton("⬇ 开始下载"); self.dl_btn.clicked.connect(self.start_download)
        v.addWidget(self.dl_btn)
        self.dl_pause = QPushButton("暂停 / 继续"); self.dl_pause.setObjectName("ghost")
        self.dl_pause.clicked.connect(self.pause_current_download); v.addWidget(self.dl_pause)

        hint = QLabel("提示：服务器需支持 Range 才能多线程；不支持时自动单线程下载。断点进度保存在 .part 文件，中断后可重新添加同一链接继续。")
        hint.setObjectName("sub"); hint.setWordWrap(True); v.addWidget(hint)
        v.addStretch(1)
        return w

    def _build_download_tasks(self):
        w = QWidget(); v = QVBoxLayout(w); v.setContentsMargins(0, 0, 0, 0)
        card = QFrame(); card.setObjectName("card")
        cv = QVBoxLayout(card); cv.setContentsMargins(16, 14, 16, 14); cv.setSpacing(10)
        h = QHBoxLayout(); t = QLabel("下载任务"); t.setObjectName("h2")
        self.dl_summary = QLabel("空闲"); self.dl_summary.setObjectName("badge")
        h.addWidget(t); h.addWidget(self.dl_summary); h.addStretch(1)
        clr = QPushButton("清除已完成"); clr.setObjectName("small"); clr.clicked.connect(self.clear_finished_downloads)
        h.addWidget(clr)
        cv.addLayout(h)

        self.dl_body = QStackedWidget()

        # 页 0：空状态引导
        empty = QWidget(); ev = QVBoxLayout(empty); ev.setContentsMargins(24, 30, 24, 30)
        ev.setAlignment(Qt.AlignCenter)
        eicon = QLabel("⬇"); eicon.setAlignment(Qt.AlignCenter); eicon.setStyleSheet("font-size:56px;color:#9fb4d8;")
        etitle = QLabel("还没有下载任务"); etitle.setObjectName("h2"); etitle.setAlignment(Qt.AlignCenter)
        ehint = QLabel("在左侧粘贴 HTTP / HTTPS 链接，选择线程数后点击「开始下载」\n任务进度、速度与剩余时间会显示在这里。")
        ehint.setObjectName("sub"); ehint.setWordWrap(True); ehint.setAlignment(Qt.AlignCenter)
        ev.addStretch(1); ev.addWidget(eicon); ev.addSpacing(14)
        ev.addWidget(etitle); ev.addSpacing(8); ev.addWidget(ehint); ev.addStretch(1)
        self.dl_body.addWidget(empty)

        # 页 1：任务列表
        self.dl_scroll = QScrollArea(); self.dl_scroll.setWidgetResizable(True)
        self.dl_box = QWidget(); self.dl_list = QVBoxLayout(self.dl_box)
        self.dl_list.setContentsMargins(4, 4, 4, 4); self.dl_list.setSpacing(8)
        self.dl_list.addStretch(1)
        self.dl_scroll.setWidget(self.dl_box)
        self.dl_body.addWidget(self.dl_scroll)
        self.dl_body.setCurrentIndex(0)
        cv.addWidget(self.dl_body, 1)
        card.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Expanding)
        v.addWidget(card, 1)
        return w

    def _build_chat_area(self):
        card = QFrame(); card.setObjectName("card")
        cv = QVBoxLayout(card); cv.setContentsMargins(16, 14, 16, 14); cv.setSpacing(10)
        head = QHBoxLayout()
        h2 = QLabel("聊天 · 文件互传"); h2.setObjectName("h2")
        self.status = QLabel("未连接"); self.status.setObjectName("badge")
        head.addWidget(h2); head.addWidget(self.status); head.addStretch(1)
        clr = QPushButton("清空聊天"); clr.setObjectName("small")
        clr.clicked.connect(self.ask_clear)
        head.addWidget(clr)
        cv.addLayout(head)

        self.scroll = QScrollArea(); self.scroll.setWidgetResizable(True)
        self.chatbox = QWidget(); self.chatlist = QVBoxLayout(self.chatbox)
        self.chatlist.setContentsMargins(4, 4, 4, 4); self.chatlist.setSpacing(2)
        self.chatlist.addStretch(1)
        self.scroll.setWidget(self.chatbox)
        cv.addWidget(self.scroll, 1)

        self.newmsg = QPushButton("↓ 新消息"); self.newmsg.setObjectName("small")
        self.newmsg.clicked.connect(self.goto_bottom)
        self.newmsg.hide()
        cv.addWidget(self.newmsg)

        inp = QHBoxLayout()
        self.input = QLineEdit(); self.input.setPlaceholderText("说点什么，回车发送…")
        self.input.returnPressed.connect(self.send_text)
        send = QPushButton("发送"); send.clicked.connect(self.send_text)
        inp.addWidget(self.input); inp.addWidget(send)
        cv.addLayout(inp)
        tools = QHBoxLayout()
        upf = QPushButton("📎 上传文件"); upf.setObjectName("ghost"); upf.clicked.connect(self.upload_files)
        upd = QPushButton("📁 上传文件夹"); upd.setObjectName("gray"); upd.clicked.connect(self.upload_folder)
        tools.addWidget(upf); tools.addWidget(upd); tools.addStretch(1)
        cv.addLayout(tools)
        self.progress = QProgressBar(); self.progress.hide(); cv.addWidget(self.progress)
        return card

    def _build_idle_placeholder(self):
        """房间未开启/未加入时的占位页：把用不到的聊天区收起来"""
        w = QWidget(); v = QVBoxLayout(w); v.setContentsMargins(16, 0, 16, 16)
        card = QFrame(); card.setObjectName("card")
        cv = QVBoxLayout(card); cv.setContentsMargins(24, 30, 24, 30)
        cv.setAlignment(Qt.AlignCenter)
        icon = QLabel("💬"); icon.setAlignment(Qt.AlignCenter)
        icon.setStyleSheet("font-size:64px;")
        title = QLabel("聊天互传将在连接后开启"); title.setObjectName("h2")
        title.setAlignment(Qt.AlignCenter)
        self.idle_hint = QLabel("在左侧开启房间或加入房间，即可在这里聊天、收发文件。")
        self.idle_hint.setObjectName("sub"); self.idle_hint.setWordWrap(True)
        self.idle_hint.setAlignment(Qt.AlignCenter)
        cv.addStretch(1); cv.addWidget(icon); cv.addSpacing(12)
        cv.addWidget(title); cv.addSpacing(6); cv.addWidget(self.idle_hint); cv.addStretch(1)
        card.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Expanding)
        v.addWidget(card, 1)
        return w

    def _build_host_panel(self):
        w = QWidget(); v = QVBoxLayout(w); v.setContentsMargins(0, 0, 0, 0); v.setSpacing(8)
        tip = QLabel("开房间后，同一 Wi-Fi 下的安卓 App / 浏览器输入地址 + 口令即可加入，无需服务器。")
        tip.setObjectName("sub"); tip.setWordWrap(True); v.addWidget(tip)

        lenrow = QHBoxLayout(); lenrow.addWidget(QLabel("口令位数"))
        self.code_len = QComboBox(); self.code_len.addItems(["4", "5", "6"])
        lenrow.addWidget(self.code_len); lenrow.addStretch(1)
        v.addLayout(lenrow)

        self.host_btn = QPushButton("📡 开启房间"); self.host_btn.clicked.connect(self.toggle_host)
        v.addWidget(self.host_btn)

        self.room_info = QWidget(); ri = QVBoxLayout(self.room_info); ri.setContentsMargins(0, 0, 0, 0)
        ri.setSpacing(8)
        ri.addWidget(QLabel("房间口令", alignment=Qt.AlignCenter))
        self.code_lbl = QLabel("----"); self.code_lbl.setObjectName("codebig")
        self.code_lbl.setAlignment(Qt.AlignCenter); ri.addWidget(self.code_lbl)
        self.addr_lbl = QLabel("地址：-"); self.addr_lbl.setObjectName("addr")
        self.addr_lbl.setAlignment(Qt.AlignCenter); self.addr_lbl.setTextInteractionFlags(Qt.TextSelectableByMouse)
        ri.addWidget(self.addr_lbl)
        self.qr_lbl = QLabel(); self.qr_lbl.setAlignment(Qt.AlignCenter)
        self.qr_lbl.setFixedHeight(150)   # 固定高度，避免被布局压缩导致与按钮重叠
        self.qr_lbl.hide()
        ri.addWidget(self.qr_lbl)
        row = QHBoxLayout()
        copy = QPushButton("复制地址"); copy.setObjectName("ghost"); copy.clicked.connect(self.copy_addr)
        openb = QPushButton("浏览器打开"); openb.setObjectName("gray"); openb.clicked.connect(self.open_browser)
        row.addWidget(copy); row.addWidget(openb)
        ri.addLayout(row)
        self.room_info.hide()   # 未开房间时不占位
        v.addWidget(self.room_info)
        v.addStretch(1)         # 剩余空白统一压到最底部
        return w

    def _build_guest_panel(self):
        w = QWidget(); v = QVBoxLayout(w); v.setContentsMargins(0, 0, 0, 0); v.setSpacing(10)
        tip = QLabel("输入安卓 App（或 Windows）开启的房间地址与口令，即可加入一起互传。")
        tip.setObjectName("sub"); tip.setWordWrap(True); v.addWidget(tip)
        v.addWidget(QLabel("主机地址（IPv4:端口）"))
        self.guest_addr = QLineEdit(); self.guest_addr.setPlaceholderText("例如 192.168.1.8:45321")
        v.addWidget(self.guest_addr)
        v.addWidget(QLabel("数字口令"))
        self.guest_code = QLineEdit(); self.guest_code.setObjectName("code")
        self.guest_code.setPlaceholderText("····"); self.guest_code.setMaxLength(8)
        v.addWidget(self.guest_code)
        v.addWidget(QLabel("我的昵称"))
        self.guest_name = QLineEdit(); self.guest_name.setPlaceholderText(MY_NAME)
        v.addWidget(self.guest_name)
        self.guest_btn = QPushButton("加入房间"); self.guest_btn.clicked.connect(self.toggle_guest)
        v.addWidget(self.guest_btn)
        hint = QLabel("提示：可用安卓端生成的二维码链接，口令会自动带入。")
        hint.setObjectName("sub"); hint.setWordWrap(True); v.addWidget(hint)
        v.addStretch(1)
        return w

    # ---------- 主题 ----------
    def apply_theme(self):
        QApplication.instance().setStyleSheet(theme.DARK if self.dark else theme.LIGHT)
        for i in range(self.dl_list.count()):
            c = self.dl_list.itemAt(i).widget()
            if isinstance(c, DownloadTaskCard):
                c.set_dark(self.dark)
        if self.hub and self.room_on and self.mode == "host":
            self.render_qr()

    def toggle_theme(self):
        self.dark = not self.dark
        self.apply_theme()
        self.render_chat()

    # ---------- 无边框拖动 ----------
    def mousePressEvent(self, e):
        if e.button() == Qt.LeftButton and self.titlebar.underMouse():
            self._drag_pos = e.globalPos() - self.frameGeometry().topLeft()

    def mouseMoveEvent(self, e):
        if self._drag_pos and e.buttons() & Qt.LeftButton:
            self.move(e.globalPos() - self._drag_pos)

    def mouseReleaseEvent(self, e):
        self._drag_pos = None

    # ---------- 模式切换 ----------
    def switch_mode(self, mode):
        if self.room_on and mode != self.mode and mode not in ("download", "pan"):
            QMessageBox.information(self, "提示", "请先关闭当前房间/退出后再切换模式。")
            for t, m in ((self.tab_dl, "download"), (self.tab_pan, "pan"),
                         (self.tab_host, "host"), (self.tab_guest, "guest")):
                t.setChecked(self.mode == m)
            return
        # 下载模式可与房间共存（不冲突），但切换时仅换 UI
        self.mode = mode
        self.tab_dl.setChecked(mode == "download")
        self.tab_pan.setChecked(mode == "pan")
        self.tab_host.setChecked(mode == "host")
        self.tab_guest.setChecked(mode == "guest")
        idx = {"download": 0, "pan": 1, "host": 2, "guest": 3}[mode]
        self.stack.setCurrentIndex(idx)
        # 收起未用到的区域：下载/网盘->任务；房间模式且已连接->聊天；否则->占位引导
        if mode in ("download", "pan"):
            self.right_stack.setCurrentIndex(0)
        else:
            self.right_stack.setCurrentIndex(1 if self.room_on else 2)
            self.idle_hint.setText(
                "房间已连接，右侧聊天已就绪。" if self.room_on else
                ("在左侧开启房间或加入房间，即可在这里聊天、收发文件。")
            )
            self.set_status("房间已开启" if self.room_on else "未连接")

    # ---------- 开房间（host） ----------
    def toggle_host(self):
        if self.room_on:
            self.stop_room()
            return
        n = int(self.code_len.currentText())
        page = open(res_path("assets/lan.html"), "rb").read()
        self.hub = L.LanHub(code_len=n, page_bytes=page)
        try:
            self.hub.start()
        except Exception as e:
            self.hub = None
            self.toast("开启失败：" + str(e)[:80])
            return
        self.room_on = True
        self.mode = "host"
        self.room_info.show()
        self.qr_lbl.show()
        self.code_lbl.setText(self.hub.code)
        self.addr_lbl.setText(self.hub.url())
        self.host_btn.setText("关闭房间"); self.host_btn.setObjectName("red")
        self.host_btn.setStyleSheet("background:#fdeeee;color:#d64545;")
        self.render_qr()
        self.right_stack.setCurrentIndex(1)   # 已连接：显示聊天区
        self.set_status("房间已开启 · %d 人" % self.peer_count())
        self.msgs = []
        self.last_sig = ""
        self.render_chat()

    def render_qr(self):
        try:
            import qrcode
            img = qrcode.make(self.hub.url() + "/?code=" + self.hub.code, box_size=6, border=2)
            img = img.convert("RGB")
            data = img.tobytes()
            w, h = img.size
            qimg = QImage(data, w, h, w * 3, QImage.Format_RGB888)
            self.qr_lbl.setPixmap(QPixmap.fromImage(qimg).scaled(144, 144, Qt.KeepAspectRatio, Qt.SmoothTransformation))
        except Exception as e:
            self.qr_lbl.setText("二维码生成失败：" + str(e)[:40])

    def copy_addr(self):
        if not self.hub:
            return
        text = self.hub.url() + "  口令 " + self.hub.code
        QApplication.clipboard().setText(text)
        self.toast("已复制地址与口令")

    def open_browser(self):
        if self.hub:
            webbrowser.open(self.hub.url() + "/?code=" + self.hub.code)

    # ---------- 加入房间（guest，连安卓） ----------
    def toggle_guest(self):
        if self.room_on:
            self.stop_room()
            return
        addr = self.guest_addr.text().strip()
        code = self.guest_code.text().strip()
        name = self.guest_name.text().strip() or MY_NAME
        if not addr or not code:
            self.toast("请填写地址与口令")
            return
        self.guest_btn.setText("连接中…"); self.guest_btn.setEnabled(False)
        self.client = L.LanClient(addr, code, ME, name)
        def work():
            r = self.client.join()
            self.guest_btn.setEnabled(True)
            if r.ok:
                self.room_on = True
                self.guest_btn.setText("退出房间"); self.guest_btn.setObjectName("red")
                self.guest_btn.setStyleSheet("background:#fdeeee;color:#d64545;")
                self.set_status("已加入 " + code)
                QTimer.singleShot(0, lambda: self.right_stack.setCurrentIndex(1))  # 回到主线程切 UI
                self.msgs = []; self.last_sig = ""; self.clear_ts = 0; self.seen_ts = 0
            else:
                self.guest_btn.setText("加入房间")
                self.toast("加入失败：" + r.msg)
        threading.Thread(target=work, daemon=True).start()

    # ---------- 统一关闭 ----------
    def stop_room(self):
        if self.mode == "host" and self.hub:
            self.hub.stop(); self.hub = None
            self.code_lbl.setText("----"); self.addr_lbl.setText("地址：-"); self.qr_lbl.clear()
            self.qr_lbl.hide(); self.room_info.hide()
            self.host_btn.setText("📡 开启房间")
            self.host_btn.setStyleSheet("")
        else:
            self.client = None
            self.guest_btn.setText("加入房间"); self.guest_btn.setStyleSheet("")
        self.room_on = False
        self.msgs = []; self.last_sig = ""; self.clear_ts = 0; self.seen_ts = 0
        self.set_status("未连接")
        if self.mode != "download":
            self.right_stack.setCurrentIndex(2)   # 收起聊天区，回到占位引导
        self.render_chat()

    # ---------- 轮询 ----------
    def poll(self):
        if not self.room_on:
            return
        if self.mode == "host":
            msgs = self.hub.chat_json() if self.hub else []
            self.set_status("房间已开启 · %d 人" % self.peer_count())
        else:
            try:
                r = self.client.chat()
                if not r.ok:
                    self.set_status("断开：" + r.msg[:16]); return
                msgs = r.msgs
            except Exception:
                return
        sig = "|".join(m.get("id", "") + m.get("kind", "") for m in msgs)
        if sig != self.last_sig:
            self.last_sig = sig
            self.msgs = msgs
            self.render_chat()

    def peer_count(self):
        if not self.hub:
            return 1
        with self.hub.lock:
            now = time.time()
            for k in list(self.hub.peers):
                if now - self.hub.peers[k] > 90:
                    self.hub.peers.pop(k, None)
            return len(self.hub.peers) + 1

    # ---------- 渲染聊天 ----------
    def render_chat(self):
        while self.chatlist.count() > 1:
            item = self.chatlist.takeAt(self.chatlist.count() - 1)
            w = item.widget()
            if w:
                w.setParent(None); w.deleteLater()
        shown = [m for m in self.msgs if m.get("time", 0) > self.clear_ts]
        if not shown:
            empty = QLabel("还没有消息，发条消息或上传文件试试～")
            empty.setObjectName("sub"); empty.setAlignment(Qt.AlignCenter)
            empty.setMinimumHeight(120)
            self.chatlist.insertWidget(0, empty)
            return
        near_bottom = (self.scroll.verticalScrollBar().value() >=
                       self.scroll.verticalScrollBar().maximum() - 60)
        max_t = max(m.get("time", 0) for m in shown)
        if self.seen_ts == 0:
            self.seen_ts = max_t
        for m in shown:
            b = Bubble(m, ME, dark=self.dark)
            b.recallClicked.connect(self.do_recall)
            b.previewClicked.connect(self.preview_file)
            b.downloadClicked.connect(self.download_file)
            self.chatlist.insertWidget(self.chatlist.count() - 1, b)
        if near_bottom:
            QTimer.singleShot(60, self.goto_bottom)
            self.seen_ts = max_t; self.newmsg.hide()
        else:
            unread = sum(1 for m in shown if m.get("time", 0) > self.seen_ts)
            if unread > 0:
                self.newmsg.setText("↓ %d 条新消息" % unread); self.newmsg.show()
            else:
                self.newmsg.hide()

    def goto_bottom(self):
        self.scroll.verticalScrollBar().setValue(self.scroll.verticalScrollBar().maximum())
        self.seen_ts = max((m.get("time", 0) for m in self.msgs), default=0)
        self.newmsg.hide()

    # ---------- 发送文本 ----------
    def send_text(self):
        text = self.input.text().strip()
        if not text:
            return
        if not self.room_on:
            self.toast("请先开启或加入房间"); return
        self.input.clear()
        if self.mode == "host":
            self.hub.send_text(ME, self.my_name(), text)
        else:
            def work():
                r = self.client.send_text(text)
                if not r.ok:
                    self.toast("发送失败：" + r.msg)
            threading.Thread(target=work, daemon=True).start()
        self.seen_ts = int(time.time() * 1000)
        QTimer.singleShot(200, self.poll)

    def my_name(self):
        if self.mode == "guest" and self.client:
            return self.guest_name.text().strip() or MY_NAME
        return MY_NAME

    # ---------- 上传文件 / 文件夹 ----------
    def upload_files(self):
        if not self.room_on:
            self.toast("请先开启或加入房间"); return
        paths, _ = QFileDialog.getOpenFileNames(self, "选择文件", "", "所有文件 (*.*)")
        if paths:
            self.do_upload([(p, os.path.basename(p)) for p in paths])

    def upload_folder(self):
        if not self.room_on:
            self.toast("请先开启或加入房间"); return
        d = QFileDialog.getExistingDirectory(self, "选择文件夹")
        if not d:
            return
        items = []
        for root, dirs, files in os.walk(d):
            for f in files:
                full = os.path.join(root, f)
                rel = os.path.relpath(full, os.path.dirname(d))  # 保留顶层文件夹名
                items.append((full, rel.replace("\\", "/")))
        if items:
            self.toast("准备上传 %d 个文件…" % len(items))
            self.do_upload(items)

    def do_upload(self, items):
        self.progress.show(); self.progress.setMaximum(len(items)); self.progress.setValue(0)
        def work():
            done = 0
            for path, rel in items:
                try:
                    if self.mode == "host":
                        self.hub.add_local_file(path, ME, self.my_name())
                    else:
                        r = self.client.upload(path, rel)
                        if not r.ok:
                            self.toast("上传失败 " + rel + "：" + r.msg)
                except Exception as e:
                    self.toast("上传出错：" + str(e)[:60])
                done += 1
                self.progress.setValue(done)
            self.progress.hide()
            self.poll()
        threading.Thread(target=work, daemon=True).start()

    # ---------- 下载 ----------
    def download_file(self, msg):
        name = msg.get("fileName") or "file"
        dest = pick_save_path(name, self)
        if not dest:
            return
        def work():
            try:
                if self.mode == "host":
                    rf = next((f for f in self.hub.files if f.id == msg.get("fileId")), None)
                    if not rf:
                        self.toast("文件已不存在"); return
                    data = open(rf.path, "rb").read()
                else:
                    data = self.client.download(msg.get("fileId"))
                with open(dest, "wb") as f:
                    f.write(data)
                self.toast("已保存到：" + dest)
            except Exception as e:
                self.toast("下载失败：" + str(e)[:60])
        threading.Thread(target=work, daemon=True).start()

    # ---------- 内置预览 ----------
    def preview_file(self, msg):
        name = msg.get("fileName") or ""
        media = L.media_class(name)
        def work():
            try:
                if self.mode == "host":
                    rf = next((f for f in self.hub.files if f.id == msg.get("fileId")), None)
                    if not rf:
                        self.toast("文件已不存在"); return
                    src = rf.path
                else:
                    data = self.client.download(msg.get("fileId"), inline=True)
                    import tempfile
                    src = os.path.join(tempfile.gettempdir(), "zdown_prev_" + msg.get("fileId", "x") + "_" + name)
                    with open(src, "wb") as f:
                        f.write(data)
                dlg = PreviewDialog(src, media, dark=self.dark, parent=self)
                dlg.exec_()
            except Exception as e:
                self.toast("预览失败：" + str(e)[:60])
        threading.Thread(target=work, daemon=True).start()

    # ---------- 撤回（仅自己） ----------
    def do_recall(self, mid):
        msg = next((m for m in self.msgs if m.get("id") == mid), None)
        if not msg or msg.get("fromId") != ME:
            self.toast("只能撤回自己的消息"); return
        dlg = ConfirmDialog("撤回消息", "确定撤回这条消息？撤回后对所有成员生效。", self.dark, self)
        dlg.exec_()
        if not dlg.result_ok:
            return
        if self.mode == "host":
            ok = self.hub.recall(ME, mid)
            if not ok:
                self.toast("撤回失败")
        else:
            def work():
                r = self.client.recall(mid)
                if not r.ok:
                    self.toast("撤回失败：" + r.msg)
            threading.Thread(target=work, daemon=True).start()
        QTimer.singleShot(250, self.poll)

    # ---------- 清空聊天（页面内确认，本地生效） ----------
    def ask_clear(self):
        dlg = ConfirmDialog("清空聊天", "清空当前聊天显示？只对你自己生效，不影响其他成员。", self.dark, self)
        dlg.exec_()
        if dlg.result_ok:
            self.clear_ts = int(time.time() * 1000)
            self.seen_ts = 0
            self.render_chat()
            self.toast("聊天显示已清空")

    # ---------- 网盘下载 ----------
    def _on_pan_platform_changed(self):
        if not hasattr(self, "pan_url"):
            return
        pid = self.pan_platform_combo.currentData()
        hints = {
            "quark": ("已选平台：夸克网盘", "https://pan.quark.cn/s/xxxx 提取码: abcd"),
            "uc": ("已选平台：UC网盘", "https://drive.uc.cn/s/xxxx 密码: abcd"),
            "baidu": ("已选平台：百度网盘", "https://pan.baidu.com/s/1xxxx 提取码: abcd"),
            "xunlei": ("已选平台：迅雷网盘", "https://pan.xunlei.com/s/xxxx?pwd=abcd"),
            "c139": ("已选平台：139和彩云", "https://yun.139.com/shareweb/#/w/i/xxxx 提取码 abcd"),
            "pan123": ("已选平台：123云盘", "https://www.123pan.com/s/xxxx-xxxx"),
            "pan115": ("已选平台：115网盘", "https://115.com/s/swxxxxx?password=abcd"),
            "guangya": ("已选平台：光鸭云盘", "https://www.guangyapan.com/s/xxxx"),
            "ilanzou": ("已选平台：蓝奏云优享", "https://www.ilanzou.com/s/xxxx"),
            "lanzou": ("已选平台：蓝奏云", "https://www.lanzoui.com/xxxx"),
            "github": ("已选平台：GitHub", "https://github.com/owner/repo 或 .../blob/main/file.zip"),
        }
        label, placeholder = hints.get(pid, ("已选平台：自动识别（粘贴链接后自动判断）", "https://pan.quark.cn/s/xxxx 提取码: abcd"))
        self.pan_platform.setText(label)
        self.pan_url.setPlaceholderText(placeholder)

    def open_my_files(self):
        import pan
        pid = self.pan_platform_combo.currentData()
        if pid in (None, "github"):
            QMessageBox.information(self, "提示", "请先在上方选择网盘平台（夸克 / UC / 百度）")
            return
        cookies = self.pan_cookie.text().strip()
        if not cookies:
            QMessageBox.information(self, "需要先登录", "请先点「网页登录」登录 %s，或手动粘贴 Cookie。" % pan.PLATFORMS.get(pid, pid))
            return
        try:
            import myfiles
            dlg = myfiles.MyFilesDialog(pid, cookies, self, self.dark)
            if dlg.exec_() != QDialog.Accepted:
                return
            files = dlg.result_files()
            if not files:
                return
            self._enqueue_pan_direct(files, cookies)
        except Exception as e:
            QMessageBox.warning(self, "打开失败", str(e)[:150])

    def _enqueue_pan_direct(self, files, cookies=None):
        import http_dl
        n = 0
        for f in files:
            url = f.get("url", "")
            if not url:
                continue
            headers = dict(f.get("headers") or {})
            if cookies:
                headers["Cookie"] = cookies
            try:
                name, total, rng, final = http_dl.probe(url, headers=headers)
            except Exception as e:
                QMessageBox.warning(self, "取链失败", "%s：%s" % (f.get("name", "文件"), str(e)[:100]))
                continue
            default = os.path.join(os.path.expanduser("~"), "Downloads", f.get("name") or name)
            dest = QFileDialog.getSaveFileName(self, "保存到", default, "所有文件 (*.*)")[0]
            if not dest:
                continue
            card = DownloadTaskCard(dest, f.get("name") or name, self.threads_for_pan(), self.dark)
            self.dl_list.insertWidget(self.dl_list.count() - 1, card)
            task = http_dl.Task(url, dest, threads=self.threads_for_pan(), headers=headers,
                on_progress=lambda d, t, s, st, c=card: self._on_dl_progress(c, d, t, s, st),
                on_done=lambda ok, m, c=card: self._on_dl_done(c, ok, m))
            card.task = task
            self.dl_tasks.append(task)
            self.dl_body.setCurrentIndex(1)
            task.start()
            n += 1
        self.switch_mode("download")
        self.toast("已开始 %d 个下载任务" % n)

    def open_web_login(self):
        try:
            import web_login
        except Exception as e:
            QMessageBox.warning(self, "登录舱不可用", "WebEngine 组件加载失败：%s" % str(e)[:100])
            return
        platform = self.pan_platform_combo.currentData() or "quark"
        text = self.pan_url.text().strip()
        if text:
            import pan
            share = pan.parse_share(text)
            if share:
                platform = share["platform"]
        try:
            dlg = web_login.WebLoginDialog(platform, self, self.dark)
            def on_cookies(cookie_str):
                self.pan_cookie.setText(cookie_str)
                self.toast("已提取 %s 的登录状态" % platform)
            dlg.cookies_ready.connect(on_cookies)
            dlg.exec_()
        except Exception as e:
            import traceback
            try:
                lg = os.path.join(os.path.expanduser("~"), ".jisudownloader", "weblogin.log")
                os.makedirs(os.path.dirname(lg), exist_ok=True)
                open(lg, "a", encoding="utf-8").write("dialog ERR " + traceback.format_exc())
            except Exception:
                pass
            QMessageBox.warning(self, "登录窗口出错", str(e)[:150])

    def parse_pan(self):
        import pan
        text = self.pan_url.text().strip()
        if not text:
            self.toast("请先粘贴网盘分享链接")
            return
        picked = self.pan_platform_combo.currentData()
        share = pan.parse_share(text, force_platform=picked)
        if not share:
            self.pan_platform.setText("平台：未识别（检查链接格式）")
            self.toast("未识别的网盘链接")
            return
        self.pan_platform.setText("平台：%s · %s" % (share.get("platform_name", ""), share["share_id"][:16]))
        self.pan_parse_btn.setEnabled(False)
        self.pan_parse_btn.setText("解析中…")

        def work():
            try:
                cookies = self.pan_cookie.text().strip() or None
                token = cookies if share["platform"] == "github" else None
                files = pan.resolve(share, cookies=cookies, token=token)
                def ok():
                    self.pan_files.clear()
                    for f in files:
                        it = QListWidgetItem("%s  (%s)" % (f.get("name", "文件"), L.fmt_size(f.get("size", 0))))
                        it.setData(Qt.UserRole, f)
                        self.pan_files.addItem(it)
                    self.pan_dl_btn.setEnabled(bool(files))
                    self.pan_parse_btn.setEnabled(True)
                    self.pan_parse_btn.setText("解析文件列表")
                    self.toast("解析到 %d 个文件" % len(files))
                QTimer.singleShot(0, ok)
            except Exception as e:
                def bad(msg=str(e)):
                    self.pan_parse_btn.setEnabled(True)
                    self.pan_parse_btn.setText("解析文件列表")
                    self.pan_files.clear()
                    self.pan_dl_btn.setEnabled(False)
                    QMessageBox.warning(self, "解析失败", msg[:200])
                QTimer.singleShot(0, bad)
        threading.Thread(target=work, daemon=True).start()

    def download_pan_files(self):
        import pan
        items = self.pan_files.selectedItems()
        if not items:
            self.toast("请先选中要下载的文件")
            return
        cookies = self.pan_cookie.text().strip() or None
        resolved = []
        for it in items:
            f = it.data(Qt.UserRole)
            if f.get("platform") == "quark" and f.get("fid"):
                try:
                    resolved.extend(pan.quark_direct([f], cookies=cookies))
                except Exception as e:
                    QMessageBox.warning(self, "取直链失败", str(e)[:120])
                continue
            if str(f.get("url", "")).startswith("baidu://"):
                QMessageBox.information(self, "百度网盘", "百度直链需额外接口取链，当前版本暂未开放。请先用蓝奏云 / GitHub / 夸克 / UC。")
                continue
            if f.get("url"):
                resolved.append(f)
        for f in resolved:
            url = f.get("url", "")
            headers = dict(f.get("headers") or {})
            if cookies:
                headers["Cookie"] = cookies
            try:
                name, total, rng, final = http_dl.probe(url, headers=headers)
            except Exception as e:
                QMessageBox.warning(self, "取链失败", "%s：%s" % (f.get("name", "文件"), str(e)[:100]))
                continue
            default = os.path.join(os.path.expanduser("~"), "Downloads", f.get("name") or name)
            dest = QFileDialog.getSaveFileName(self, "保存到", default, "所有文件 (*.*)")[0]
            if not dest:
                continue
            card = DownloadTaskCard(dest, f.get("name") or name, self.threads_for_pan(), self.dark)
            self.dl_list.insertWidget(self.dl_list.count() - 1, card)
            task = http_dl.Task(url, dest, threads=self.threads_for_pan(), headers=headers,
                on_progress=lambda d, t, s, st, c=card: self._on_dl_progress(c, d, t, s, st),
                on_done=lambda ok, m, c=card: self._on_dl_done(c, ok, m))
            card.task = task
            self.dl_tasks.append(task)
            self.dl_body.setCurrentIndex(1)
            task.start()
        self.switch_mode("download")
        self.toast("已开始 %d 个网盘下载任务" % len(resolved))

    def threads_for_pan(self):
        return 8

    # ---------- HTTP 多线程下载 ----------
    def start_download(self):
        url = self.dl_url.text().strip()
        if not url.lower().startswith(("http://", "https://")):
            self.toast("请输入 http:// 或 https:// 开头的链接")
            return
        self.dl_btn.setEnabled(True)
        # 先 probe 文件名/大小
        try:
            name, total, rng, final = http_dl.probe(url)
        except Exception as e:
            self.toast("无法连接：%s" % str(e)[:60])
            return
        default = os.path.join(os.path.expanduser("~"), "Downloads", name)
        dest = QFileDialog.getSaveFileName(self, "保存到", default, "所有文件 (*.*)")[0]
        if not dest:
            return
        threads = int(self.dl_threads.currentText())
        card = DownloadTaskCard(dest, name, threads, self.dark)
        self.dl_list.insertWidget(self.dl_list.count() - 1, card)
        task = http_dl.Task(
            url, dest, threads=threads,
            on_progress=lambda d, t, s, st, c=card: self._on_dl_progress(c, d, t, s, st),
            on_done=lambda ok, m, c=card: self._on_dl_done(c, ok, m),
        )
        card.task = task
        self.dl_tasks.append(task)
        self.dl_body.setCurrentIndex(1)   # 有任务：切到任务列表
        task.start()
        self.dl_url.clear()
        self.toast("已开始下载：%s（%s，%d 线程）" % (name, L.fmt_size(total), task.threads if rng else 1))

    def _on_dl_progress(self, card, done, total, speed, state):
        card.update_progress(done, total, speed, state)
        active = sum(1 for t in self.dl_tasks if t.state in ("下载中", "准备中", "暂停"))
        self.dl_summary.setText("%d 个进行中" % active if active else "空闲")

    def _on_dl_done(self, card, ok, message):
        card.finish(ok, message)
        active = sum(1 for t in self.dl_tasks if t.state in ("下载中", "准备中", "暂停"))
        self.dl_summary.setText("%d 个进行中" % active if active else "空闲")

    def pause_current_download(self):
        active = [t for t in self.dl_tasks if t.state in ("下载中", "准备中")]
        paused = [t for t in self.dl_tasks if t.state == "暂停"]
        if active:
            for t in active:
                t.pause()
            self.toast("已暂停 %d 个任务" % len(active))
        elif paused:
            for t in paused:
                t.resume()
            self.toast("已继续 %d 个任务" % len(paused))
        else:
            self.toast("当前没有进行中的下载任务")

    def clear_finished_downloads(self):
        removed = 0
        for i in reversed(range(self.dl_list.count())):
            item = self.dl_list.itemAt(i)
            w = item.widget()
            if w is None or not isinstance(w, DownloadTaskCard):
                continue
            if w.finished:
                w.setParent(None); w.deleteLater(); removed += 1
        self.dl_tasks = [t for t in self.dl_tasks if t.state in ("下载中", "准备中", "暂停")]
        # 没有可见任务卡片时切回引导页
        has_card = any(isinstance(self.dl_list.itemAt(i).widget(), DownloadTaskCard)
                       for i in range(self.dl_list.count()))
        self.dl_body.setCurrentIndex(1 if has_card else 0)
        self.toast("已清除 %d 个任务" % removed)

    # ---------- 杂项 ----------
    def set_status(self, s):
        self.status.setText(s)

    def toast(self, s):
        self.status.setText(s)
        QTimer.singleShot(2600, lambda: self.set_status(
            ("房间已开启 · %d 人" % self.peer_count()) if (self.room_on and self.mode == "host")
            else ("已加入房间" if self.room_on else "未连接")))

    def closeEvent(self, e):
        if self.room_on:
            dlg = ConfirmDialog("退出", "房间正在运行，确定退出？", self.dark, self)
            dlg.exec_()
            if not dlg.result_ok:
                e.ignore(); return
        # 取消进行中的下载（保留断点）
        for t in self.dl_tasks:
            if t.state in ("下载中", "准备中", "暂停"):
                try:
                    t.cancel()
                except Exception:
                    pass
        try:
            if self.hub:
                self.hub.stop()
        except Exception:
            pass
        super().closeEvent(e)

def _setup_dpi():
    """高 DPI 适配：必须在创建 QApplication 之前调用。
    系统已缩放（>=125%）时交给 Qt 自动处理；4K 屏但系统仍是 100% 时强制放大，
    否则字小如蚂蚁、内容挤在左上角。"""
    if getattr(sys, "frozen", False):
        meipass = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
        os.environ.setdefault("QTWEBENGINE_RESOURCES_PATH", meipass)
        os.environ.setdefault("QTWEBENGINE_CHROMIUM_FLAGS", "--no-sandbox")
        cache = os.path.join(os.path.expanduser("~"), ".jisudownloader", "webcache")
        try:
            os.makedirs(cache, exist_ok=True)
            os.environ.setdefault("QTWEBENGINE_CACHE_PATH", cache)
        except Exception:
            pass
    try:
        QApplication.setAttribute(Qt.AA_EnableHighDpiScaling, True)
        QApplication.setAttribute(Qt.AA_UseHighDpiPixmaps, True)
        QApplication.setAttribute(Qt.AA_ShareOpenGLContexts, True)
    except Exception:
        pass
    if sys.platform != "win32":
        return
    try:
        import ctypes
        try:
            ctypes.windll.shcore.SetProcessDpiAwareness(2)  # Per-Monitor V2
        except Exception:
            ctypes.windll.user32.SetProcessDPIAware()
        sw = ctypes.windll.user32.GetSystemMetrics(0)       # 主屏物理宽(px)
        hdc = ctypes.windll.user32.GetDC(0)
        dpi = ctypes.windll.gdi32.GetDeviceCaps(hdc, 88)    # LOGPIXELSX
        ctypes.windll.user32.ReleaseDC(0, hdc)
        ratio = dpi / 96.0
        # 系统已在缩放 → Qt 自动处理即可；4K@100% → 强制放大到 ~1.6x
        if sw >= 3000 and ratio < 1.25:
            os.environ.setdefault("QT_SCALE_FACTOR", "1.6")
        elif ratio > 1.01:
            os.environ.setdefault("QT_AUTO_SCREEN_SCALE_FACTOR", "1")
    except Exception:
        pass


def main():
    _setup_dpi()
    app = QApplication(sys.argv)
    app.setApplicationName("极速下载器 Windows")
    # 基准字号兜底（Qt 默认 9pt 偏小）
    f = app.font()
    if f.pointSize() < 10:
        f.setPointSize(10)
    app.setFont(f)
    w = MainWindow()
    w.show()
    def _warm():
        try:
            import web_login
            web_login.warm_up()
        except Exception:
            pass
    QTimer.singleShot(800, _warm)
    if "--selftest-login" in sys.argv:
        def _selftest():
            try:
                import web_login
                dlg = web_login.WebLoginDialog("quark", w, w.dark)
                dlg.show()
                web_login._log("SELFTEST dialog shown")
                QTimer.singleShot(4000, app.quit)
            except Exception:
                lg = os.path.join(os.path.expanduser("~"), ".jisudownloader", "weblogin.log")
                os.makedirs(os.path.dirname(lg), exist_ok=True)
                open(lg, "a", encoding="utf-8").write("SELFTEST ERR " + traceback.format_exc())
                app.quit()
        QTimer.singleShot(500, _selftest)
    sys.exit(app.exec_())

if __name__ == "__main__":
    main()
