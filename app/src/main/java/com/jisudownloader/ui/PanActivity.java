package com.jisudownloader.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
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
    private Spinner platformSpinner;
    private ListView list;
    private static final String[] PLATFORM_IDS = {null, "quark", "uc", "baidu", "xunlei", "c139",
            "pan123", "pan115", "guangya", "ilanzou", "lanzou", "github"};
    private static final String[] PLATFORM_NAMES = {"自动识别", "夸克网盘", "UC网盘", "百度网盘", "迅雷网盘",
            "139和彩云", "123云盘", "115网盘", "光鸭云盘", "蓝奏云优享", "蓝奏云", "GitHub"};
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
        platformSpinner = findViewById(R.id.panPlatformSpinner);
        list = findViewById(R.id.panList);
        list.setAdapter(new FileAdapter());

        ArrayAdapter<String> pa = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, PLATFORM_NAMES);
        pa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        platformSpinner.setAdapter(pa);
        platformSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) {
                platform.setText("已选平台：" + PLATFORM_NAMES[pos]);
                input.setHint(hintFor(PLATFORM_IDS[pos]));
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });

        findViewById(R.id.btnParse).setOnClickListener(v -> parse());
        findViewById(R.id.btnWebLogin).setOnClickListener(v -> openLogin());
        list.setOnItemClickListener((p, v, pos, id) -> addTask(files.get(pos)));
    }

    private String hintFor(String pid) {
        if (pid == null) return "粘贴网盘分享链接（可整段，自动提取提取码）";
        switch (pid) {
            case "quark": return "https://pan.quark.cn/s/xxxx 提取码: abcd";
            case "uc": return "https://drive.uc.cn/s/xxxx 密码: abcd";
            case "baidu": return "https://pan.baidu.com/s/1xxxx 提取码: abcd";
            case "xunlei": return "https://pan.xunlei.com/s/xxxx?pwd=abcd";
            case "github": return "https://github.com/owner/repo";
            case "lanzou": return "https://www.lanzoui.com/xxxx";
            case "ilanzou": return "https://www.ilanzou.com/s/xxxx";
            default: return "粘贴 " + pid + " 的分享链接";
        }
    }

    private String pickedPlatform() {
        int pos = platformSpinner.getSelectedItemPosition();
        return pos >= 0 && pos < PLATFORM_IDS.length ? PLATFORM_IDS[pos] : null;
    }

    private void openLogin() {
        String pid = pickedPlatform();
        String text = input.getText().toString().trim();
        PanResolver.Share share = text.isEmpty() ? null : PanResolver.parse(text);
        String p = pid != null ? pid : (share == null ? "quark" : share.platform);
        android.content.Intent i = new android.content.Intent(this, PanLoginActivity.class);
        i.putExtra(PanLoginActivity.EXTRA_PLATFORM, p);
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
        PanResolver.Share share = PanResolver.parse(text, pickedPlatform());
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
