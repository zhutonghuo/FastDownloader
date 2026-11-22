package com.jisudownloader.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MyFilesActivity extends Activity {
    public static final String EXTRA_PLATFORM = "platform";
    public static final String EXTRA_COOKIE = "cookie";
    private String platform;
    private String cookie;
    private final List<PanResolver.MyFile> files = new ArrayList<>();
    private final Map<Integer, Boolean> checked = new HashMap<>();
    private final List<String> stack = new ArrayList<>();
    private final List<String> stackNames = new ArrayList<>();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private FileAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_myfiles);
        findViewById(R.id.root).setBackgroundColor(ThemeHelper.bgColor(this));
        findViewById(R.id.header).setBackgroundColor(ThemeHelper.primary(this));

        platform = getIntent().getStringExtra(EXTRA_PLATFORM);
        cookie = getIntent().getStringExtra(EXTRA_COOKIE);
        if (platform == null) platform = "quark";
        stack.add("0"); stackNames.add("全部文件");
        ((TextView) findViewById(R.id.myTitle)).setText("我的文件 · " + platform);

        ListView list = findViewById(R.id.myList);
        adapter = new FileAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            PanResolver.MyFile f = files.get(pos);
            if (f.isDir) {
                stack.add(f.fid); stackNames.add(f.name);
                checked.clear();
                load();
            }
        });
        findViewById(R.id.btnUp).setOnClickListener(v -> {
            if (stack.size() > 1) {
                stack.remove(stack.size() - 1); stackNames.remove(stackNames.size() - 1);
                checked.clear(); load();
            }
        });
        findViewById(R.id.btnRefresh).setOnClickListener(v -> { checked.clear(); load(); });
        findViewById(R.id.btnDownloadSel).setOnClickListener(v -> downloadSelected());
        load();
    }

    private void load() {
        ((TextView) findViewById(R.id.myPath)).setText(
                android.text.TextUtils.join(" / ", stackNames));
        final String fid = stack.get(stack.size() - 1);
        new Thread(() -> {
            try {
                List<PanResolver.MyFile> r = PanResolver.listMyFiles(platform, cookie, fid);
                main.post(() -> {
                    files.clear(); files.addAll(r);
                    adapter.notifyDataSetChanged();
                    toast("共 " + r.size() + " 项");
                });
            } catch (PanResolver.ResolveException e) {
                main.post(() -> toast(e.getMessage()));
            } catch (Exception e) {
                main.post(() -> toast("加载失败：" + e.getMessage()));
            }
        }).start();
    }

    private void downloadSelected() {
        final List<PanResolver.MyFile> sel = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            if (Boolean.TRUE.equals(checked.get(i)) && !files.get(i).isDir) sel.add(files.get(i));
        }
        if (sel.isEmpty()) { toast("请先勾选文件（文件夹点进去）"); return; }
        new Thread(() -> {
            try {
                List<PanResolver.PanFile> direct = PanResolver.myFileDirect(sel, cookie);
                main.post(() -> {
                    for (PanResolver.PanFile f : direct) addTask(f);
                    toast("已加入下载 " + direct.size() + " 个文件");
                });
            } catch (PanResolver.ResolveException e) {
                main.post(() -> toast(e.getMessage()));
            } catch (Exception e) {
                main.post(() -> toast("取直链失败：" + e.getMessage()));
            }
        }).start();
    }

    private void addTask(PanResolver.PanFile f) {
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

    private class FileAdapter extends BaseAdapter {
        @Override public int getCount() { return files.size(); }
        @Override public Object getItem(int i) { return files.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View convert, ViewGroup parent) {
            LinearLayout row = convert instanceof LinearLayout ? (LinearLayout) convert : new LinearLayout(MyFilesActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(12, 12, 12, 12);
            if (row.getChildCount() == 0) {
                TextView tv = new TextView(MyFilesActivity.this);
                tv.setId(R.id.myFileName);
                tv.setTextSize(14);
                tv.setTextColor(ThemeHelper.textColor(MyFilesActivity.this));
                tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                CheckBox cb = new CheckBox(MyFilesActivity.this);
                cb.setId(R.id.myFileCheck);
                row.addView(tv); row.addView(cb);
            }
            TextView tv = row.findViewById(R.id.myFileName);
            CheckBox cb = row.findViewById(R.id.myFileCheck);
            PanResolver.MyFile f = files.get(i);
            tv.setText((f.isDir ? "📁 " : "📄 ") + f.name
                    + (f.isDir ? "" : "\n" + Fmt.size(f.size)));
            cb.setOnCheckedChangeListener(null);
            cb.setChecked(Boolean.TRUE.equals(checked.get(i)));
            final int pos = i;
            cb.setOnCheckedChangeListener((b, isChecked) -> {
                if (isChecked) checked.put(pos, true); else checked.remove(pos);
            });
            cb.setVisibility(f.isDir ? View.GONE : View.VISIBLE);
            return row;
        }
    }
}
