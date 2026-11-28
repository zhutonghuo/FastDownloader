package com.jisudownloader.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.jisudownloader.R;
import com.jisudownloader.util.ThemeHelper;

public class PanLoginActivity extends Activity {
    public static final String EXTRA_PLATFORM = "platform";
    private WebView web;
    private TextView titleLbl;
    private int zoomSteps = 0;
    private String platform;
    private final java.util.Map<String, String> cookies = new java.util.concurrent.ConcurrentHashMap<>();

    private static String loginUrl(String p) {
        if (p == null) return "https://pan.quark.cn/";
        switch (p) {
            case "quark": return "https://pan.quark.cn/";
            case "uc": return "https://drive.uc.cn/";
            case "xunlei": return "https://pan.xunlei.com/login";
            case "baidu": return "https://pan.baidu.com/login";
            case "c139": return "https://yun.139.com/w/#/";
            case "pan123": return "https://user.123pan.cn/centerlogin";
            case "pan115": return "https://115.com/";
            case "guangya": return "https://www.guangyapan.com/";
            case "ilanzou": return "https://www.ilanzou.com/";
            case "lanzou": return "https://www.lanzoui.com/";
            case "github": return "https://github.com/login";
            default: return "";
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pan_login);
        findViewById(R.id.root).setBackgroundColor(ThemeHelper.bgColor(this));
        View header = findViewById(R.id.header);
        header.setBackgroundColor(ThemeHelper.primary(this));

        platform = getIntent().getStringExtra(EXTRA_PLATFORM);
        ((TextView) findViewById(R.id.loginTitle)).setText("登录 " + platform + " · 完成后点右上角");

        web = findViewById(R.id.loginWeb);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36");
        s.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        s.setRenderPriority(WebSettings.RenderPriority.HIGH);
        s.setLoadsImagesAutomatically(true);
        // 页面缩放：允许双指缩放 + 显示缩放按钮
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setTextZoom(100);
        try { s.setDatabaseEnabled(true); } catch (Exception ignored) {}

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(web, true);
        }

        titleLbl = findViewById(R.id.loginTitle);
        final View overlay = findViewById(R.id.loadingOverlay);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) {
                titleLbl.setText("登录 " + platform + " · 加载中 " + p + "%");
            }
            @Override public boolean onCreateWindow(WebView v, boolean dialog, boolean userGesture,
                                                    android.os.Message resultMsg) {
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(v);
                resultMsg.sendToTarget();
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                overlay.setVisibility(View.VISIBLE);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url == null) return false;
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    v.loadUrl(url);
                    return true;
                }
                return false;
            }
            @Override public void onPageFinished(WebView v, String url) {
                collect(url);
                titleLbl.setText("登录 " + platform + " · 完成后点右上角");
                overlay.setVisibility(View.GONE);
            }
        });
        String url = loginUrl(platform);
        if (url.isEmpty()) {
            Toast.makeText(this, "暂不支持 " + platform + " 的网页登录", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        web.loadUrl(url);

        findViewById(R.id.btnDone).setOnClickListener(v -> finishWithCookies());
        findViewById(R.id.btnCancel).setOnClickListener(v -> finish());
        findViewById(R.id.btnZoomIn).setOnClickListener(v -> {
            web.zoomIn();
            zoomSteps++;
            showZoom("已放大");
        });
        findViewById(R.id.btnZoomOut).setOnClickListener(v -> {
            if (zoomSteps > 0) {
                web.zoomOut();
                zoomSteps--;
            }
            showZoom("已缩小");
        });
        findViewById(R.id.btnZoomReset).setOnClickListener(v -> {
            for (int i = 0; i < zoomSteps; i++) web.zoomOut();
            zoomSteps = 0;
            showZoom("已复位");
        });
        findViewById(R.id.btnClearCookie).setOnClickListener(v -> clearCookies());
    }

    private void showZoom(String msg) {
        Toast.makeText(this, msg + "（缩放档位 " + (100 + zoomSteps * 20) + "%）", Toast.LENGTH_SHORT).show();
    }

    /** 清除浏览器 Cookie 与缓存，便于重新扫码登录 */
    private void clearCookies() {
        try {
            CookieManager cm = CookieManager.getInstance();
            if (cm != null) {
                cm.removeAllCookies(null);
                cm.removeSessionCookies(null);
                cm.flush();
            }
            web.clearCache(true);
            web.clearHistory();
        } catch (Exception ignored) {
        }
        cookies.clear();
        Toast.makeText(this, "已清除 Cookie，请重新登录", Toast.LENGTH_SHORT).show();
        titleLbl.setText("登录 " + platform + " · Cookie 已清除，请重新登录");
        web.loadUrl(loginUrl(platform));
    }

    private void collect(String url) {
        try {
            String raw = CookieManager.getInstance().getCookie(url);
            if (raw == null || raw.isEmpty()) return;
            for (String part : raw.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2) cookies.put(kv[0], kv[1]);
            }
        } catch (Exception ignored) {
        }
    }

    private void finishWithCookies() {
        collect(web.getUrl());
        String host = android.net.Uri.parse(web.getUrl() == null ? "" : web.getUrl()).getHost();
        if (host != null) collect("https://" + host);
        String main = loginUrl(platform);
        if (!main.isEmpty()) {
            String mh = android.net.Uri.parse(main).getHost();
            if (mh != null) {
                collect("https://" + mh);
                collect("https://." + mh);
            }
        }
        // 主域名：登录态 Cookie 常挂在主域上（如 .quark.cn / .uc.cn），必须一并收集
        String md = com.jisudownloader.net.PanResolver.mainDomain(platform);
        if (!md.isEmpty()) {
            collect("https://" + md + "/");
            collect("https://." + md);
            collect("https://www." + md + "/");
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : cookies.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        final String cookieStr = sb.toString();
        if (cookieStr.trim().isEmpty()) {
            Toast.makeText(this, "未获取到 Cookie，请先在网页里完成登录", Toast.LENGTH_LONG).show();
            return;
        }
        titleLbl.setText("正在校验登录状态…");
        // 真正校验一次登录态，避免"没登录也提示登录成功"
        new Thread(() -> {
            String state;
            try {
                state = com.jisudownloader.net.PanResolver.verifyLogin(platform, cookieStr);
            } catch (Exception e) {
                state = "unknown";
            }
            final String st = state;
            runOnUiThread(() -> onVerified(cookieStr, st));
        }, "verify-login").start();
    }

    private void onVerified(String cookieStr, String state) {
        if ("ok".equals(state)) {
            deliver(cookieStr, "登录成功");
            return;
        }
        if ("unknown".equals(state)) {
            deliver(cookieStr, "已保存登录状态（无法自动校验）");
            return;
        }
        // fail：明确未登录
        titleLbl.setText("未检测到登录状态");
        new android.app.AlertDialog.Builder(this)
                .setTitle("未检测到登录状态")
                .setMessage("没有检测到该网盘的有效登录态。\n\n"
                        + "可能的原因：\n"
                        + "· 还没在网页里完成登录（扫码后需要在网页上确认）\n"
                        + "· 账号已退出登录\n"
                        + "· 接口临时受限（风控），但 Cookie 本身可能仍然是有效的\n\n"
                        + "可以选「仍然保存」直接使用，或点顶部「清除Cookie」后重新登录。")
                .setPositiveButton("继续登录", null)
                .setNegativeButton("仍然保存", (d, w) -> deliver(cookieStr, "已保存登录状态"))
                .show();
    }

    private void deliver(String cookieStr, String msg) {
        android.content.Intent out = new android.content.Intent();
        out.putExtra("cookies", cookieStr);
        out.putExtra("platform", platform);
        setResult(RESULT_OK, out);
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
