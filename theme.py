# -*- coding: utf-8 -*-
"""现代 QSS 主题（浅色 / 深色），卡片式设计，非 win32 原生控件观感"""

LIGHT = """
* { font-family: "Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", sans-serif; }
QWidget#root { background: #f2f5fb; }
QLabel { color: #1e2536; }
QFrame#card, .card { background: #ffffff; border: 1px solid #e4e9f2; border-radius: 14px; }
QLabel#title { font-size: 21px; font-weight: 700; color: #16213e; }
QLabel#sub { font-size: 13px; color: #8792a8; }
QLabel#h2 { font-size: 16px; font-weight: 700; color: #1e2536; }
QLabel#codebig { font-size: 42px; font-weight: 800; color: #2f6fed; letter-spacing: 8px; }
QLabel#addr { font-size: 13px; color: #55627a; }
QLabel#badge { color: #2f6fed; background: #eaf1ff; border-radius: 12px; padding: 3px 12px; font-weight: 600; }
QPushButton { background: #2f6fed; color: #fff; border: 0; border-radius: 9px; padding: 9px 18px; font-size: 14px; font-weight: 600; }
QPushButton:hover { background: #1f55c8; }
QPushButton:pressed { background: #17439e; }
QPushButton:disabled { background: #b9c6dd; color: #eef2f8; }
QPushButton#ghost { background: #eaf1ff; color: #2f6fed; }
QPushButton#ghost:hover { background: #dbe7ff; }
QPushButton#gray { background: #f0f2f6; color: #5a6a88; }
QPushButton#gray:hover { background: #e6eaf1; }
QPushButton#red { background: #fdeeee; color: #d64545; }
QPushButton#red:hover { background: #fbdede; }
QPushButton#tab { background: transparent; color: #7b869c; border-radius: 8px; }
QPushButton#tab:checked { background: #2f6fed; color: #fff; }
QPushButton#small { padding: 5px 11px; font-size: 12px; border-radius: 7px; }
QLineEdit { background: #fafbfe; border: 1px solid #e4e9f2; border-radius: 9px; padding: 9px 14px; font-size: 15px; color: #1e2536; }
QLineEdit:focus { border-color: #2f6fed; }
QLineEdit#code { font-size: 26px; letter-spacing: 8px; font-weight: 700; }
QTextEdit { background: #fff; border: 1px solid #e4e9f2; border-radius: 10px; font-size: 14px; }
QScrollArea { border: 0; background: transparent; }
QScrollBar:vertical { background: transparent; width: 8px; margin: 0; }
QScrollBar::handle:vertical { background: #c9d4e8; border-radius: 4px; min-height: 30px; }
QScrollBar::handle:vertical:hover { background: #aab9d4; }
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical { height: 0; }
QProgressBar { border: 0; height: 6px; border-radius: 3px; background: #e6ebf4; text-align: center; }
QProgressBar::chunk { background: #2f6fed; border-radius: 3px; }
QCheckBox { color: #55627a; font-size: 13px; }
QComboBox { background: #fafbfe; border: 1px solid #e4e9f2; border-radius: 8px; padding: 5px 10px; }
"""

DARK = """
* { font-family: "Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", sans-serif; }
QWidget#root { background: #151a26; }
QLabel { color: #e6ebf5; }
QFrame#card, .card { background: #1e2536; border: 1px solid #2c3547; border-radius: 14px; }
QLabel#title { font-size: 21px; font-weight: 700; color: #ffffff; }
QLabel#sub { font-size: 13px; color: #94a0b8; }
QLabel#h2 { font-size: 16px; font-weight: 700; color: #e6ebf5; }
QLabel#codebig { font-size: 42px; font-weight: 800; color: #6ea0ff; letter-spacing: 8px; }
QLabel#addr { font-size: 13px; color: #aeb9cf; }
QLabel#badge { color: #8fb6ff; background: #24304a; border-radius: 12px; padding: 3px 12px; font-weight: 600; }
QPushButton { background: #2f6fed; color: #fff; border: 0; border-radius: 9px; padding: 9px 18px; font-size: 14px; font-weight: 600; }
QPushButton:hover { background: #447ff0; }
QPushButton:pressed { background: #1f55c8; }
QPushButton:disabled { background: #39445a; color: #6b7590; }
QPushButton#ghost { background: #24304a; color: #8fb6ff; }
QPushButton#ghost:hover { background: #2d3b5a; }
QPushButton#gray { background: #2a3142; color: #aeb9cf; }
QPushButton#gray:hover { background: #333c50; }
QPushButton#red { background: #3a2428; color: #f08a8a; }
QPushButton#red:hover { background: #4a2a2f; }
QPushButton#tab { background: transparent; color: #8894ab; border-radius: 8px; }
QPushButton#tab:checked { background: #2f6fed; color: #fff; }
QPushButton#small { padding: 5px 11px; font-size: 12px; border-radius: 7px; }
QLineEdit { background: #171d2b; border: 1px solid #2c3547; border-radius: 9px; padding: 9px 14px; font-size: 15px; color: #e6ebf5; }
QLineEdit:focus { border-color: #2f6fed; }
QLineEdit#code { font-size: 26px; letter-spacing: 8px; font-weight: 700; }
QTextEdit { background: #1e2536; border: 1px solid #2c3547; border-radius: 10px; font-size: 14px; color: #e6ebf5; }
QScrollArea { border: 0; background: transparent; }
QScrollBar:vertical { background: transparent; width: 8px; margin: 0; }
QScrollBar::handle:vertical { background: #3a4459; border-radius: 4px; min-height: 30px; }
QScrollBar::handle:vertical:hover { background: #4a5670; }
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical { height: 0; }
QProgressBar { border: 0; height: 6px; border-radius: 3px; background: #2a3142; text-align: center; }
QProgressBar::chunk { background: #2f6fed; border-radius: 3px; }
QCheckBox { color: #aeb9cf; font-size: 13px; }
QComboBox { background: #171d2b; border: 1px solid #2c3547; border-radius: 8px; padding: 5px 10px; color: #e6ebf5; }
"""
