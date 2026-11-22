package com.jisudownloader.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.jisudownloader.R;
import com.jisudownloader.model.Magnet;
import com.jisudownloader.model.Task;
import com.jisudownloader.model.TaskStore;
import com.jisudownloader.service.DownloadService;
import com.jisudownloader.util.Fmt;
import com.jisudownloader.util.Prefs;
import com.jisudownloader.util.ThemeHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements TaskStore.Listener {

    private ListView list;
    private EditText input;
    private TextView empty;
    private TextView stat;
    private Adapter adapter;
    private final Handler h = new Handler();

    private static class Handler extends android.os.Handler {
        Handler() {
            super(android.os.Looper.getMainLooper());
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        TaskStore.init(this);
        com.jisudownloader.model.HistoryStore.init(this);

        list = findViewById(R.id.list);
        input = findViewById(R.id.inputUrl);
        empty = findViewById(R.id.empty);
        stat = findViewById(R.id.statText);

        adapter = new Adapter();
        list.setAdapter(adapter);

        findViewById(R.id.btnAdd).setOnClickListener(v -> add());
        findViewById(R.id.btnPaste).setOnClickListener(v -> paste());
        findViewById(R.id.btnSetting).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnHistory).setOnClickListener(v ->
                startActivity(new Intent(this, HistoryActivity.class)));
        findViewById(R.id.btnPan).setOnClickListener(v ->
                startActivity(new Intent(this, PanActivity.class)));
        findViewById(R.id.btnLan).setOnClickListener(v ->
                startActivity(new Intent(this, LanActivity.class)));

        paint();

        String shared = getIntent().getStringExtra("android.intent.extra.TEXT");
        if (shared != null && !shared.trim().isEmpty()) {
            input.setText(shared.trim());
            input.setSelection(input.getText().length());
        }
        checkHomeUpdate();
    }

    private void checkHomeUpdate() {
        com.jisudownloader.util.UpdateChecker.check(this,
            new com.jisudownloader.util.UpdateChecker.Callback() {
                @Override public void onResult(String tag, String apkUrl) {
                    if (tag == null) return;
                    View card = findViewById(R.id.updateCard);
                    if (card == null) return;
                    ((TextView) findViewById(R.id.updateTitle)).setText("发现新版本 " + tag);
                    ((TextView) findViewById(R.id.updateDesc)).setText("当前 v" + com.jisudownloader.util.UpdateChecker.currentVer(MainActivity.this) + " · 点击下载安装");
                    card.setVisibility(View.VISIBLE);
                    final String url = apkUrl;
                    findViewById(R.id.updateBtn).setOnClickListener(v ->
                        com.jisudownloader.util.UpdateChecker.downloadAndInstall(
                            MainActivity.this, url, (TextView) v));
                }
                @Override public void onError(String msg) {
                }
            });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String s = intent.getStringExtra("android.intent.extra.TEXT");
        if (s != null && !s.trim().isEmpty()) {
            input.setText(s.trim());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        TaskStore.addListener(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 100);
        }
        ensureService();
        startTick();
    }

    @Override
    protected void onPause() {
        super.onPause();
        TaskStore.removeListener(this);
        h.removeCallbacksAndMessages(null);
    }

    private void ensureService() {
        Intent i = new Intent(this, DownloadService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception ignored) {
        }
    }

    private void startTick() {
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                adapter.notifyDataSetChanged();
                paint();
                startTick();
            }
        }, 600);
    }

    @Override
    public void onChanged() {
        runOnUiThread(() -> adapter.notifyDataSetChanged());
    }

    private void paint() {
        int primary = ThemeHelper.primary(this);
        int sub = ThemeHelper.subColor(this);
        int bg = ThemeHelper.bgColor(this);

        findViewById(R.id.root).setBackgroundColor(bg);
        View header = findViewById(R.id.header);
        header.setBackgroundColor(primary);

        android.widget.EditText inputBox = findViewById(R.id.inputUrl);
        GradientDrawable g = new GradientDrawable();
        g.setColor(ThemeHelper.cardColor(this));
        g.setCornerRadius(ThemeHelper.dp(this, 12));
        g.setStroke(1, ThemeHelper.lineColor(this));
        inputBox.setBackground(g);
        inputBox.setTextColor(ThemeHelper.textColor(this));
        inputBox.setHintTextColor(sub);

        TextView btnAdd = findViewById(R.id.btnAdd);
        GradientDrawable pg = new GradientDrawable();
        pg.setColor(primary);
        pg.setCornerRadius(ThemeHelper.dp(this, 20));
        btnAdd.setBackground(pg);

        stat.setTextColor(sub);
        empty.setTextColor(sub);

        List<Task> all = TaskStore.all();
        int running = 0;
        double totalSpeed = 0;
        for (Task t : all) {
            if (t.status == Task.RUNNING || t.status == Task.CONNECTING) {
                running++;
                totalSpeed += t.speed;
            }
        }
        stat.setText(running > 0
                ? "进行中 " + running + " 个任务 · 合计速度 " + Fmt.speed(totalSpeed) + " · 默认线程 " + Prefs.threads()
                : "共 " + all.size() + " 个任务 · 默认线程 " + Prefs.threads() + " · 已完成 "
                + countDone(all) + " 个");
        empty.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private int countDone(List<Task> all) {
        int n = 0;
        for (Task t : all) if (t.status == Task.DONE) n++;
        return n;
    }

    private void paste() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            toast("无法访问剪贴板");
            return;
        }
        ClipData cd = cm.getPrimaryClip();
        if (cd == null || cd.getItemCount() == 0) {
            toast("剪贴板是空的");
            return;
        }
        String s = cd.getItemAt(0).coerceToText(this).toString().trim();
        input.setText(s);
        input.setSelection(input.getText().length());
    }

    private void add() {
        String s = input.getText().toString().trim();
        if (s.isEmpty()) {
            toast("请先粘贴链接");
            return;
        }
        List<String> sources = new ArrayList<>();
        for (String line : s.split("[\\r\\n]+")) {
            String t = line.trim();
            if (!t.isEmpty()) sources.add(t);
        }
        if (sources.isEmpty()) {
            toast("请先粘贴链接");
            return;
        }
        for (String src : sources) {
            if (!Magnet.isMagnet(src) && !src.startsWith("http://") && !src.startsWith("https://")) {
                toast("不支持的链接：" + src);
                continue;
            }
            Task t = Task.fromSource(src, Prefs.threads());
            applyDownloadTarget(t);
            TaskStore.add(t);
        }
        input.setText("");
        ensureService();
        DownloadService.pump(this);
        toast("已添加");
    }

    private String resolveDir() {
        File d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (d.exists() && d.canWrite()) return d.getAbsolutePath();
        File ext = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (ext != null && (ext.exists() || ext.mkdirs())) return ext.getAbsolutePath();
        return getFilesDir().getAbsolutePath();
    }

    // 根据设置决定任务落地方式：真实可写路径直写；否则走 SAF（先下到缓存，完成后拷入所选文件夹）
    private void applyDownloadTarget(Task t) {
        String custom = Prefs.path();
        if (custom != null && !custom.isEmpty()) {
            File f = new File(custom);
            if ((f.exists() && f.isDirectory()) || f.mkdirs()) {
                File probe = new File(f, ".jisu_write_test");
                try {
                    if (probe.createNewFile()) {
                        probe.delete();
                        t.dirPath = f.getAbsolutePath();
                        t.targetTreeUri = "";
                        return;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        String tree = Prefs.pathTree();
        if (tree != null && !tree.isEmpty()) {
            File cache = new File(getCacheDir(), "jisudl/" + t.id);
            if (!cache.exists()) cache.mkdirs();
            t.dirPath = cache.getAbsolutePath();
            t.targetTreeUri = tree;
            return;
        }
        t.dirPath = resolveDir();
        t.targetTreeUri = "";
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void openFolder(Task t) {
        // SAF 模式下文件在用户所选文件夹中，直接尝试打开该文件夹
        if (t.targetTreeUri != null && !t.targetTreeUri.isEmpty()) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(t.targetTreeUri));
                startActivity(Intent.createChooser(i, "打开位置"));
            } catch (Exception ignored) {
                toast("无法打开所选文件夹，请到文件管理器查看");
            }
            return;
        }
        // 优先定位到已下载的文件；文件不存在时退回到其所在目录
        File file = new File(t.dirPath, t.fileName);
        File target = file.exists() ? file : null;
        if (target == null) {
            File dir = new File(t.dirPath);
            if (dir.exists() && dir.isDirectory()) target = dir;
        }
        if (target == null) {
            toast("文件还不存在");
            return;
        }
        try {
            if (target.isFile()) {
                // 用真实 MIME 类型打开，文件管理器会跳到该文件所在文件夹
                Uri uri = FileProvider.getUriForFile(this, "com.jisudownloader.fileprovider", target);
                String mime = guessMime(target.getName());
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, mime);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "打开位置"));
            } else {
                // 目录无法用 FileProvider 共享，引导到系统文件管理器
                openDirFallback(target);
            }
        } catch (Exception e) {
            openDirFallback(target);
        }
    }

    private void openDirFallback(File dir) {
        try {
            // 公开 Download 目录：直接用系统文件管理器打开
            if (dir.getAbsolutePath().contains("Download")) {
                Uri u = Uri.parse("content://com.android.providers.downloads.documents/document/downloads");
                Intent i = new Intent(Intent.ACTION_VIEW, u);
                startActivity(Intent.createChooser(i, "打开位置"));
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivity(Intent.createChooser(i, "选择文件管理器"));
        } catch (Exception ignored) {
            toast("没有可用的文件管理器");
        }
    }

    private static String guessMime(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String ext = name.substring(dot + 1).toLowerCase();
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (m != null) return m;
        }
        return "*/*";
    }

    private final class Adapter extends BaseAdapter {

        @Override
        public int getCount() {
            return TaskStore.all().size();
        }

        @Override
        public Object getItem(int position) {
            return TaskStore.all().get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_task, parent, false);
                h = new Holder(v);
                v.setTag(h);
            } else {
                h = (Holder) v.getTag();
            }
            Task t = (Task) getItem(position);
            bind(h, t);
            return v;
        }
    }

    private static final class Holder {
        final TextView name;
        final TextView state;
        final ProgressBar progress;
        final TextView percent;
        final TextView speed;
        final TextView eta;
        final TextView remain;
        final TextView btnToggle;
        final TextView btnFolder;
        final TextView btnDelete;
        final View root;

        Holder(View v) {
            root = v;
            name = v.findViewById(R.id.name);
            state = v.findViewById(R.id.state);
            progress = v.findViewById(R.id.progress);
            percent = v.findViewById(R.id.percent);
            speed = v.findViewById(R.id.speed);
            eta = v.findViewById(R.id.eta);
            remain = v.findViewById(R.id.remain);
            btnToggle = v.findViewById(R.id.btnToggle);
            btnFolder = v.findViewById(R.id.btnFolder);
            btnDelete = v.findViewById(R.id.btnDelete);
        }
    }

    private void bind(Holder h, Task t) {
        int text = ThemeHelper.textColor(this);
        int sub = ThemeHelper.subColor(this);
        int primary = ThemeHelper.primary(this);

        ThemeHelper.card(this, h.root, 14);

        h.name.setText(t.displayName);
        h.name.setTextColor(text);
        h.state.setText(t.stateText());
        h.state.setTextColor(t.status == Task.ERROR ? 0xFFE05252 : primary);

        boolean busy = t.status == Task.RUNNING || t.status == Task.CONNECTING;
        boolean indeterminate = busy && t.total <= 0;
        ThemeHelper.progress(this, h.progress, t.progress(), indeterminate);

        h.percent.setText(Fmt.percent(t.downloaded, t.total));
        h.percent.setTextColor(primary);

        if (busy) {
            h.speed.setText(Fmt.speed(t.speed));
        } else if (t.status == Task.DONE) {
            h.speed.setText("已完成");
        } else {
            h.speed.setText("速度 0 B/s");
        }
        h.speed.setTextColor(sub);

        if (t.status == Task.DONE) {
            h.eta.setText("");
        } else if (busy) {
            h.eta.setText("剩余 " + Fmt.eta(t.remain(), t.speed));
        } else {
            h.eta.setText("");
        }
        h.eta.setTextColor(sub);

        StringBuilder sb = new StringBuilder();
        if (t.total > 0) {
            sb.append("总大小 ").append(Fmt.size(t.total));
            sb.append(" · 已下载 ").append(Fmt.size(t.downloaded));
            sb.append(" · 还需 ").append(Fmt.size(t.remain()));
        } else {
            sb.append("大小未知");
        }
        sb.append(" · ").append(t.threads).append(" 线程");
        if (t.magnet) sb.append(" · 磁力");
        if (t.status == Task.ERROR && t.error != null && !t.error.isEmpty()) {
            sb.append("\n").append(t.error);
        }
        h.remain.setText(sb.toString());
        h.remain.setTextColor(t.status == Task.ERROR ? 0xFFE05252 : sub);

        h.btnToggle.setText(busy || t.status == Task.QUEUED ? "暂停" : "继续");
        h.btnToggle.setTextColor(primary);
        h.btnFolder.setTextColor(sub);
        h.btnDelete.setTextColor(0xFFE05252);

        h.btnToggle.setOnClickListener(v -> {
            if (t.status == Task.RUNNING || t.status == Task.CONNECTING || t.status == Task.QUEUED) {
                DownloadService.pauseTask(t.id);
            } else {
                t.threads = Prefs.threads();
                DownloadService.resumeTask(MainActivity.this, t.id);
                ensureService();
            }
            adapter.notifyDataSetChanged();
        });
        h.btnFolder.setOnClickListener(v -> openFolder(t));
        h.btnDelete.setOnClickListener(v -> new android.app.AlertDialog.Builder(MainActivity.this)
                .setTitle("删除任务")
                .setMessage("确定删除「" + t.displayName + "」吗？\n已下载的文件和临时分片也会一并删除，无法恢复。")
                .setPositiveButton("删除", (d, w) -> {
                    DownloadService.deleteTask(MainActivity.this, t.id);
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton("取消", null)
                .show());
    }
}