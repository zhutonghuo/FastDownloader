# 极速下载器 Windows 版（FastDownloader-Windows）

一个**单文件、免安装**的 Windows 局域网互传工具，与安卓版「极速下载器」（FastDownloader）**完全互通**。纯 PyQt5 原生界面（现代卡片式设计，不是 win32 原生丑控件，也不是网页套壳），双击即用。

## 与安卓版互通

本 Windows 版**完整复刻了安卓版 `LanHub` / `LanClient` 的 HTTP 协议**（`/api/join`、`/api/chat`、`/api/upload`、`/api/download`、`/api/recall`、`/api/qr` 等，JSON 字段、口令规则、文件存储、撤回逻辑完全一致），因此两个方向都能互通：

- **安卓开房间 → Windows 加入**：安卓 App 点「开启房间」，把地址（`http://IPv4:端口`）和 4~6 位口令告诉 Windows，Windows 选「加入房间」填入即可。Windows 发的文字/文件、撤回，安卓端和其他浏览器访客都能实时看到。
- **Windows 开房间 → 安卓加入**：Windows 点「开房间」，界面显示口令、局域网地址和二维码；安卓 App 选「加入房间」扫码或手填地址+口令即可连入。
- **浏览器访客**：任意同一 Wi-Fi 下的手机/电脑浏览器打开 `http://IPv4:端口/?code=口令` 即可加入聊天互传（房间内嵌的网页 `assets/lan.html` 与安卓版同源）。
- 三者共用同一个房间、同一份消息记录、同一个文件池，**互不经过任何公网服务器**，数据只在本局域网传输。

## 功能

- **HTTP 多线程高速下载**：输入 http/https 直链，2~32 线程 Range 分片并发，实时进度/速度，支持暂停/继续、取消、断点续传（`.part` 文件），服务器不支持 Range 时自动单线程
- 开房间 / 加入房间（双向互通）
- 微信式聊天：文本 + 文件气泡，自己靠右、他人靠左，带发送时间
- 上传单个文件 / 整个文件夹（保留相对目录结构）
- 下载文件到本机任意位置
- **内置播放器**：图片直接看、视频/音频用内置 QMediaPlayer 播放（不调用外部播放器）
- 仅能撤回自己发送的消息（文本和文件都支持，对所有成员生效）
- 清空聊天显示（仅本机生效）、未读新消息提示
- 浅色 / 深色主题切换
- 自定义无边框标题栏，现代化 UI

## 下载与使用

从 [Releases](https://github.com/zhutonghuo/fastdownloader-windows/releases) 下载 `FastDownloader.exe`（单文件，约 35MB），双击运行即可，无需 Python 环境。

也可从官网下载：<https://zhutonghuo.xyz/fdwin/>（或直链 <https://zhutonghuo.xyz/fdwin/FastDownloader.exe>）

1. 确保 Windows 与安卓在同一 Wi-Fi / 局域网。
2. 一端开房间（显示口令与地址），另一端加入。
3. 开始聊天、传文件。

## 从源码构建

需要 Python 3.12 + PyQt5 + pyinstaller + qrcode：

```bash
pip install pyinstaller PyQt5 qrcode pillow
python build.py
# 产物：dist/FastDownloader.exe
```

协议自检（离线，验证与安卓协议一致性，21 项）：

```bash
python test_protocol.py
```

HTTP 下载引擎自检（本地限速服务器，13 项）：

```bash
python test_httpdl.py
```

## 项目结构

| 文件 | 说明 |
| --- | --- |
| `main.py` | PyQt5 主界面（HTTP 下载、开/加入房间、聊天、上传下载、预览、主题） |
| `http_dl.py` | HTTP 多线程分片下载引擎（Range/断点续传/暂停/取消） |
| `lan_core.py` | 协议核心：复刻安卓 LanHub 服务端 + LanClient 客户端 |
| `bubble.py` | 聊天气泡自定义控件 |
| `theme.py` | 现代 QSS 浅色/深色主题 |
| `assets/lan.html` | 房间内嵌网页（供浏览器/安卓访客，与安卓版同源） |
| `build.py` | 单文件 exe 打包脚本 |
| `test_protocol.py` | 协议一致性自检 |

## 版本

- v1.7：新增「我的文件」——登录后浏览自己网盘账号的文件/文件夹（夸克/UC/百度），文件夹导航、勾选下载。
- v1.61：WebView 登录舱提速——启动后台预热 Chromium、启用 GPU 渲染、移除 disable-gpu、加加载进度提示。
- v1.6：网盘下载改为「下拉框选平台」（11 家+自动识别），修复夸克解析未定义变量崩溃。
- v1.52：修复打包版点击网页登录闪退（WebEngine 资源路径/缓存目录/异常兜底）。
- v1.51：修复 WebEngine 登录舱初始化顺序错误（QtWebEngineWidgets 必须在 QApplication 之前导入）。
- v1.5：内置 WebView 登录舱（自动提取 Cookie + 手动完成备选）；网盘取链支持夸克/UC/百度。
- v1.4：新增网盘下载（11 家平台链接识别 + GitHub/蓝奏云解析），高速引擎注入云析式慢连接抢占、Range 容忍与严格写入校验。
- v1.31：修复房间二维码扭曲（QImage 缺少 bytesPerLine 导致每行错位）。
- v1.3：修复 4K / 高 DPI 屏字小如蚂蚁、内容挤在左上角的问题（自动缩放+限宽居中）；修复 QSS 选择器失配导致卡片/按钮样式全部未生效；下载任务区新增空状态引导。
- v1.2：界面精简——未连接时收起用不到的聊天互传区，改为简洁引导占位。
- v1.1：新增 HTTP 多线程高速下载（Range 分片/断点续传/暂停继续），界面重构适配多分辨率。
- v1.0：首个 Windows 版，与安卓版 v1.12 互通。
