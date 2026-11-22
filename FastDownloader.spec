# -*- mode: python ; coding: utf-8 -*-


a = Analysis(
    ['D:\\一些小项目\\FastDownloaderWindows\\main.py'],
    pathex=[],
    binaries=[],
    datas=[('D:\\一些小项目\\FastDownloaderWindows\\assets\\lan.html', 'assets')],
    hiddenimports=['PyQt5.QtMultimedia', 'PyQt5.QtMultimediaWidgets', 'qrcode', 'PIL', 'pan', 'myfiles', 'web_login', 'PyQt5.QtWebEngineWidgets', 'PyQt5.QtWebEngineCore', 'PyQt5.QtNetwork'],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=['tkinter', 'matplotlib', 'numpy'],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name='FastDownloader',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=['D:\\一些小项目\\FastDownloaderWindows\\app.ico'],
)
