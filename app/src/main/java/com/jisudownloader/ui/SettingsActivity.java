package com.jisudownloader.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.jisudownloader.R;
import com.jisudownloader.service.DownloadService;
import com.jisudownloader.util.Prefs;
import com.jisudownloader.util.ThemeHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import androidx.core.content.FileProvider;

public class SettingsActivity extends Activity {

    private TextView valueThreads;
    private TextView valueMaxTasks;
    private TextView valuePath;
    private SeekBar seekThreads;
    private SeekBar seekMaxTasks;
    private SeekBar seekRetry;
    private TextView valueRetry;
    private Switch swAuto;
    private EditText inputUa;
    private LinearLayout themeRow;
    private TextView updateInfo;
    private String pendingUrl = "";
    private String pendingTag = "";
    private android.net.Uri pendingInstallUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        valueThreads = findViewById(R.id.valueThreads);
        valueMaxTasks = findViewById(R.id.valueMaxTasks);
        valuePath = findViewById(R.id.valuePath);
        seekThreads = findViewById(R.id.seekThreads);
        seekMaxTasks = findViewById(R.id.seekMaxTasks);
        seekRetry = findViewById(R.id.seekRetry);
        valueRetry = findViewById(R.id.valueRetry);
        swAuto = findViewById(R.id.swAuto);
        inputUa = findViewById(R.id.inputUa);
        themeRow = findViewById(R.id.themeRow);
        updateInfo = findViewById(R.id.updateInfo);
        findViewById(R.id.btnUpdate).setOnClickListener(v -> {
            if (pendingUrl != null && !pendingUrl.isEmpty()) downloadAndInstall(pendingUrl);
            else checkUpdate();
        });

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnPickPath).setOnClickListener(v -> pick());
        findViewById(R.id.btnSupport).setOnClickListener(v -> showSupport());
        findViewById(R.id.btnResetPath).setOnClickListener(v -> {
            Prefs.setPath("");
            Prefs.setPathTree("");
            paintPath();
            toast("已恢复默认路径");
        });

        seekThreads.setProgress(Prefs.threads() - 1);
        seekThreads.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int v = Prefs.clampThreads(progress + 1);
                valueThreads.setText(String.valueOf(v));
                if (fromUser) Prefs.setThreads(v);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                Prefs.setThreads(sb.getProgress() + 1);
            }
        });

        seekMaxTasks.setProgress(Prefs.maxTasks() - 1);
        seekMaxTasks.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                valueMaxTasks.setText(String.valueOf(progress + 1));
                if (fromUser) Prefs.setMaxTasks(progress + 1);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });

        seekRetry.setProgress(Prefs.retry() - 1);
        seekRetry.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int v = progress + 1;
                valueRetry.setText(String.valueOf(v));
                if (fromUser) Prefs.setRetry(v);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                Prefs.setRetry(sb.getProgress() + 1);
            }
        });

        swAuto.setChecked(Prefs.autoStart());
        swAuto.setOnCheckedChangeListener((b, checked) -> Prefs.setAutoStart(checked));

        inputUa.setText(Prefs.userAgent());

        buildThemes();
        paint();
    }

    @Override
    protected void onPause() {
        super.onPause();
        String ua = inputUa.getText().toString().trim();
        if (!ua.isEmpty()) Prefs.setUserAgent(ua);
        DownloadService.pump(this);
    }

    private void showSupport() {
        int bg = ThemeHelper.bgColor(this);
        int primary = ThemeHelper.primary(this);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ThemeHelper.dp(this, 16), ThemeHelper.dp(this, 16),
                ThemeHelper.dp(this, 16), ThemeHelper.dp(this, 12));

        ImageView iv = new ImageView(this);
        Bitmap bmp = BitmapFactory.decodeResource(getResources(), R.drawable.support_qr);
        iv.setImageBitmap(bmp);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ThemeHelper.dp(this, 300));
        box.addView(iv, ip);

        TextView cap = new TextView(this);
        cap.setText("烛童火 的赞赏码 · 长按图片也可保存");
        cap.setTextSize(12);
        cap.setGravity(Gravity.CENTER);
        cap.setPadding(0, ThemeHelper.dp(this, 8), 0, 0);
        cap.setTextColor(ThemeHelper.subColor(this));
        box.addView(cap);

        TextView save = new TextView(this);
        save.setText("保存到手机（Download）");
        save.setTextSize(14);
        save.setGravity(Gravity.CENTER);
        save.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ThemeHelper.dp(this, 44));
        sp.topMargin = ThemeHelper.dp(this, 12);
        GradientDrawable sg = new GradientDrawable();
        sg.setColor(primary);
        sg.setCornerRadius(ThemeHelper.dp(this, 22));
        save.setBackground(sg);
        save.setOnClickListener(v -> saveQr());
        box.addView(save, sp);

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("支持一下")
                .setView(box)
                .setNegativeButton("关闭", null)
                .create();
        d.getWindow().setBackgroundDrawable(new ColorDrawable(bg));
        d.show();
        d.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(primary);
    }

    private void saveQr() {
        Bitmap bmp = BitmapFactory.decodeResource(getResources(), R.drawable.support_qr);
        if (bmp == null) {
            toast("图片资源缺失");
            return;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, "极速下载器赞赏码.jpg");
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (u == null) {
                    toast("保存失败");
                    return;
                }
                OutputStream os = getContentResolver().openOutputStream(u);
                if (os != null) {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 95, os);
                    os.close();
                }
                toast("已保存到 Download");
            } catch (Exception e) {
                toast("保存失败：" + e.getMessage());
            }
        } else {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 77);
                return;
            }
            try {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, "极速下载器赞赏码.jpg");
                int n = 1;
                while (f.exists()) f = new File(dir, "极速下载器赞赏码(" + (n++) + ").jpg");
                OutputStream os = new FileOutputStream(f);
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, os);
                os.close();
                toast("已保存：" + f.getName());
            } catch (Exception e) {
                toast("保存失败：" + e.getMessage());
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 77 && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            saveQr();
        } else if (requestCode == 77) {
            toast("没有存储权限，无法保存");
        }
    }

    private void pick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Intent i;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.putExtra("android.content.extra.SHOW_ADVANCED", true);
            } else {
                i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try {
                startActivityForResult(i, 42);
            } catch (Exception e) {
                toast("设备不支持选择文件夹");
            }
        } else {
            toast("系统版本过低，请手动在应用目录下载");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 42 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            String path = treeUriToPath(uri);
            if (path != null && isWritable(new File(path))) {
                Prefs.setPath(path);
                Prefs.setPathTree("");
                paintPath();
                toast("已设置为：" + path);
            } else {
                Prefs.setPath("");
                Prefs.setPathTree(uri.toString());
                paintPath();
                toast("已选择目录（将经系统授权写入）");
            }
        }

        if (requestCode == 99) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && getPackageManager().canRequestPackageInstalls()
                && pendingInstallUri != null) {
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setDataAndType(pendingInstallUri, "application/vnd.android.package-archive");
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(it);
            } catch (Exception e) {
                toast("安装失败：" + (e.getMessage() == null ? "" : e.getMessage()));
            }
        } else {
            toast("未获得安装权限，请在设置中开启“允许安装未知应用”");
        }
    }
    }

    // 把 ACTION_OPEN_DOCUMENT_TREE 返回的 tree URI 解析为真实文件系统路径；解析不到则返回 null
    private String treeUriToPath(Uri uri) {
        if (uri == null) return null;
        String auth = uri.getAuthority();
        if ("com.android.externalstorage.documents".equals(auth)) {
            String tp = uri.getPath(); // /tree/primary:Download/document/primary:Download
            int i = tp.indexOf("/tree/");
            if (i >= 0) {
                String id = tp.substring(i + 6);
                int d = id.indexOf("/document/");
                if (d >= 0) id = id.substring(0, d);
                return extStoragePath(id);
            }
            return null;
        }
        if ("com.android.providers.downloads.documents".equals(auth)) {
            File d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            return d == null ? null : d.getAbsolutePath();
        }
        // 其它 provider：尝试读取 _data 列
        if ("content".equals(uri.getScheme())) {
            try (android.database.Cursor c = getContentResolver().query(uri,
                    new String[]{"_data"}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex("_data");
                    if (idx >= 0) {
                        String p = c.getString(idx);
                        if (p != null) return p;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String extStoragePath(String id) {
        int colon = id.indexOf(':');
        if (colon < 0) return null;
        String volume = id.substring(0, colon);
        String rest = id.substring(colon + 1);
        String root;
        if ("primary".equalsIgnoreCase(volume)) {
            root = Environment.getExternalStorageDirectory().getAbsolutePath();
        } else {
            root = null;
            File[] vols = getApplicationContext().getExternalFilesDirs(null);
            if (vols != null) {
                for (File f : vols) {
                    if (f != null && f.getAbsolutePath().contains(volume)) {
                        String p = f.getAbsolutePath();
                        int idx = p.indexOf("/Android/");
                        if (idx >= 0) p = p.substring(0, idx);
                        root = p;
                        break;
                    }
                }
            }
        }
        if (root == null) return null;
        return rest.isEmpty() ? root : root + "/" + rest;
    }

    private boolean isWritable(File dir) {
        if (dir == null) return false;
        if (!dir.exists() && !dir.mkdirs()) return false;
        try {
            File t = new File(dir, ".jisu_write_test");
            boolean ok = t.createNewFile();
            if (ok) t.delete();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    private void buildThemes() {
        themeRow.removeAllViews();
        final String[] names = {"科技蓝", "清新绿", "活力橙", "暗夜黑"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextSize(13);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0, ThemeHelper.dp(this, 40), 1f);
            lp.setMargins(0, 0, ThemeHelper.dp(this, 8), 0);
            t.setLayoutParams(lp);
            themeRow.addView(t);
            t.setOnClickListener(v -> {
                Prefs.setTheme(ThemeHelper.THEME_KEYS[idx]);
                ThemeHelper.apply(SettingsActivity.this);
                recreate();
            });
        }
    }

    private void paint() {
        int primary = ThemeHelper.primary(this);
        int text = ThemeHelper.textColor(this);
        int sub = ThemeHelper.subColor(this);

        findViewById(R.id.root).setBackgroundColor(ThemeHelper.bgColor(this));
        findViewById(R.id.header).setBackgroundColor(primary);

        int[] cards = {R.id.cardThreads, R.id.cardTheme, R.id.cardPath, R.id.cardMax,
                R.id.cardRetry, R.id.cardAuto, R.id.cardUa, R.id.cardSupport, R.id.cardUpdate};
        for (int id : cards) ThemeHelper.card(this, findViewById(id), 14);

        int[] labels = {R.id.labelThreads, R.id.labelTheme, R.id.labelPath, R.id.labelMaxTasks,
                R.id.labelAuto, R.id.labelRetry, R.id.labelUa, R.id.labelSupport, R.id.labelUpdate};
        for (int id : labels) ((TextView) findViewById(id)).setTextColor(text);

        int[] hints = {R.id.hintThreads, R.id.hintTheme, R.id.hintAuto, R.id.labelMin,
                R.id.labelMax, R.id.about, R.id.hintRetry, R.id.hintSupport, R.id.updateInfo};
        for (int id : hints) ((TextView) findViewById(id)).setTextColor(sub);

        valueThreads.setTextColor(primary);
        valueMaxTasks.setTextColor(primary);
        valueRetry.setTextColor(primary);
        valuePath.setTextColor(sub);

        GradientDrawable pg = new GradientDrawable();
        pg.setColor(primary);
        pg.setCornerRadius(ThemeHelper.dp(this, 19));
        findViewById(R.id.btnPickPath).setBackground(pg);
        findViewById(R.id.btnSupport).setBackground(pg.getConstantState().newDrawable().mutate());
        ((GradientDrawable) findViewById(R.id.btnSupport).getBackground())
                .setCornerRadius(ThemeHelper.dp(this, 20));

        GradientDrawable ug = new GradientDrawable();
        ug.setColor(primary);
        ug.setCornerRadius(ThemeHelper.dp(this, 20));
        findViewById(R.id.btnUpdate).setBackground(ug);

        GradientDrawable ig = new GradientDrawable();
        ig.setColor(ThemeHelper.cardColor(this));
        ig.setCornerRadius(ThemeHelper.dp(this, 12));
        ig.setStroke(1, ThemeHelper.lineColor(this));
        inputUa.setBackground(ig);
        inputUa.setTextColor(text);

        paintPath();
        paintThemes();
    }

    private void paintPath() {
        String custom = Prefs.path();
        if (custom != null && !custom.isEmpty()) {
            valuePath.setText(custom);
            return;
        }
        String tree = Prefs.pathTree();
        if (tree != null && !tree.isEmpty()) {
            valuePath.setText("已选择（系统授权文件夹）");
            return;
        }
        File d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (d != null && d.exists() && d.canWrite()) {
            valuePath.setText(d.getAbsolutePath() + "（默认）");
            return;
        }
        File ext = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        valuePath.setText(ext != null ? ext.getAbsolutePath() + "（默认·应用专属目录）" : getFilesDir().getAbsolutePath());
    }

    private void paintThemes() {
        final String[] keys = ThemeHelper.THEME_KEYS;
        String cur = Prefs.theme();
        for (int i = 0; i < themeRow.getChildCount(); i++) {
            TextView t = (TextView) themeRow.getChildAt(i);
            boolean sel = keys[i].equals(cur);
            int p = ThemeHelper.THEME_KEYS[i].equals("dark") ? 0xFF6E8BFF : themeSwatch(i);
            GradientDrawable g = new GradientDrawable();
            g.setColor(sel ? p : 0x22000000);
            g.setCornerRadius(ThemeHelper.dp(this, 10));
            g.setStroke(sel ? 0 : 1, ThemeHelper.lineColor(this));
            t.setBackground(g);
            t.setTextColor(sel ? 0xFFFFFFFF : ThemeHelper.textColor(this));
        }
    }

    private int themeSwatch(int i) {
        switch (i) {
            case 1:
                return 0xFF17A673;
            case 2:
                return 0xFFF07A1A;
            default:
                return 0xFF1B6EF3;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private String currentVer() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.16";
        }
    }

    private void checkUpdate() {
        if (updateInfo == null) return;
        updateInfo.setText("正在检查更新…");
        pendingUrl = "";
        pendingTag = "";
        new Thread(() -> {
            try {
                String url = "https://api.github.com/repos/zhutonghuo/FastDownloader/releases?per_page=100";
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setRequestProperty("User-Agent", "JisuDownloader-Update");
                int rc = c.getResponseCode();
                if (rc != 200) throw new Exception("服务器返回 " + rc);
                StringBuilder sb = new StringBuilder();
                try (InputStream in = c.getInputStream();
                     BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                c.disconnect();
                JSONArray releases = new JSONArray(sb.toString());
                JSONObject best = null;
                String bestTag = "";
                for (int k = 0; k < releases.length(); k++) {
                    JSONObject rel = releases.optJSONObject(k);
                    if (rel == null || rel.optBoolean("draft") || rel.optBoolean("prerelease")) continue;
                    String t = rel.optString("tag_name", "");
                    if (t.isEmpty()) continue;
                    if (best == null || cmpVer(stripVer(t), stripVer(bestTag)) > 0) {
                        best = rel;
                        bestTag = t;
                    }
                }
                if (best == null) throw new Exception("没有可用版本");
                JSONArray assets = best.optJSONArray("assets");
                String apkUrl = null;
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.optJSONObject(i);
                        if (a == null) continue;
                        String nm = a.optString("name", "").toLowerCase();
                        if (nm.endsWith(".apk")) {
                            apkUrl = a.optString("browser_download_url", "");
                            break;
                        }
                    }
                }
                if (apkUrl == null || apkUrl.isEmpty()) throw new Exception("未找到安装包");
                final String latestTag = bestTag;
                final String assetUrl = apkUrl;
                int cmp = cmpVer(stripVer(latestTag), currentVer());
                if (cmp <= 0) {
                    runOnUiThread(() -> updateInfo.setText("已是最新版本（" + latestTag + "）"));
                    return;
                }
                pendingUrl = assetUrl;
                pendingTag = latestTag;
                runOnUiThread(() -> {
                    updateInfo.setText("发现新版本：" + latestTag + "（当前 " + currentVer() + "）");
                    TextView b = findViewById(R.id.btnUpdate);
                    b.setText("下载并安装 " + latestTag);
                });
            } catch (Exception e) {
                final String msg = e.getMessage();
                runOnUiThread(() -> updateInfo.setText("检查失败：" + (msg == null ? "未知错误" : msg)));
            }
        }, "check-update").start();
    }

    private void downloadAndInstall(String url) {
        if (updateInfo != null) updateInfo.setText("正在下载安装包…");
        TextView b = findViewById(R.id.btnUpdate);
        if (b != null) b.setEnabled(false);
        new Thread(() -> {
            try {
                File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建目录");
                File apk = new File(dir, "ZDown.apk");
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(10000);
                c.setReadTimeout(120000);
                int rc = c.getResponseCode();
                if (rc != 200) throw new Exception("下载失败 代码 " + rc);
                try (InputStream in = new BufferedInputStream(c.getInputStream());
                     FileOutputStream os = new FileOutputStream(apk)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
                c.disconnect();
                final File f = apk;
                runOnUiThread(() -> {
                    TextView bb = findViewById(R.id.btnUpdate);
                    if (bb != null) bb.setEnabled(true);
                    installApk(f);
                });
            } catch (Exception e) {
                final String msg = e.getMessage();
                runOnUiThread(() -> {
                    TextView bb = findViewById(R.id.btnUpdate);
                    if (bb != null) bb.setEnabled(true);
                    if (updateInfo != null)
                        updateInfo.setText("下载失败：" + (msg == null ? "未知错误" : msg));
                });
            }
        }, "dl-apk").start();
    }

    private void installApk(File apk) {
        if (apk == null || !apk.exists()) {
            toast("安装包不存在");
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, "com.jisudownloader.fileprovider", apk);
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setDataAndType(uri, "application/vnd.android.package-archive");
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (getPackageManager().canRequestPackageInstalls()) {
                    startActivity(it);
                } else {
                    pendingInstallUri = uri;
                    Intent perm = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName()));
                    try {
                        startActivityForResult(perm, 99);
                    } catch (Exception e) {
                        Intent app = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName()));
                        startActivityForResult(app, 99);
                    }
                }
            } else {
                startActivity(it);
            }
        } catch (Exception e) {
            toast("安装失败：" + (e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    private static String stripVer(String s) {
        if (s == null) return "";
        int i = 0;
        while (i < s.length() && !Character.isDigit(s.charAt(i))) i++;
        return s.substring(i);
    }

    private static int cmpVer(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? safeInt(pa[i]) : 0;
            int y = i < pb.length ? safeInt(pb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int safeInt(String s) {
        if (s == null) return 0;
        int j = 0;
        while (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) j++;
        String num = s.substring(0, j).replaceAll("\\..*", "");
        try {
            return Integer.parseInt(num);
        } catch (Exception e) {
            return 0;
        }
    }
}