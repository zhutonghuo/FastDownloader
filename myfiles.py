# -*- coding: utf-8 -*-
import os
import pan
from PyQt5.QtCore import Qt, QTimer
from PyQt5.QtWidgets import (
    QDialog, QVBoxLayout, QHBoxLayout, QLabel, QPushButton, QListWidget,
    QListWidgetItem, QMessageBox, QInputDialog,
)


class MyFilesDialog(QDialog):
    def __init__(self, platform, cookies, parent=None, dark=False):
        super().__init__(parent)
        self.platform = platform
        self.cookies = cookies
        self.stack = [("0", "全部文件")]
        self.setWindowTitle("我的文件 · " + pan.PLATFORMS.get(platform, platform))
        self.resize(880, 620)
        v = QVBoxLayout(self)
        v.setContentsMargins(12, 12, 12, 12)

        top = QHBoxLayout()
        self.path_lbl = QLabel("全部文件"); top.addWidget(self.path_lbl, 1)
        back = QPushButton("← 上级"); back.clicked.connect(self.go_up); top.addWidget(back)
        refresh = QPushButton("刷新"); refresh.clicked.connect(self.load); top.addWidget(refresh)
        v.addLayout(top)

        self.list = QListWidget()
        self.list.setSelectionMode(QListWidget.ExtendedSelection)
        self.list.itemDoubleClicked.connect(self.on_double)
        v.addWidget(self.list, 1)

        bottom = QHBoxLayout()
        self.info = QLabel("双击文件夹进入，选中文件后点下载")
        bottom.addWidget(self.info, 1)
        dl = QPushButton("下载选中"); dl.setObjectName("primary")
        dl.clicked.connect(self.download); bottom.addWidget(dl)
        v.addLayout(bottom)

        QTimer.singleShot(0, self.load)

    def load(self):
        fid, _ = self.stack[-1]
        self.path_lbl.setText(" / ".join(n for _, n in self.stack))
        self.list.clear()
        self.info.setText("加载中…")
        def work():
            try:
                files = pan.list_my_files(self.platform, self.cookies, fid)
                QTimer.singleShot(0, lambda: self.show_files(files))
            except Exception as e:
                QTimer.singleShot(0, lambda m=str(e): self.info.setText("加载失败：" + m[:120]))
        import threading
        threading.Thread(target=work, daemon=True).start()

    def show_files(self, files):
        self.list.clear()
        for f in files:
            icon = "📁" if f.get("is_dir") else "📄"
            size = "" if f.get("is_dir") else ("  %.1f KB" % (f.get("size", 0) / 1024.0))
            it = QListWidgetItem("%s  %s%s" % (icon, f.get("name", "文件"), size))
            it.setData(Qt.UserRole, f)
            self.list.addItem(it)
        self.info.setText("共 %d 项 · 双击文件夹进入，选中文件后点下载" % len(files))

    def on_double(self, item):
        f = item.data(Qt.UserRole)
        if f.get("is_dir"):
            self.stack.append((f.get("fid", "0"), f.get("name", "文件夹")))
            self.load()

    def go_up(self):
        if len(self.stack) > 1:
            self.stack.pop()
            self.load()

    def download(self):
        items = self.list.selectedItems()
        files = [it.data(Qt.UserRole) for it in items if not it.data(Qt.UserRole).get("is_dir")]
        if not files:
            QMessageBox.information(self, "提示", "请先选中要下载的文件（文件夹请双击进入）")
            return
        self.info.setText("正在取直链…")
        def work():
            try:
                direct = pan.myfile_direct(files, self.cookies)
                QTimer.singleShot(0, lambda: self.accept_files(direct))
            except Exception as e:
                QTimer.singleShot(0, lambda m=str(e): (self.info.setText("取直链失败：" + m[:120]),
                                  QMessageBox.warning(self, "取直链失败", m[:200])))
        import threading
        threading.Thread(target=work, daemon=True).start()

    def accept_files(self, direct):
        self._result = direct
        self.accept()

    def result_files(self):
        return getattr(self, "_result", [])
