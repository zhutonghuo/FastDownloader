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
    private final java.util.Map<String, String> cookies = new java.util.concurrent.ConcurrentHashMap<>();

    private static String loginUrl(String p) {
        if (p == null) return "https://pan.quark.cn";
        switch (p) {
            case "uc": return "https://drive.uc.cn";
            case "xunlei": return "https://pan.xunlei.com";
            case "baidu": return "https://pan.baidu.com";
            case "c139": return "https://yun.139.com";
            case "pan123": return "https://www.123pan.com";
            case "pan115": return "https://115.com";
            case "guangya": return "https://www.guangyapan.com";
            case "ilanzou": return "https://www.ilanzou.com";
            case "lanzou": return "https://www.lanzoui.com";
            default: return "https://pan.quark.cn";
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

        String platform = getIntent().getStringExtra(EXTRA_PLATFORM);
        ((TextView) findViewById(R.id.loginTitle)).setText("登录 " + platform + " · 完成后点右上角");

        web = findViewById(R.id.loginWeb);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36");
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(web, true);
        }

        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                collect(url);
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.loadUrl(loginUrl(platform));

        findViewById(R.id.btnDone).setOnClickListener(v -> finishWithCookies());
        findViewById(R.id.btnCancel).setOnClickListener(v -> finish());
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
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : cookies.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        android.content.Intent out = new android.content.Intent();
        out.putExtra("cookies", sb.toString());
        setResult(RESULT_OK, out);
        Toast.makeText(this, "已提取登录状态", Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
