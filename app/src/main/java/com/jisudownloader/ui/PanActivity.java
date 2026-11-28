package com.jisudownloader.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.jisudownloader.R;
import com.jisudownloader.model.Task;
import com.jisudownloader.model.TaskStore;
import com.jisudownloader.net.PanResolver;
import com.jisudownloader.service.DownloadService;
import com.jisudownloader.util.AccountStore;
import com.jisudownloader.util.Prefs;
import com.jisudownloader.util.ThemeHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PanActivity extends Activity {
    private EditText input;
    private CheckBox saveChk;
    private ListView accounts;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private int loginReq = 1001;
    private String pendingAfterLogin = null;

    private static final String[] PLATFORM_IDS = {"quark", "uc", "baidu", "xunlei", "c139",
            "pan123", "pan115", "guangya", "ilanzou", "lanzou", "github"};
    private static final String[] PLATFORM_NAMES = {"夸克网盘", "UC网盘", "百度网盘", "迅雷网盘",
            "139和彩云", "123云盘", "115网盘", "光鸭云盘", "蓝奏云优享", "蓝奏云", "GitHub"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pan);
        findViewById(R.id.root).setBackgroundColor(ThemeHelper.bgColor(this));
        findViewById(R.id.header).setBackgroundColor(ThemeHelper.primary(this));

        input = findViewById(R.id.panInput);
        saveChk = findViewById(R.id.panSave);
        accounts = findViewById(R.id.panAccounts);
        accounts.setAdapter(new AccountAdapter());
        accounts.setOnItemClickListener((p, v, pos, id) -> {
            String pid = PLATFORM_IDS[pos];
            if (AccountStore.isLogin(this, pid)) openMyFiles(pid);
            else openLogin(pid, null);
        });
        findViewById(R.id.btnParse).setOnClickListener(v -> parse());
    }

    @Override
    protected void onResume() {
        super.onResume();
        ((BaseAdapter) accounts.getAdapter()).notifyDataSetChanged();
    }

    private void openLogin(String pid, String after) {
        pendingAfterLogin = after;
        Intent i = new Intent(this, PanLoginActivity.class);
        i.putExtra(PanLoginActivity.EXTRA_PLATFORM, pid);
        startActivityForResult(i, loginReq);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == loginReq && res == RESULT_OK && data != null) {
            String c = data.getStringExtra("cookies");
            String pid = data.getStringExtra("platform");
            if (c != null && !c.isEmpty() && pid != null) {
                AccountStore.save(this, pid, c);
                ((BaseAdapter) accounts.getAdapter()).notifyDataSetChanged();
                toast("已登录 " + pid);
                if (pendingAfterLogin != null) {
                    String text = pendingAfterLogin;
                    pendingAfterLogin = null;
                    runPipeline(text, c);
                }
            }
        }
    }

    private void clearCookies(String pid) {
        // 清掉 WebView 里的 Cookie 与本机保存的登录态
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            if (cm != null) {
                cm.removeAllCookies(null);
                cm.removeSessionCookies(null);
                cm.flush();
            }
        } catch (Exception ignored) {
        }
        AccountStore.logout(this, pid);
        ((BaseAdapter) accounts.getAdapter()).notifyDataSetChanged();
        toast("已清除 " + pid + " 的 Cookie");
    }

    private void openMyFiles(String pid) {
        String cookie = AccountStore.get(this, pid);
        if (cookie.isEmpty()) { toast("请先登录"); return; }
        Intent i = new Intent(this, MyFilesActivity.class);
        i.putExtra(MyFilesActivity.EXTRA_PLATFORM, pid);
        i.putExtra(MyFilesActivity.EXTRA_COOKIE, cookie);
        startActivity(i);
    }

    private void parse() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) { toast("请先粘贴分享链接"); return; }
        PanResolver.Share share = PanResolver.parse(text);
        if (share == null) { toast("未识别的网盘链接"); return; }
        String pid = share.platform;
        String cookie = AccountStore.get(this, pid);
        boolean needLogin = false;
        for (String s : PLATFORM_IDS) {
            if (s.equals(pid)) { needLogin = !"github".equals(pid) && !"lanzou".equals(pid)
                    && !"ilanzou".equals(pid) && cookie.isEmpty(); break; }
        }
        if (needLogin) {
            toast("该网盘未登录，正在打开登录页…");
            openLogin(pid, text);
            return;
        }
        runPipeline(text, cookie.isEmpty() ? null : cookie);
    }

    private void runPipeline(String text, String cookie) {
        new Thread(() -> {
            try {
                PanResolver.Share share = PanResolver.parse(text);
                if (share == null) throw new PanResolver.ResolveException("未识别链接");
                java.util.List<PanResolver.PanFile> files = PanResolver.resolve(share, cookie);
                String saveMsg = "";
                if (saveChk.isChecked() && cookie != null
                        && ("quark".equals(share.platform) || "uc".equals(share.platform))) {
                    try {
                        PanResolver.saveToMyDrive(share, files, cookie);
                        saveMsg = "，已转存到我的网盘";
                    } catch (PanResolver.ResolveException se) {
                        saveMsg = "，转存失败，改为直接下载";
                    }
                }
                java.util.List<PanResolver.PanFile> direct = new ArrayList<>();
                if ("quark".equals(share.platform) || "uc".equals(share.platform)) {
                    for (PanResolver.PanFile f : files) direct.addAll(PanResolver.quarkDirect(f, cookie));
                } else {
                    for (PanResolver.PanFile f : files) if (f.url != null && !f.url.isEmpty()) direct.add(f);
                }
                if (direct.isEmpty()) throw new PanResolver.ResolveException("未取到下载直链");
                final String msg = saveMsg;
                main.post(() -> {
                    for (PanResolver.PanFile f : direct) addDirect(f, cookie);
                    toast("已加入下载 " + direct.size() + " 个文件" + msg);
                });
            } catch (PanResolver.ResolveException e) {
                main.post(() -> toast(e.getMessage()));
            } catch (Exception e) {
                main.post(() -> toast("下载失败：" + e.getMessage()));
            }
        }).start();
    }

    private void addDirect(PanResolver.PanFile f, String cookie) {
        Task t = Task.fromSource(f.url, Prefs.threads());
        t.displayName = f.name;
        Map<String, String> h = new HashMap<>();
        if (f.referer != null) h.put("Referer", f.referer);
        if (cookie != null && !cookie.isEmpty()) h.put("Cookie", cookie);
        t.headers = h;
        TaskStore.add(t);
        Intent i = new Intent(this, DownloadService.class);
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
        } catch (Exception ignored) {}
        DownloadService.pump(this);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private class AccountAdapter extends BaseAdapter {
        @Override public int getCount() { return PLATFORM_IDS.length; }
        @Override public Object getItem(int i) { return PLATFORM_IDS[i]; }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View convert, ViewGroup parent) {
            LinearLayout row = convert instanceof LinearLayout ? (LinearLayout) convert : new LinearLayout(PanActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(16, 14, 16, 14);
            if (row.getChildCount() == 0) {
                TextView tv = new TextView(PanActivity.this);
                tv.setId(R.id.acctName);
                tv.setTextSize(15);
                tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                TextView btn = new TextView(PanActivity.this);
                btn.setId(R.id.acctBtn);
                btn.setTextSize(13);
                btn.setPadding(14, 6, 14, 6);
                TextView clr = new TextView(PanActivity.this);
                clr.setId(R.id.acctClear);
                clr.setTextSize(13);
                clr.setPadding(10, 6, 10, 6);
                row.addView(tv); row.addView(btn); row.addView(clr);
            }
            TextView tv = row.findViewById(R.id.acctName);
            TextView btn = row.findViewById(R.id.acctBtn);
            TextView clr = row.findViewById(R.id.acctClear);
            final String pid = PLATFORM_IDS[i];
            boolean logged = AccountStore.isLogin(PanActivity.this, pid);
            tv.setText((logged ? "● " : "○ ") + PLATFORM_NAMES[i] + (logged ? "  · 已登录" : "  · 未登录"));
            tv.setTextColor(logged ? 0xFF17A673 : ThemeHelper.subColor(PanActivity.this));
            if (logged) {
                btn.setText("退出");
                btn.setTextColor(ThemeHelper.subColor(PanActivity.this));
            } else {
                btn.setText("登录");
                btn.setTextColor(ThemeHelper.primary(PanActivity.this));
            }
            btn.setOnClickListener(v -> {
                if (AccountStore.isLogin(PanActivity.this, pid)) {
                    AccountStore.logout(PanActivity.this, pid);
                    ((BaseAdapter) accounts.getAdapter()).notifyDataSetChanged();
                    toast("已退出 " + pid);
                } else {
                    openLogin(pid, null);
                }
            });
            clr.setText("清除Cookie");
            clr.setTextColor(ThemeHelper.subColor(PanActivity.this));
            clr.setOnClickListener(v -> clearCookies(pid));
            return row;
        }
    }
}
