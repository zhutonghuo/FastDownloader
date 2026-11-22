package com.jisudownloader.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.jisudownloader.R;
import com.jisudownloader.model.Task;
import com.jisudownloader.model.TaskStore;
import com.jisudownloader.net.PanResolver;
import com.jisudownloader.service.DownloadService;
import com.jisudownloader.util.Fmt;
import com.jisudownloader.util.Prefs;
import com.jisudownloader.util.ThemeHelper;

import java.util.ArrayList;
import java.util.List;

public class PanActivity extends Activity {
    private EditText input;
    private EditText cookieInput;
    private TextView platform;
    private ListView list;
    private final List<PanResolver.PanFile> files = new ArrayList<>();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pan);
        findViewById(R.id.root).setBackgroundColor(ThemeHelper.bgColor(this));
        View header = findViewById(R.id.header);
        header.setBackgroundColor(ThemeHelper.primary(this));
        getActionBar();

        input = findViewById(R.id.panInput);
        cookieInput = findViewById(R.id.panCookie);
        platform = findViewById(R.id.panPlatform);
        list = findViewById(R.id.panList);
        list.setAdapter(new FileAdapter());

        findViewById(R.id.btnParse).setOnClickListener(v -> parse());
        findViewById(R.id.btnWebLogin).setOnClickListener(v -> openLogin());
        list.setOnItemClickListener((p, v, pos, id) -> addTask(files.get(pos)));
    }

    private void openLogin() {
        String text = input.getText().toString().trim();
        PanResolver.Share share = PanResolver.parse(text);
        String platform = share == null ? "quark" : share.platform;
        android.content.Intent i = new android.content.Intent(this, PanLoginActivity.class);
        i.putExtra(PanLoginActivity.EXTRA_PLATFORM, platform);
        startActivityForResult(i, 1001);
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 1001 && res == RESULT_OK && data != null) {
            String c = data.getStringExtra("cookies");
            if (c != null && !c.isEmpty()) {
                cookieInput.setText(c);
                toast("已自动填入登录 Cookie");
            }
        }
    }

    private void parse() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            toast("请先粘贴分享链接");
            return;
        }
        PanResolver.Share share = PanResolver.parse(text);
        if (share == null) {
            platform.setText("平台：未识别（检查链接格式）");
            toast("未识别的网盘链接");
            return;
        }
        platform.setText("平台：" + share.platformName + " · " + share.shareId);
        String cookie = cookieInput.getText().toString().trim();
        new Thread(() -> {
            try {
                List<PanResolver.PanFile> result = PanResolver.resolve(share, cookie);
                main.post(() -> {
                    files.clear();
                    files.addAll(result);
                    ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
                    toast("解析到 " + result.size() + " 个文件，点击加入下载");
                });
            } catch (PanResolver.ResolveException e) {
                main.post(() -> toast(e.getMessage()));
            } catch (Exception e) {
                main.post(() -> toast("解析失败：" + e.getMessage()));
            }
        }).start();
    }

    private void addTask(PanResolver.PanFile f) {
        final String cookie = cookieInput.getText().toString().trim();
        if (f.url == null || f.url.isEmpty()) {
            if (f.cookie != null && f.cookie.startsWith("baidu://")) {
                toast("百度直链暂未开放，请用 GitHub / 蓝奏云 / 夸克 / UC");
                return;
            }
            new Thread(() -> {
                try {
                    java.util.List<PanResolver.PanFile> direct = PanResolver.quarkDirect(f, cookie);
                    main.post(() -> {
                        if (direct.isEmpty()) toast("取直链失败");
                        else addDirect(direct.get(0), cookie);
                    });
                } catch (PanResolver.ResolveException e) {
                    main.post(() -> toast(e.getMessage()));
                }
            }).start();
            return;
        }
        addDirect(f, cookie);
    }

    private void addDirect(PanResolver.PanFile f, String cookie) {
        Task t = Task.fromSource(f.url, Prefs.threads());
        t.displayName = f.name;
        java.util.Map<String, String> h = new java.util.HashMap<>();
        if (f.referer != null) h.put("Referer", f.referer);
        if (cookie != null && !cookie.isEmpty()) h.put("Cookie", cookie);
        t.headers = h;
        TaskStore.add(t);
        ensureService();
        DownloadService.pump(this);
        toast("已加入下载：" + f.name);
    }

    private void ensureService() {
        Intent i = new Intent(this, DownloadService.class);
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
        } catch (Exception ignored) {
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private class FileAdapter extends BaseAdapter {
        @Override public int getCount() { return files.size(); }
        @Override public Object getItem(int i) { return files.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View convert, ViewGroup parent) {
            TextView tv = convert instanceof TextView ? (TextView) convert : new TextView(PanActivity.this);
            PanResolver.PanFile f = files.get(i);
            tv.setText(f.name + "\n" + Fmt.size(f.size));
            tv.setTextSize(14);
            tv.setTextColor(ThemeHelper.textColor(PanActivity.this));
            tv.setPadding(16, 12, 16, 12);
            return tv;
        }
    }
}
