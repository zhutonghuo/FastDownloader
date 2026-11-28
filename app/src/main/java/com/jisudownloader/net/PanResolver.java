package com.jisudownloader.net;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PanResolver {
    public static final class Share {
        public String platform;
        public String platformName;
        public String shareId;
        public String pwd;
        public String url;
        public String owner;
        public String repo;
    }

    public static final class PanFile {
        public String name;
        public long size;
        public String url;
        public String referer;
        public String cookie;
        public String fid;
        public String share_fid_token;
        public String stoken;
    }

    public static final class ResolveException extends Exception {
        public ResolveException(String m) { super(m); }
    }

    private static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36";

    /** 主域名：登录态 Cookie 常挂在主域上，提取/校验都要覆盖主域而不是 pan.* 子域 */
    public static final Map<String, String> MAIN_DOMAINS = new LinkedHashMap<>();
    /** 各平台在主域名下的登录标志 Cookie */
    public static final Map<String, String[]> LOGIN_COOKIE_HINTS = new LinkedHashMap<>();

    static {
        MAIN_DOMAINS.put("quark", "quark.cn");
        MAIN_DOMAINS.put("uc", "uc.cn");
        MAIN_DOMAINS.put("xunlei", "xunlei.com");
        MAIN_DOMAINS.put("baidu", "baidu.com");
        MAIN_DOMAINS.put("c139", "139.com");
        MAIN_DOMAINS.put("pan123", "123pan.cn");
        MAIN_DOMAINS.put("pan115", "115.com");
        MAIN_DOMAINS.put("guangya", "guangyapan.com");
        MAIN_DOMAINS.put("ilanzou", "ilanzou.com");
        MAIN_DOMAINS.put("lanzou", "lanzoui.com");
        MAIN_DOMAINS.put("github", "github.com");

        LOGIN_COOKIE_HINTS.put("quark", new String[]{"__pus", "__puus"});
        LOGIN_COOKIE_HINTS.put("uc", new String[]{"__pus", "__puus"});
        LOGIN_COOKIE_HINTS.put("baidu", new String[]{"BDUSS", "STOKEN"});
        LOGIN_COOKIE_HINTS.put("pan115", new String[]{"UID", "CID"});
        LOGIN_COOKIE_HINTS.put("github", new String[]{"user_session", "logged_in"});
    }

    public static String mainDomain(String platform) {
        String d = MAIN_DOMAINS.get(platform);
        return d == null ? "" : d;
    }

    public static boolean hasLoginCookie(String platform, String cookie) {
        String[] hints = LOGIN_COOKIE_HINTS.get(platform);
        if (hints == null || cookie == null) return false;
        java.util.Set<String> names = new java.util.HashSet<>();
        for (String part : cookie.split(";")) {
            int i = part.indexOf('=');
            if (i > 0) names.add(part.trim().substring(0, i));
        }
        for (String h : hints) if (names.contains(h)) return true;
        return false;
    }
    private static final Pattern URL_RE = Pattern.compile("https?://[^\\s，。；;)）\\]\"']+");
    private static final Pattern PWD_URL = Pattern.compile("[?&](?:pwd|password|passcode|pass)=([A-Za-z0-9]{4,8})", Pattern.CASE_INSENSITIVE);
    private static final Pattern PWD_TEXT = Pattern.compile("(?:提取码|访问码|密码)[：:\\s]*([A-Za-z0-9]{4,8})");

    private static final String[][] PATTERNS = {
        {"quark", "夸克网盘", "pan\\.quark\\.cn/s/([A-Za-z0-9]+)"},
        {"uc", "UC网盘", "drive\\.uc\\.cn/s/([A-Za-z0-9]+)"},
        {"xunlei", "迅雷网盘", "pan\\.xunlei\\.com/s/([A-Za-z0-9_-]+)"},
        {"baidu", "百度网盘", "pan\\.baidu\\.com/s/(1[A-Za-z0-9_-]+)"},
        {"c139", "139和彩云", "yun\\.139\\.com/shareweb/.*?/w/i/([A-Za-z0-9_-]+)"},
        {"pan123", "123云盘", "123(?:865|pan)\\.(?:com|cn)/s/([A-Za-z0-9]+-[A-Za-z0-9]+)"},
        {"pan115", "115网盘", "115(?:cdn|rc)?\\.com/s/(sw[A-Za-z0-9]+)"},
        {"guangya", "光鸭云盘", "guangyapan\\.com/s/([A-Za-z0-9_-]+)"},
        {"ilanzou", "蓝奏云优享", "(?:^|[/.])ilanzou\\.(?:com|net|org|cn)/s/([A-Za-z0-9_-]+)"},
        {"lanzou", "蓝奏云", "(?:^|[/.])(?:lanzou[a-z0-9-]*|lan[zs]o[ux])\\.(?:com|net|org|cn)/([A-Za-z0-9]+)"},
    };

    public static Share parse(String text, String forcePlatform) {
        if (text == null) return null;
        Matcher um = URL_RE.matcher(text.trim());
        if (!um.find()) return null;
        String url = um.group().replaceAll("[。，；;)\\]$]+$", "");
        String pwd = null;
        Matcher pm = PWD_URL.matcher(url);
        if (pm.find()) pwd = pm.group(1);
        if (pwd == null) {
            Matcher tm = PWD_TEXT.matcher(text);
            if (tm.find()) pwd = tm.group(1);
        }
        if (forcePlatform != null && !forcePlatform.isEmpty() && !"github".equals(forcePlatform)) {
            for (String[] p : PATTERNS) {
                if (!forcePlatform.equals(p[0])) continue;
                Matcher m = Pattern.compile(p[2], Pattern.CASE_INSENSITIVE).matcher(url);
                if (m.find()) {
                    String sid = m.group(1);
                    if ("baidu".equals(p[0]) && sid.startsWith("1")) sid = sid.substring(1);
                    Share s = new Share();
                    s.platform = p[0]; s.platformName = p[1]; s.shareId = sid;
                    s.pwd = pwd; s.url = url;
                    return s;
                }
            }
            Share s = new Share();
            s.platform = forcePlatform; s.platformName = forcePlatform;
            s.shareId = url.replaceAll(".*/", "").replaceAll("\\?.*$", "");
            s.pwd = pwd; s.url = url;
            return s;
        }
        for (String[] p : PATTERNS) {
            Matcher m = Pattern.compile(p[2], Pattern.CASE_INSENSITIVE).matcher(url);
            if (m.find()) {
                String sid = m.group(1);
                if ("baidu".equals(p[0]) && sid.startsWith("1")) sid = sid.substring(1);
                Share s = new Share();
                s.platform = p[0]; s.platformName = p[1]; s.shareId = sid;
                s.pwd = pwd; s.url = url;
                return s;
            }
        }
        Matcher gh = Pattern.compile("github\\.com/([^/]+)/([^/]+)(?:/(?:blob|tree)/(.+))?", Pattern.CASE_INSENSITIVE).matcher(url);
        if (gh.find()) {
            Share s = new Share();
            s.platform = "github"; s.platformName = "GitHub";
            s.shareId = gh.group(3) == null ? "" : gh.group(3);
            s.owner = gh.group(1); s.repo = gh.group(2); s.url = url;
            return s;
        }
        return null;
    }

    public static Share parse(String text) { return parse(text, null); }

    private static String httpGet(String url, Map<String, String> headers) throws Exception {
        return httpGet(url, headers, 15000, 20000);
    }

    /** 可指定超时的 GET（登录校验用短超时，避免慢网下长时间等待） */
    private static String httpGet(String url, Map<String, String> headers, int connectMs, int readMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/json, text/plain, */*");
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new ResolveException("HTTP " + code);
        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        c.disconnect();
        return sb.toString();
    }

    public static List<PanFile> resolve(Share s, String cookie) throws ResolveException {
        try {
            if ("github".equals(s.platform)) return resolveGithub(s, cookie);
            if ("lanzou".equals(s.platform) || "ilanzou".equals(s.platform)) return resolveLanzou(s);
            if ("quark".equals(s.platform)) return resolveQuark(s, cookie);
            if ("uc".equals(s.platform)) return resolveUc(s, cookie);
            if ("baidu".equals(s.platform)) return resolveBaidu(s, cookie);
            throw new ResolveException(s.platformName + " 取链接口适配中；当前可用：GitHub / 蓝奏云 / 夸克 / UC / 百度");
        } catch (ResolveException e) {
            throw e;
        } catch (Exception e) {
            throw new ResolveException("解析失败：" + String.valueOf(e.getMessage()).substring(0, Math.min(80, String.valueOf(e.getMessage()).length())));
        }
    }

    private static List<PanFile> resolveGithub(Share s, String token) throws Exception {
        String path = s.shareId == null ? "" : s.shareId;
        String ref = null;
        if (path.startsWith("blob/") || path.startsWith("tree/")) {
            String[] parts = path.split("/", 3);
            ref = parts.length > 1 ? parts[1] : null;
            path = parts.length > 2 ? parts[2] : "";
        } else if (path.contains("/")) {
            ref = path.substring(0, path.indexOf('/'));
            path = path.substring(path.indexOf('/') + 1);
        }
        StringBuilder encoded = new StringBuilder();
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) continue;
            if (encoded.length() > 0) encoded.append('/');
            encoded.append(URLEncoder.encode(seg, "UTF-8"));
        }
        String api = "https://api.github.com/repos/" + s.owner + "/" + s.repo + "/contents/" + encoded;
        if (ref != null && !ref.isEmpty()) api += "?ref=" + URLEncoder.encode(ref, "UTF-8");
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/vnd.github+json");
        if (token != null && !token.trim().isEmpty()) h.put("Authorization", "Bearer " + token.trim());
        String body = httpGet(api, h);
        List<PanFile> out = new ArrayList<>();
        JSONObject obj = new JSONObject(body);
        if (obj.has("download_url") && !obj.isNull("download_url")) {
            PanFile f = new PanFile();
            f.name = obj.optString("name", "download");
            f.size = obj.optLong("size", 0);
            f.url = obj.getString("download_url");
            f.referer = "https://github.com/";
            out.add(f);
        } else if (body.trim().startsWith("[")) {
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.getJSONObject(i);
                if ("file".equals(it.optString("type")) && !it.isNull("download_url")) {
                    PanFile f = new PanFile();
                    f.name = it.optString("name", "download");
                    f.size = it.optLong("size", 0);
                    f.url = it.getString("download_url");
                    f.referer = "https://github.com/";
                    out.add(f);
                }
            }
        }
        if (out.isEmpty()) throw new ResolveException("目录为空或需要 Token");
        return out;
    }

    private static String httpPost(String url, String jsonBody, Map<String, String> headers) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setDoOutput(true);
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        c.getOutputStream().write(jsonBody.getBytes(StandardCharsets.UTF_8));
        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(); String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close(); c.disconnect();
        return sb.toString();
    }

    private static Map<String, String> cookieHeaders(String cookie, String referer) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Referer", referer);
        h.put("Origin", referer.replaceAll("/$", ""));
        if (cookie != null && !cookie.isEmpty()) h.put("Cookie", cookie);
        return h;
    }

    private static List<PanFile> resolveQuarkLike(Share s, String cookie, boolean uc) throws Exception {
        String sid = s.shareId, pwd = s.pwd == null ? "" : s.pwd;
        String base = "https://drive-pc.quark.cn/1/clouddrive/share/sharepage";
        String referer = uc ? "https://drive.uc.cn/" : "https://pan.quark.cn/";
        Map<String, String> h = cookieHeaders(cookie, referer);
        String tokenUrl = base + "/token?pr=ucpro&fr=pc";
        JSONObject tok = new JSONObject(httpPost(tokenUrl, "{\"pwd_id\":\"" + sid + "\",\"passcode\":\"" + pwd + "\"}", h));
        if (tok.optInt("status") != 200 || !tok.has("data")) throw new ResolveException(tok.optString("message", "取凭证失败"));
        String stoken = tok.getJSONObject("data").optString("stoken", "");
        if (stoken.isEmpty()) throw new ResolveException("分享需要提取码或已失效");
        String detail = base + "/detail?pr=ucpro&fr=pc&pwd_id=" + sid + "&stoken=" + stoken
                + "&pdir_fid=0&force=0&_page=1&_size=200&_sort=file_type%3Aasc%2Cupdated_at%3Adesc";
        JSONObject det = new JSONObject(httpGet(detail, h));
        if (det.optInt("status") != 200) throw new ResolveException(det.optString("message", "列目录失败"));
        JSONArray list = det.getJSONObject("data").optJSONArray("list");
        List<PanFile> out = new ArrayList<>();
        if (list != null) for (int i = 0; i < list.length(); i++) {
            JSONObject it = list.getJSONObject(i);
            if (it.optBoolean("dir")) continue;
            PanFile f = new PanFile();
            f.name = it.optString("file_name", "file"); f.size = it.optLong("size", 0);
            String token = it.optString("share_fid_token", "");
            f.fid = it.optString("fid", "");
            f.share_fid_token = token;
            f.stoken = stoken;
            f.cookie = f.fid + "|" + token + "|" + sid + "|" + stoken + "|" + referer;
            f.url = ""; f.referer = referer;
            out.add(f);
        }
        if (out.isEmpty()) throw new ResolveException("分享目录中没有文件");
        return out;
    }

    private static List<PanFile> resolveQuark(Share s, String cookie) throws Exception { return resolveQuarkLike(s, cookie, false); }
    private static List<PanFile> resolveUc(Share s, String cookie) throws Exception { return resolveQuarkLike(s, cookie, true); }

    public static List<PanFile> quarkDirect(PanFile f, String cookie) throws ResolveException {
        try {
            String[] p = f.cookie.split("\\|", -1);
            String fid = p[0], token = p[1], sid = p[2], stoken = p[3], referer = p.length > 4 ? p[4] : "https://pan.quark.cn/";
            String body = "{\"fids\":[\"" + fid + "\"],\"pwd_id\":\"" + sid + "\",\"stoken\":\"" + stoken
                    + "\",\"share_fid_tokens\":[\"" + token + "\"]}";
            Map<String, String> h = cookieHeaders(cookie, referer);
            JSONObject obj = new JSONObject(httpPost("https://drive-pc.quark.cn/1/clouddrive/share/sharepage/download?pr=ucpro&fr=pc", body, h));
            if (obj.optInt("status") != 200) throw new ResolveException(obj.optString("message", "取直链失败"));
            JSONArray urls = obj.getJSONObject("data").optJSONArray("download_url");
            List<PanFile> out = new ArrayList<>();
            if (urls != null && urls.length() > 0) {
                PanFile r = new PanFile();
                r.name = f.name; r.size = f.size; r.url = urls.optString(0); r.referer = referer; r.cookie = cookie;
                out.add(r);
            }
            return out;
        } catch (ResolveException e) { throw e; }
        catch (Exception e) { throw new ResolveException("取直链失败：" + e.getMessage()); }
    }

    private static List<PanFile> resolveBaidu(Share s, String cookie) throws Exception {
        String url = "https://pan.baidu.com/share/list?surl=" + s.shareId + "&pwd=" + (s.pwd == null ? "" : s.pwd) + "&page=1&num=1000";
        Map<String, String> h = cookieHeaders(cookie, "https://pan.baidu.com/");
        JSONObject obj = new JSONObject(httpGet(url, h));
        if (obj.optInt("errno", -1) != 0) throw new ResolveException("百度返回错误 " + obj.optInt("errno") + "（提取码或 Cookie 无效）");
        JSONArray list = obj.optJSONArray("list");
        List<PanFile> out = new ArrayList<>();
        if (list != null) for (int i = 0; i < list.length(); i++) {
            JSONObject it = list.getJSONObject(i);
            if (it.optInt("is_dir") == 1) continue;
            PanFile f = new PanFile();
            f.name = it.optString("filename", "file"); f.size = it.optLong("size", 0);
            f.url = ""; f.cookie = "baidu://" + it.optString("fs_id"); f.referer = "https://pan.baidu.com/";
            out.add(f);
        }
        if (out.isEmpty()) throw new ResolveException("分享目录中没有文件");
        return out;
    }

    public static void saveToMyDrive(Share share, List<PanFile> files, String cookie) throws ResolveException {
        String p = share.platform;
        try {
            if ("quark".equals(p) || "uc".equals(p)) {
                String base = "quark".equals(p) ? "https://drive-pc.quark.cn/1/clouddrive" : "https://drive.uc.cn/1/clouddrive";
                String ref = "quark".equals(p) ? "https://pan.quark.cn/" : "https://drive.uc.cn/";
                StringBuilder fids = new StringBuilder("["), tokens = new StringBuilder("[");
                for (int i = 0; i < files.size(); i++) {
                    if (i > 0) { fids.append(','); tokens.append(','); }
                    fids.append('"').append(files.get(i).fid).append('"');
                    tokens.append('"').append(files.get(i).share_fid_token).append('"');
                }
                fids.append(']'); tokens.append(']');
                String stoken = files.isEmpty() ? "" : files.get(0).stoken;
                String body = "{\"fid_list\":" + fids + ",\"token_list\":" + tokens
                        + ",\"pwd_id\":\"" + share.shareId + "\",\"stoken\":\"" + stoken
                        + "\",\"pdir_fid\":\"0\"}";
                Map<String, String> h = cookieHeaders(cookie, ref);
                JSONObject obj = new JSONObject(httpPost(base + "/share/sharepage/save?pr=ucpro&fr=pc", body, h));
                if (obj.optInt("code", 0) != 0) throw new ResolveException(obj.optString("message", "转存失败（可能需会员或空间不足）"));
                return;
            }
            throw new ResolveException(p + " 转存接口适配中，可直接下载");
        } catch (ResolveException e) { throw e; }
        catch (Exception e) { throw new ResolveException("转存失败：" + e.getMessage()); }
    }

    public static final class MyFile {
        public String name;
        public long size;
        public boolean isDir;
        public String fid;
        public String path;
        public String platform;
    }

    /**
     * 校验登录态。
     * 返回 "ok" = 已登录；"fail" = 未登录；"unknown" = 该平台暂无校验接口或网络异常。
     */
    public static String verifyLogin(String platform, String cookie) {
        if (cookie == null || cookie.trim().isEmpty()) return "fail";
        boolean hintOk = hasLoginCookie(platform, cookie);

        // 1) 先走平台接口（最强信号，通常 1 次请求即可判定）。
        //    重要：不要在接口前做主域 GET 探测——那会下载整个首页（12s+15s 超时），
        //    再叠加接口超时导致「点完成登录后要等近一分钟」。
        String api = verifyByApi(platform, cookie, hintOk);
        if (api != null) return api;

        // 2) 接口无结论时（无适配接口 / 403 风控 / 网络异常），才做主域探测兜底。
        //    用 HEAD + 短超时，只跳转判定，不下载页面内容。
        String md = mainDomain(platform);
        if (!md.isEmpty()) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://" + md + "/").openConnection();
                c.setRequestMethod("HEAD");
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(4000);
                c.setReadTimeout(5000);
                c.setRequestProperty("User-Agent", UA);
                c.setRequestProperty("Cookie", cookie);
                int rc = c.getResponseCode();
                String finalUrl = c.getURL().toString().toLowerCase(Locale.US);
                c.disconnect();
                if (rc == 401) return "fail";
                if (finalUrl.contains("/login") || finalUrl.contains("/signin")
                        || finalUrl.contains("passport.") || finalUrl.contains("/authorize")) return "fail";
            } catch (Exception e) {
                // 网络异常不判定
            }
        }
        return hintOk ? "ok" : "unknown";
    }

    /** 平台接口校验；返回 null 表示无适配接口或需主域兜底。 */
    private static String verifyByApi(String platform, String cookie, boolean hintOk) {
        try {
            // 注意：平台接口的 Referer 必须用其自身域名（pan.quark.cn / drive.uc.cn / pan.baidu.com），
            // 用主域名会被网关判为跨域非法请求（403 Invalid CORS request）
            if ("quark".equals(platform) || "uc".equals(platform)) {
                String base = "quark".equals(platform) ? "https://drive-pc.quark.cn/1/clouddrive" : "https://drive.uc.cn/1/clouddrive";
                String ref = "quark".equals(platform) ? "https://pan.quark.cn/" : "https://drive.uc.cn/";
                Map<String, String> h = cookieHeaders(cookie, ref);
                String url = base + "/file/sort?pr=ucpro&fr=pc&pdir_fid=0&_page=1&_size=10&_fetch_total=1&_fetch_sub_dirs=0";
                JSONObject obj = new JSONObject(httpGet(url, h, 6000, 8000));
                int code = obj.has("code") ? obj.optInt("code", -1) : obj.optInt("status", -1);
                if (code == 0) return "ok";
                // 有登录标志 cookie 却调不通，多半是风控/参数问题，不误判未登录
                return hintOk ? "ok" : "fail";
            }
            if ("baidu".equals(platform)) {
                Map<String, String> h = cookieHeaders(cookie, "https://pan.baidu.com/");
                String url = "https://pan.baidu.com/api/list?dir=%2F&page=1&num=10&order=name&desc=0";
                JSONObject obj = new JSONObject(httpGet(url, h, 6000, 8000));
                if (obj.optInt("errno", -1) == 0) return "ok";
                return hintOk ? "ok" : "fail";
            }
            if ("github".equals(platform)) {
                Map<String, String> h = new LinkedHashMap<>();
                h.put("Cookie", cookie);
                h.put("Accept", "application/vnd.github+json");
                h.put("User-Agent", UA);
                JSONObject obj = new JSONObject(httpGet("https://api.github.com/user", h, 6000, 8000));
                return obj.optString("login", "").isEmpty() ? "fail" : "ok";
            }
        } catch (ResolveException e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (m.contains("401")) return "fail";     // 明确未登录（401 优先于标志 cookie）
            // 403 / 其它：让主域探测兜底，不直接判未登录
            return null;
        } catch (Exception e) {
            return null;
        }
        return null; // 该平台暂无接口适配
    }

    public static List<MyFile> listMyFiles(String platform, String cookie, String fid) throws ResolveException {
        try {
            if (fid == null || fid.isEmpty()) fid = "0";
            if ("quark".equals(platform) || "uc".equals(platform)) {
                String base = "quark".equals(platform) ? "https://drive-pc.quark.cn/1/clouddrive" : "https://drive.uc.cn/1/clouddrive";
                String ref = "quark".equals(platform) ? "https://pan.quark.cn/" : "https://drive.uc.cn/";
                Map<String, String> h = cookieHeaders(cookie, ref);
                String url = base + "/file/sort?pr=ucpro&fr=pc&pdir_fid=" + URLEncoder.encode(fid, "UTF-8")
                        + "&_page=1&_size=200&_fetch_total=1&_fetch_sub_dirs=0&_sort=file_type%3Aasc%2Cupdated_at%3Adesc";
                JSONObject obj = new JSONObject(httpGet(url, h));
                int code = obj.optInt("code", 0);
                if (code != 0) throw new ResolveException(obj.optString("message", "文件列表失败"));
                JSONArray list = obj.getJSONObject("data").optJSONArray("list");
                List<MyFile> out = new ArrayList<>();
                if (list != null) for (int i = 0; i < list.length(); i++) {
                    JSONObject it = list.getJSONObject(i);
                    MyFile f = new MyFile();
                    f.name = it.optString("file_name", "file"); f.size = it.optLong("size", 0);
                    f.isDir = it.optBoolean("dir"); f.fid = it.optString("fid", "");
                    f.path = fid; f.platform = platform;
                    out.add(f);
                }
                return out;
            }
            if ("baidu".equals(platform)) {
                Map<String, String> h = cookieHeaders(cookie, "https://pan.baidu.com/");
                String dir = "0".equals(fid) ? "/" : fid;
                String url = "https://pan.baidu.com/api/list?dir=" + URLEncoder.encode(dir, "UTF-8")
                        + "&page=1&num=1000&order=name&desc=0";
                JSONObject obj = new JSONObject(httpGet(url, h));
                if (obj.optInt("errno", -1) != 0) throw new ResolveException("百度返回错误 " + obj.optInt("errno"));
                JSONArray list = obj.optJSONArray("list");
                List<MyFile> out = new ArrayList<>();
                if (list != null) for (int i = 0; i < list.length(); i++) {
                    JSONObject it = list.getJSONObject(i);
                    MyFile f = new MyFile();
                    f.name = it.optString("filename", "file"); f.size = it.optLong("size", 0);
                    f.isDir = it.optInt("is_dir") == 1; f.fid = it.optString("fs_id", "");
                    f.path = it.optString("path", dir); f.platform = "baidu";
                    out.add(f);
                }
                return out;
            }
            throw new ResolveException(platform + " 暂不支持浏览账号文件，当前可用：夸克 / UC / 百度");
        } catch (ResolveException e) { throw e; }
        catch (Exception e) { throw new ResolveException("文件列表失败：" + e.getMessage()); }
    }

    public static List<PanFile> myFileDirect(List<MyFile> files, String cookie) throws ResolveException {
        if (files == null || files.isEmpty()) return new ArrayList<>();
        String platform = files.get(0).platform;
        try {
            if ("quark".equals(platform) || "uc".equals(platform)) {
                String base = "quark".equals(platform) ? "https://drive-pc.quark.cn/1/clouddrive" : "https://drive.uc.cn/1/clouddrive";
                String ref = "quark".equals(platform) ? "https://pan.quark.cn/" : "https://drive.uc.cn/";
                StringBuilder fids = new StringBuilder("[");
                for (int i = 0; i < files.size(); i++) {
                    if (i > 0) fids.append(',');
                    fids.append('"').append(files.get(i).fid).append('"');
                }
                fids.append(']');
                Map<String, String> h = cookieHeaders(cookie, ref);
                JSONObject obj = new JSONObject(httpPost(base + "/file/download?pr=ucpro&fr=pc",
                        "{\"fids\":" + fids + "}", h));
                if (obj.optInt("code", 0) != 0) throw new ResolveException(obj.optString("message", "取直链失败"));
                JSONArray data = obj.optJSONArray("data");
                List<PanFile> out = new ArrayList<>();
                if (data != null) for (int i = 0; i < data.length(); i++) {
                    JSONObject d = data.optJSONObject(i);
                    if (d == null) continue;
                    String u = d.optString("download_url", "");
                    if (u.isEmpty()) continue;
                    MyFile f = files.get(Math.min(i, files.size() - 1));
                    PanFile r = new PanFile();
                    r.name = f.name; r.size = f.size; r.url = u; r.referer = ref; r.cookie = cookie;
                    out.add(r);
                }
                if (out.isEmpty()) throw new ResolveException("未取到直链（可能需会员或已失效）");
                return out;
            }
            throw new ResolveException("百度账号文件直链适配中，当前可用：夸克 / UC");
        } catch (ResolveException e) { throw e; }
        catch (Exception e) { throw new ResolveException("取直链失败：" + e.getMessage()); }
    }

    private static List<PanFile> resolveLanzou(Share s) throws Exception {
        String host = "ilanzou".equals(s.platform) ? "https://www.ilanzou.com" : "https://www.lanzoui.com";
        String page = httpGet(host + "/s/" + s.shareId, null);
        List<PanFile> out = new ArrayList<>();
        Matcher m = Pattern.compile("id=\"(\\d+)\"[^>]*>.*?class=\"file-name\"[^>]*>([^<]+)<", Pattern.DOTALL).matcher(page);
        while (m.find()) {
            PanFile f = new PanFile();
            f.name = m.group(2).trim(); f.url = host + "/s/" + s.shareId;
            f.referer = host + "/"; f.cookie = s.shareId + "|" + m.group(1);
            out.add(f);
        }
        if (out.isEmpty()) throw new ResolveException("未解析到文件（蓝奏云页面结构可能已变）");
        return out;
    }
}
