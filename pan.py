# -*- coding: utf-8 -*-
import re
import json
import time
import urllib.request
import urllib.parse
import urllib.error

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

PLATFORMS = {
    "quark": "夸克网盘", "uc": "UC网盘", "xunlei": "迅雷网盘", "baidu": "百度网盘",
    "c139": "139和彩云", "pan123": "123云盘", "pan115": "115网盘", "guangya": "光鸭云盘",
    "ilanzou": "蓝奏云优享", "lanzou": "蓝奏云", "github": "GitHub",
}

URL_RE = re.compile(r"https?://[^\s，。；;)）\]】\"']+")
PWD_URL_RE = re.compile(r"[?&](?:pwd|password|passcode|pass)=([A-Za-z0-9]{4,8})", re.I)
PWD_TEXT_RE = re.compile(r"(?:提取码|访问码|密码)[：:\s]*([A-Za-z0-9]{4,8})")

PATTERNS = [
    ("quark", re.compile(r"pan\.quark\.cn/s/([A-Za-z0-9]+)", re.I)),
    ("uc", re.compile(r"drive\.uc\.cn/s/([A-Za-z0-9]+)", re.I)),
    ("xunlei", re.compile(r"pan\.xunlei\.com/s/([A-Za-z0-9_-]+)", re.I)),
    ("baidu", re.compile(r"pan\.baidu\.com/s/(1[A-Za-z0-9_-]+)", re.I)),
    ("c139", re.compile(r"yun\.139\.com/shareweb/.*?/w/i/([A-Za-z0-9_-]+)", re.I)),
    ("pan123", re.compile(r"123(?:865|pan)\.(?:com|cn)/s/([A-Za-z0-9]+-[A-Za-z0-9]+)", re.I)),
    ("pan123", re.compile(r"share\.123pan\.cn/123pan/([A-Za-z0-9-]+)", re.I)),
    ("pan115", re.compile(r"115(?:cdn|rc)?\.com/s/(sw[A-Za-z0-9]+)", re.I)),
    ("guangya", re.compile(r"guangyapan\.com/s/([A-Za-z0-9_-]+)", re.I)),
    ("ilanzou", re.compile(r"(?:^|[/.])ilanzou\.(?:com|net|org|cn)/s/([A-Za-z0-9_-]+)", re.I)),
    ("lanzou", re.compile(r"(?:^|[/.])(?:lanzou[a-z0-9-]*|lan[zs]o[ux])\.(?:com|net|org|cn)/([A-Za-z0-9]+)", re.I)),
]

GITHUB_RE = re.compile(r"github\.com/([^/]+)/([^/]+)(?:/(?:blob|tree)/(.+))?", re.I)


def parse_share(text, force_platform=None):
    text = (text or "").strip()
    m = URL_RE.search(text)
    if not m:
        return None
    url = m.group(0).rstrip("。，；;)）")
    pwd = PWD_URL_RE.search(url)
    code = pwd.group(1) if pwd else None
    if not code:
        tp = PWD_TEXT_RE.search(text)
        code = tp.group(1) if tp else None
    if force_platform and force_platform != "github":
        for platform, pat in PATTERNS:
            if platform != force_platform:
                continue
            hit = pat.search(url)
            if hit:
                sid = hit.group(1)
                if platform == "baidu":
                    sid = sid[1:]
                return {"platform": platform, "platform_name": PLATFORMS.get(platform, platform),
                        "share_id": sid, "pwd": code, "url": url}
        return {"platform": force_platform, "platform_name": PLATFORMS.get(force_platform, force_platform),
                "share_id": url.rstrip("/").split("/")[-1].split("?")[0], "pwd": code, "url": url}
    for platform, pat in PATTERNS:
        hit = pat.search(url)
        if hit:
            sid = hit.group(1)
            if platform == "baidu":
                sid = sid[1:]
            if platform == "pan115" and len(sid) < 3:
                continue
            return {"platform": platform, "platform_name": PLATFORMS.get(platform, platform),
                    "share_id": sid, "pwd": code, "url": url}
    gh = GITHUB_RE.search(url)
    if gh:
        return {"platform": "github", "platform_name": "GitHub",
                "share_id": gh.group(3) or "", "pwd": None, "url": url,
                "owner": gh.group(1), "repo": gh.group(2)}
    return None


def _http(url, headers=None, data=None, method=None, timeout=20):
    h = {"User-Agent": UA, "Accept": "application/json, text/plain, */*"}
    h.update(headers or {})
    body = None
    if data is not None:
        body = urllib.parse.urlencode(data).encode("utf-8")
        h["Content-Type"] = "application/x-www-form-urlencoded"
    req = urllib.request.Request(url, data=body, headers=h, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read().decode("utf-8", "replace")
        try:
            return json.loads(raw), dict(r.headers)
        except Exception:
            return {"_text": raw}, dict(r.headers)


class ResolveError(Exception):
    pass


def resolve_github(share, token=None):
    owner, repo, path = share.get("owner"), share.get("repo"), share.get("share_id", "")
    headers = {"Accept": "application/vnd.github+json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    ref = None
    if "/" in path:
        first = path.split("/", 1)[0]
        if first in ("blob", "tree"):
            parts = path.split("/", 2)
            ref = parts[1]
            path = parts[2] if len(parts) > 2 else ""
        else:
            ref = first
            path = path.split("/", 1)[1]
    api = "https://api.github.com/repos/%s/%s/contents/%s" % (
        owner, repo, "/".join(urllib.parse.quote(seg) for seg in path.split("/") if seg))
    if ref:
        api += "?ref=" + urllib.parse.quote(ref)
    try:
        obj, _ = _http(api, headers)
    except Exception as e:
        raise ResolveError("GitHub 解析失败：%s" % str(e)[:80])
    if isinstance(obj, dict) and "download_url" in obj and obj["download_url"]:
        return [{"name": obj.get("name") or path.split("/")[-1], "size": obj.get("size", 0),
                 "url": obj["download_url"], "path": path,
                 "headers": {"Referer": "https://github.com/"}}]
    if isinstance(obj, list):
        out = []
        for it in obj:
            if it.get("type") == "file" and it.get("download_url"):
                out.append({"name": it["name"], "size": it.get("size", 0),
                            "url": it["download_url"], "path": it.get("path", ""),
                            "headers": {"Referer": "https://github.com/"}})
        return out
    raise ResolveError("GitHub 目录为空或需要 Token")


def resolve_lanzou(share):
    sid = share["share_id"]
    host = "https://www.ilanzou.com" if share["platform"] == "ilanzou" else "https://www.lanzoui.com"
    url = "%s/s/%s" % (host, sid)
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=20) as r:
            html = r.read().decode("utf-8", "replace")
    except Exception as e:
        raise ResolveError("蓝奏云页面获取失败：%s" % str(e)[:80])
    files = []
    for m in re.finditer(r"id=\"(\d+)\"[^>]*>.*?class=\"file-name\"[^>]*>([^<]+)<", html, re.S):
        files.append({"name": m.group(2).strip(), "size": 0, "fid": m.group(1), "url": url})
    if not files:
        for m in re.finditer(r"fn\d+_(\d+)[^>]*>([^<]+)<", html):
            files.append({"name": m.group(2).strip(), "size": 0, "fid": m.group(1), "url": url})
    if not files:
        raise ResolveError("未解析到文件列表（蓝奏云页面结构可能已变更）")
    return files


def lanzou_direct(share, fid):
    url = "https://www.lanzoui.com/ajaxm.php"
    data = {"action": "downprocess", "signs": share["share_id"], "sign": "", "p": share.get("pwd") or "", "fk": fid}
    try:
        obj, hdr = _http(url, data=data, headers={"Referer": share["url"]})
    except Exception as e:
        raise ResolveError("蓝奏云取链失败：%s" % str(e)[:80])
    if obj.get("zt") != 1 or not obj.get("url"):
        raise ResolveError(obj.get("inf") or "蓝奏云取链失败")
    file_url = obj["url"]
    if not file_url.startswith("http"):
        file_url = "https://" + file_url
    return [{"name": obj.get("inf") or "download", "size": 0, "url": file_url,
             "headers": {"Referer": "https://www.lanzoui.com/"}}]


PLATFORM_NEEDS_LOGIN = {"quark", "uc", "xunlei", "baidu", "c139", "pan123", "pan115", "guangya"}


def _cookie_headers(cookies, referer):
    h = {"Referer": referer, "Origin": referer.rstrip("/")}
    if cookies:
        h["Cookie"] = cookies
    return h


def resolve_quark(share, cookies=None):
    sid, pwd = share["share_id"], share.get("pwd") or ""
    base = "https://drive-pc.quark.cn/1/clouddrive/share/sharepage"
    hdr = _cookie_headers(cookies, "https://pan.quark.cn/")
    hdr["Content-Type"] = "application/json"
    body = json.dumps({"pwd_id": sid, "passcode": pwd}).encode("utf-8")
    req = urllib.request.Request(base + "/token?pr=ucpro&fr=pc", data=body, headers=hdr, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            tok = json.load(r)
    except Exception as e:
        raise ResolveError("夸克取凭证失败：%s" % str(e)[:80])
    if tok.get("status") != 200 or not tok.get("data", {}).get("stoken"):
        raise ResolveError(tok.get("message") or "夸克分享需要提取码或已失效")
    stoken = tok["data"]["stoken"]
    params = urllib.parse.urlencode({"pr": "ucpro", "fr": "pc", "pwd_id": sid, "stoken": stoken,
        "pdir_fid": "0", "force": "0", "_page": "1", "_size": "200",
        "_sort": "file_type:asc,updated_at:desc"})
    try:
        det, _ = _http(base + "/detail?" + params, headers=hdr, timeout=20)
    except Exception as e:
        raise ResolveError("夸克列目录失败：%s" % str(e)[:80])
    if det.get("status") != 200:
        raise ResolveError(det.get("message") or "夸克列目录失败")
    out = []
    for it in det.get("data", {}).get("list", []):
        if it.get("dir"):
            continue
        out.append({"name": it.get("file_name", "file"), "size": it.get("size", 0),
                    "fid": it.get("fid"), "share_fid_token": it.get("share_fid_token"),
                    "sid": sid, "stoken": stoken, "platform": "quark", "headers": hdr})
    if not out:
        raise ResolveError("分享目录中没有文件")
    return out


def quark_direct(files, cookies=None):
    hdr = _cookie_headers(cookies, "https://pan.quark.cn/")
    hdr["Content-Type"] = "application/json"
    body = json.dumps({"fids": [f["fid"] for f in files], "pwd_id": files[0]["sid"],
        "stoken": files[0]["stoken"],
        "share_fid_tokens": [f.get("share_fid_token") for f in files]}).encode("utf-8")
    req = urllib.request.Request("https://drive-pc.quark.cn/1/clouddrive/share/sharepage/download?pr=ucpro&fr=pc",
                                 data=body, headers=hdr, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            obj = json.load(r)
    except Exception as e:
        raise ResolveError("夸克取直链失败：%s" % str(e)[:80])
    if obj.get("status") != 200:
        raise ResolveError(obj.get("message") or "夸克取直链失败")
    urls = obj.get("data", {}).get("download_url", [])
    out = []
    for f, u in zip(files, urls):
        out.append({"name": f["name"], "size": f.get("size", 0), "url": u,
                    "headers": {"Referer": "https://pan.quark.cn/", "Cookie": cookies or ""}})
    return out


def resolve_uc(share, cookies=None):
    sid, pwd = share["share_id"], share.get("pwd") or ""
    base = "https://drive-pc.quark.cn/1/clouddrive/share/sharepage"
    hdr = _cookie_headers(cookies, "https://drive.uc.cn/")
    hdr["Content-Type"] = "application/json"
    body = json.dumps({"pwd_id": sid, "passcode": pwd}).encode("utf-8")
    req = urllib.request.Request(base + "/token?pr=ucpro&fr=pc", data=body, headers=hdr, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            tok = json.load(r)
    except Exception as e:
        raise ResolveError("UC 取凭证失败：%s" % str(e)[:80])
    if tok.get("status") != 200 or not tok.get("data", {}).get("stoken"):
        raise ResolveError(tok.get("message") or "UC 分享需要提取码或已失效")
    stoken = tok["data"]["stoken"]
    params = urllib.parse.urlencode({"pr": "ucpro", "fr": "pc", "pwd_id": sid, "stoken": stoken,
        "pdir_fid": "0", "force": "0", "_page": "1", "_size": "200",
        "_sort": "file_type:asc,updated_at:desc"})
    try:
        det, _ = _http(base + "/detail?" + params, headers=hdr, timeout=20)
    except Exception as e:
        raise ResolveError("UC 列目录失败：%s" % str(e)[:80])
    if det.get("status") != 200:
        raise ResolveError(det.get("message") or "UC 列目录失败")
    out = []
    for it in det.get("data", {}).get("list", []):
        if it.get("dir"):
            continue
        out.append({"name": it.get("file_name", "file"), "size": it.get("size", 0),
                    "fid": it.get("fid"), "share_fid_token": it.get("share_fid_token"),
                    "sid": sid, "stoken": stoken, "platform": "uc", "headers": hdr})
    if not out:
        raise ResolveError("分享目录中没有文件")
    return out


def resolve_baidu(share, cookies=None):
    sid, pwd = share["share_id"], share.get("pwd") or ""
    hdr = _cookie_headers(cookies, "https://pan.baidu.com/")
    params = urllib.parse.urlencode({"surl": sid, "pwd": pwd, "page": "1", "num": "1000"})
    try:
        obj, _ = _http("https://pan.baidu.com/share/list?" + params, headers=hdr, timeout=20)
    except Exception as e:
        raise ResolveError("百度列目录失败：%s" % str(e)[:80])
    if obj.get("errno") not in (0, None):
        raise ResolveError("百度返回错误 %s（提取码或 Cookie 可能无效）" % obj.get("errno"))
    out = []
    for it in obj.get("list", []):
        if int(it.get("is_dir", 0)) == 1:
            continue
        out.append({"name": it.get("filename", "file"), "size": int(it.get("size", 0)),
                    "url": "baidu://" + it.get("fs_id", ""), "fs_id": it.get("fs_id"),
                    "share_id": sid, "pwd": pwd, "platform": "baidu", "headers": hdr})
    if not out:
        raise ResolveError("分享目录中没有文件")
    return out


def list_my_files(platform, cookies, fid="0"):
    """列出登录账号自己网盘里的文件/文件夹。
    返回 [{"name","size","is_dir","fid","path","platform"}]，fid 用于进入子目录。"""
    if not cookies:
        raise ResolveError("请先点「网页登录」获取 Cookie")
    if platform in ("quark", "uc"):
        base = "https://drive-pc.quark.cn/1/clouddrive" if platform == "quark" else "https://drive.uc.cn/1/clouddrive"
        ref = "https://pan.quark.cn/" if platform == "quark" else "https://drive.uc.cn/"
        hdr = _cookie_headers(cookies, ref)
        params = urllib.parse.urlencode({"pr": "ucpro", "fr": "pc", "pdir_fid": fid or "0",
            "_page": "1", "_size": "200", "_fetch_total": "1", "_fetch_sub_dirs": "0",
            "_sort": "file_type:asc,updated_at:desc"})
        try:
            obj, _ = _http(base + "/file/sort?" + params, headers=hdr, timeout=25)
        except Exception as e:
            raise ResolveError("%s 文件列表失败：%s" % (platform, str(e)[:80]))
        if obj.get("code") not in (0, None):
            raise ResolveError(obj.get("message") or "%s 返回错误 %s" % (platform, obj.get("code")))
        out = []
        for it in obj.get("data", {}).get("list", []):
            out.append({"name": it.get("file_name", "file"), "size": int(it.get("size", 0)),
                        "is_dir": bool(it.get("dir")), "fid": it.get("fid", ""),
                        "path": fid, "platform": platform, "headers": hdr})
        return out
    if platform == "baidu":
        hdr = _cookie_headers(cookies, "https://pan.baidu.com/")
        try:
            obj, _ = _http("https://pan.baidu.com/api/list?" + urllib.parse.urlencode(
                {"dir": fid or "/", "page": "1", "num": "1000", "order": "name", "desc": "0"}),
                headers=hdr, timeout=25)
        except Exception as e:
            raise ResolveError("百度文件列表失败：%s" % str(e)[:80])
        if obj.get("errno") not in (0, None):
            raise ResolveError("百度返回错误 %s（Cookie 可能无效）" % obj.get("errno"))
        out = []
        for it in obj.get("list", []):
            out.append({"name": it.get("filename", "file"), "size": int(it.get("size", 0)),
                        "is_dir": int(it.get("is_dir", 0)) == 1, "fid": str(it.get("fs_id", "")),
                        "path": it.get("path", fid), "platform": "baidu", "headers": hdr})
        return out
    raise ResolveError("%s 暂不支持浏览账号文件，当前可用：夸克 / UC / 百度" % PLATFORMS.get(platform, platform))


def myfile_direct(files, cookies):
    """把账号里的文件换成下载直链。返回 [{name,size,url,headers}]。"""
    if not files:
        return []
    platform = files[0].get("platform", "quark")
    if platform in ("quark", "uc"):
        base = "https://drive-pc.quark.cn/1/clouddrive" if platform == "quark" else "https://drive.uc.cn/1/clouddrive"
        ref = "https://pan.quark.cn/" if platform == "quark" else "https://drive.uc.cn/"
        hdr = _cookie_headers(cookies, ref)
        hdr["Content-Type"] = "application/json"
        body = json.dumps({"fids": [f["fid"] for f in files]}).encode("utf-8")
        req = urllib.request.Request(base + "/file/download?" + urllib.parse.urlencode(
            {"pr": "ucpro", "fr": "pc"}), data=body, headers=hdr, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=25) as r:
                obj = json.load(r)
        except Exception as e:
            raise ResolveError("%s 取直链失败：%s" % (platform, str(e)[:80]))
        if obj.get("code") not in (0, None):
            raise ResolveError(obj.get("message") or "取直链失败")
        data = obj.get("data") or []
        url_map = {}
        for d in data:
            if isinstance(d, dict):
                url_map[d.get("fid", "")] = d.get("download_url", "")
            elif isinstance(d, str):
                url_map.setdefault("", d)
        out = []
        urls = [url_map.get(f.get("fid", "")) or (data[i].get("download_url") if i < len(data) and isinstance(data[i], dict) else None)
                for i, f in enumerate(files)]
        for f, u in zip(files, urls):
            if u:
                out.append({"name": f["name"], "size": f.get("size", 0), "url": u, "headers": dict(hdr)})
        if not out:
            raise ResolveError("未取到下载直链（文件可能需会员或已失效）")
        return out
    if platform == "baidu":
        raise ResolveError("百度账号文件的直链接口适配中，当前可用：夸克 / UC")
    raise ResolveError("该平台暂不支持账号文件下载")


def resolve(share, cookies=None, token=None):
    p = share["platform"]
    if p == "github":
        return resolve_github(share, token)
    if p in ("lanzou", "ilanzou"):
        return resolve_lanzou(share)
    if p == "quark":
        return resolve_quark(share, cookies)
    if p == "uc":
        return resolve_uc(share, cookies)
    if p == "baidu":
        return resolve_baidu(share, cookies)
    if p in PLATFORM_NEEDS_LOGIN:
        if not cookies:
            raise ResolveError("%s 需要登录 Cookie。请在网盘面板点「网页登录」。" % share["platform_name"])
        raise ResolveError("%s 取链接口仍在适配中；请关注后续版本。当前可用：GitHub / 蓝奏云 / 夸克 / UC / 百度。" % share["platform_name"])
    raise ResolveError("暂不支持的平台：%s" % p)
