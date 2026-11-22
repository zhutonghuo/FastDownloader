package com.jisudownloader.ui;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.jisudownloader.R;
import com.jisudownloader.net.LanClient;
import com.jisudownloader.net.LanHub;
import com.jisudownloader.net.LanUtil;
import com.jisudownloader.service.LanService;
import com.jisudownloader.util.Fmt;
import com.jisudownloader.util.QrUtil;
import com.jisudownloader.util.ThemeHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class LanActivity extends Activity {

    private static final int REQ_HOST = 21;
    private static final int REQ_GUEST = 22;
    private static final int REQ_SCAN = 23;
    private static final int REQ_FOLDER = 24;

    private final Handler h = new Handler(Looper.getMainLooper());

    private TextView hostStatusView;
    private TextView codeView;
    private TextView addrView;
    private TextView peerView;
    private TextView guestStatusView;
    private ImageView qrImage;
    private View qrHint;
    private Bitmap qrBitmap;
    private String lastQrKey = "";
    private TextView btnHostToggle;
    private TextView btnJoinRoom;
    private EditText inputAddr;
    private EditText inputCode;
    private EditText inputName;

    private View cardChat;
    private ScrollView chatScroll;
    private LinearLayout chatBox;
    private EditText chatInput;

    private int codeLen = 4;
    private String hostStatus = "房间未开启";
    private String codeText = "----";
    private String addrText = "开启后显示局域网地址";
    private String peerText = "在线 0 人";
    private String guestStatus = "未加入房间";

    private volatile boolean guestOn = false;
    private volatile boolean listing = false;
    private volatile String guestBase = "";
    private volatile String guestCode = "";
    private volatile String guestName = "";
    private volatile String guestId = "";
    private volatile JSONArray guestChat = new JSONArray();

    private String lastChatSig = "";
    private long seenTs = 0;
    private boolean forceSeen = false;
    private TextView btnNewMsg;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lan);

        hostStatusView = findViewById(R.id.hostStatus);
        codeView = findViewById(R.id.codeText);
        addrView = findViewById(R.id.addrText);
        peerView = findViewById(R.id.peerText);
        guestStatusView = findViewById(R.id.guestStatus);
        qrImage = findViewById(R.id.qrImage);
        qrHint = findViewById(R.id.qrHint);
        btnHostToggle = findViewById(R.id.btnHostToggle);
        btnJoinRoom = findViewById(R.id.btnJoinRoom);
        inputAddr = findViewById(R.id.inputAddr);
        inputCode = findViewById(R.id.inputCode);
        inputName = findViewById(R.id.inputName);
        cardChat = findViewById(R.id.cardChat);
        chatScroll = findViewById(R.id.chatScroll);
        chatBox = findViewById(R.id.chatBox);
        chatInput = findViewById(R.id.chatInput);
        btnNewMsg = findViewById(R.id.btnNewMsg);
        btnNewMsg.setOnClickListener(v -> {
            seenTs = System.currentTimeMillis();
            hideNewMsg();
            scrollChatBottom();
        });

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        int[] lens = {R.id.len4, R.id.len5, R.id.len6};
        int[] lenVals = {4, 5, 6};
        for (int i = 0; i < lens.length; i++) {
            final int v = lenVals[i];
            findViewById(lens[i]).setOnClickListener(x -> {
                codeLen = v;
                paint();
            });
        }

        btnHostToggle.setOnClickListener(v -> toggleHost());
        findViewById(R.id.btnCopyAddr).setOnClickListener(v -> copyAddr());
        findViewById(R.id.btnAddLocal).setOnClickListener(v -> pickFiles(REQ_HOST));
        btnJoinRoom.setOnClickListener(v -> joinRoom());
        findViewById(R.id.btnScanJoin).setOnClickListener(v ->
                startActivityForResult(new Intent(this, ScanActivity.class), REQ_SCAN));
        qrImage.setOnClickListener(v -> showBigQr());
        findViewById(R.id.btnUploadFile).setOnClickListener(v -> pickAny());
        findViewById(R.id.btnUpFile).setOnClickListener(v -> pickAny());
        findViewById(R.id.btnUpFolder).setOnClickListener(v -> pickFolder());
        findViewById(R.id.btnSendText).setOnClickListener(v -> sendText());
        chatInput.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN) {
                sendText();
                return true;
            }
            return false;
        });
        findViewById(R.id.btnLeaveRoom).setOnClickListener(v -> {
            guestOn = false;
            guestChat = new JSONArray();
            guestStatus = "已退出房间";
            lastChatSig = "";
            seenTs = 0;
            updateTexts();
        });

        paint();
    }

    @Override
    protected void onResume() {
        super.onResume();
        paint();
        h.removeCallbacksAndMessages(null);
        tick();
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacksAndMessages(null);
    }

    private void paint() {
        int primary = ThemeHelper.primary(this);
        int sub = ThemeHelper.subColor(this);
        int text = ThemeHelper.textColor(this);
        int bg = ThemeHelper.bgColor(this);

        findViewById(R.id.root).setBackgroundColor(bg);
        findViewById(R.id.header).setBackgroundColor(primary);
        ((TextView) findViewById(R.id.lanTag)).setTextColor(0xDDFFFFFF);

        int[] cards = {R.id.cardHost, R.id.cardGuest, R.id.cardChat};
        for (int id : cards) ThemeHelper.card(this, findViewById(id), 14);

        int[] titles = {R.id.hostTitle, R.id.guestTitle, R.id.chatTitle};
        for (int id : titles) ((TextView) findViewById(id)).setTextColor(text);
        int[] subs = {R.id.hostStatus, R.id.addrText, R.id.peerText, R.id.guestStatus, R.id.qrHint};
        for (int id : subs) ((TextView) findViewById(id)).setTextColor(sub);
        codeView.setTextColor(primary);

        int[] inputs = {R.id.inputAddr, R.id.inputCode, R.id.inputName, R.id.chatInput};
        for (int id : inputs) {
            EditText e = findViewById(id);
            GradientDrawable g = new GradientDrawable();
            g.setColor(ThemeHelper.cardColor(this));
            g.setCornerRadius(ThemeHelper.dp(this, 10));
            g.setStroke(1, ThemeHelper.lineColor(this));
            e.setBackground(g);
            e.setTextColor(text);
            e.setHintTextColor(sub);
        }

        paintPrimary(R.id.btnHostToggle);
        paintPrimary(R.id.btnJoinRoom);
        paintPrimary(R.id.btnSendText);
        paintPrimary(R.id.btnNewMsg);
        int[] pills = {R.id.btnCopyAddr, R.id.btnAddLocal, R.id.btnScanJoin,
                R.id.btnUploadFile, R.id.btnLeaveRoom, R.id.btnUpFile, R.id.btnUpFolder};
        for (int id : pills) paintPill(id, false);
        paintPill(R.id.len4, codeLen == 4);
        paintPill(R.id.len5, codeLen == 5);
        paintPill(R.id.len6, codeLen == 6);
    }

    private void paintPrimary(int id) {
        TextView v = findViewById(id);
        GradientDrawable g = new GradientDrawable();
        g.setColor(ThemeHelper.primary(this));
        g.setCornerRadius(ThemeHelper.dp(this, 21));
        v.setBackground(g);
        v.setTextColor(0xFFFFFFFF);
    }

    private void paintPill(int id, boolean selected) {
        TextView v = findViewById(id);
        GradientDrawable g = new GradientDrawable();
        g.setColor(selected ? ThemeHelper.primary(this) : ThemeHelper.trackColor(this));
        g.setCornerRadius(ThemeHelper.dp(this, 18));
        v.setBackground(g);
        v.setTextColor(selected ? 0xFFFFFFFF : ThemeHelper.primary(this));
    }

    private void tick() {
        boolean host = LanService.isOn();
        LanHub hub = host ? LanService.hub() : null;
        if (host && hub == null) host = false;
        JSONArray chatArr;
        if (host) {
            hostStatus = "房间运行中 · 端口 " + hub.port() + " · 口令与端口不同";
            codeText = hub.code();
            addrText = hub.url();
            showQr(hub);
            List<String> ps = hub.peerNames();
            StringBuilder sb = new StringBuilder("在线 " + ps.size() + " 人");
            for (int i = 0; i < Math.min(ps.size(), 5); i++) {
                sb.append(i == 0 ? "：" : "、").append(ps.get(i));
            }
            peerText = sb.toString();
            chatArr = hostChat();
        } else {
            hostStatus = "房间未开启 · 开启后其他人可加入";
            codeText = "----";
            addrText = "开启后显示局域网地址";
            peerText = "在线 0 人";
            chatArr = null;
            hideQr();
        }
        if (guestOn && !listing) startChat();
        if (host) renderChat(chatArr, true);
        else if (guestOn) renderChat(guestChat, false);
        else renderChat(null, false);
        updateTexts();
        h.postDelayed(this::tick, 1500);
    }

    private void updateTexts() {
        hostStatusView.setText(hostStatus);
        codeView.setText(codeText);
        addrView.setText(addrText);
        peerView.setText(peerText);
        guestStatusView.setText(guestStatus);
        btnHostToggle.setText(LanService.isOn() ? "关闭房间" : "开启房间");
        btnJoinRoom.setText(guestOn ? "已加入 · 重新连接" : "加入房间");
        // 未开房间也未加入时，收起用不到的聊天互传区，界面更聚焦
        boolean connected = LanService.isOn() || guestOn;
        if (cardChat != null) cardChat.setVisibility(connected ? View.VISIBLE : View.GONE);
        if (!connected && btnNewMsg != null) btnNewMsg.setVisibility(View.GONE);
    }

    private void startChat() {
        listing = true;
        final String base = guestBase;
        final String code = guestCode;
        final String name = guestName;
        final String me = guestId;
        new Thread(() -> {
            LanClient.Result r = LanClient.chat(base, code, me, name);
            if (r.ok) guestChat = r.msgs;
            else guestStatus = r.msg;
            listing = false;
        }, "lan-chat").start();
    }

    private JSONArray hostChat() {
        JSONArray a = new JSONArray();
        LanHub hub = LanService.hub();
        if (hub == null) return a;
        for (LanHub.ChatMsg m : hub.chat()) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", m.id).put("kind", m.kind).put("fromId", m.fromId)
                        .put("fromName", m.fromName).put("text", m.text)
                        .put("fileId", m.fileId).put("fileName", m.fileName)
                        .put("fileSize", m.fileSize).put("time", m.time);
            } catch (Exception ignored) {
            }
            a.put(o);
        }
        return a;
    }

    private void renderChat(JSONArray arr, boolean host) {
        int n = arr == null ? 0 : arr.length();
        StringBuilder sb = new StringBuilder(host ? "H" : "G");
        long maxT = 0;
        for (int i = 0; i < n; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) {
                sb.append(o.optString("id")).append(o.optString("kind"))
                        .append(o.optLong("fileSize")).append('|');
                long t = o.optLong("time");
                if (t > maxT) maxT = t;
            }
        }
        String sig = sb.toString();
        if (seenTs == 0) seenTs = maxT; // 进入房间时把已有消息标记为已读
        if (sig.equals(lastChatSig)) {
            if (forceSeen) {
                seenTs = maxT;
                forceSeen = false;
                scrollChatBottom();
                hideNewMsg();
            }
            return;
        }
        lastChatSig = sig;

        int text = ThemeHelper.textColor(this);
        int sub = ThemeHelper.subColor(this);
        int primary = ThemeHelper.primary(this);
        int card = ThemeHelper.cardColor(this);
        int line = ThemeHelper.lineColor(this);

        boolean nearBottom = isChatNearBottom();
        chatBox.removeAllViews();
        if (n == 0) {
            TextView empty = new TextView(this);
            empty.setText("还没有消息，发条消息或上传文件试试～");
            empty.setTextSize(12);
            empty.setTextColor(sub);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, ThemeHelper.dp(this, 18), 0, ThemeHelper.dp(this, 18));
            chatBox.addView(empty);
            hideNewMsg();
            return;
        }
        for (int i = 0; i < n; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            final String mid = o.optString("id");
            final String kind = o.optString("kind", "text");
            final boolean own = o.optString("fromId").equals(myId());
            final String content = o.optString("text", "");
            final String fname = o.optString("fileName");
            final long fsize = o.optLong("fileSize");
            final String fid = o.optString("fileId");
            final long mtime = o.optLong("time");

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, ThemeHelper.dp(this, 3), 0, ThemeHelper.dp(this, 3));

            LinearLayout bubbleWrap = new LinearLayout(this);
            bubbleWrap.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rp.gravity = own ? Gravity.END : Gravity.START;
            bubbleWrap.setLayoutParams(rp);

            if (!own) {
                TextView sender = new TextView(this);
                sender.setText(o.optString("fromName", "匿名"));
                sender.setTextSize(11);
                sender.setTextColor(sub);
                sender.setPadding(0, 0, 0, ThemeHelper.dp(this, 2));
                bubbleWrap.addView(sender);
            }

            if ("text".equals(kind)) {
                TextView b = new TextView(this);
                b.setText(content);
                b.setTextSize(13);
                b.setPadding(ThemeHelper.dp(this, 12), ThemeHelper.dp(this, 8),
                        ThemeHelper.dp(this, 12), ThemeHelper.dp(this, 8));
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(ThemeHelper.dp(this, 12));
                g.setColor(own ? primary : card);
                g.setStroke(1, line);
                b.setBackground(g);
                b.setTextColor(own ? 0xFFFFFFFF : text);
                b.setMaxWidth(ThemeHelper.dp(this, 260));
                bubbleWrap.addView(b);
                if (own) {
                    TextView rc = makeSmallButton("撤回", false);
                    rc.setOnClickListener(v -> confirmRecall(mid));
                    LinearLayout.LayoutParams lp = smallLp();
                    lp.gravity = Gravity.END;
                    lp.topMargin = ThemeHelper.dp(this, 4);
                    bubbleWrap.addView(rc, lp);
                }
            } else {
                LinearLayout fb = new LinearLayout(this);
                fb.setOrientation(LinearLayout.VERTICAL);
                fb.setPadding(ThemeHelper.dp(this, 10), ThemeHelper.dp(this, 8),
                        ThemeHelper.dp(this, 10), ThemeHelper.dp(this, 8));
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(ThemeHelper.dp(this, 12));
                g.setColor(own ? primary : card);
                g.setStroke(1, line);
                fb.setBackground(g);

                TextView fn = new TextView(this);
                fn.setText("📎 " + fname);
                fn.setTextSize(13);
                fn.setTextColor(own ? 0xFFFFFFFF : text);
                fb.addView(fn);

                TextView fs = new TextView(this);
                fs.setText(Fmt.size(fsize));
                fs.setTextSize(11);
                fs.setTextColor(own ? 0xCCFFFFFF : sub);
                fb.addView(fs);

                LinearLayout acts = new LinearLayout(this);
                acts.setOrientation(LinearLayout.HORIZONTAL);
                acts.setGravity(Gravity.CENTER_VERTICAL);
                acts.setPadding(0, ThemeHelper.dp(this, 6), 0, 0);
                final String mName = fname;
                final String mId = fid;
                final boolean isHost = host;
                if (LanUtil.isPreviewable(fname)) {
                    TextView pv = makeSmallButton("预览", false);
                    pv.setOnClickListener(v -> previewFile(mId, mName, isHost));
                    acts.addView(pv, smallLp());
                }
                TextView dl = makeSmallButton("下载", true);
                dl.setOnClickListener(v -> download(mId, mName, isHost));
                acts.addView(dl, smallLp());
                if (own) {
                    TextView rc = makeSmallButton("撤回", false);
                    rc.setOnClickListener(v -> confirmRecall(mid));
                    acts.addView(rc, smallLp());
                }
                fb.addView(acts);
                bubbleWrap.addView(fb);
            }

            TextView time = new TextView(this);
            time.setText(shortTime(mtime));
            time.setTextSize(10);
            time.setTextColor(own ? 0xAAFFFFFF : sub);
            time.setGravity(own ? Gravity.END : Gravity.START);
            time.setPadding(0, ThemeHelper.dp(this, 2), 0, 0);
            bubbleWrap.addView(time);

            row.addView(bubbleWrap);
            chatBox.addView(row);
        }
        if (forceSeen) {
            seenTs = maxT;
            forceSeen = false;
            scrollChatBottom();
            hideNewMsg();
        } else if (nearBottom) {
            seenTs = maxT;
            scrollChatBottom();
            hideNewMsg();
        } else {
            int unread = 0;
            for (int i = 0; i < n; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && o.optLong("time") > seenTs) unread++;
            }
            if (unread > 0) showNewMsg(unread);
            else hideNewMsg();
        }
    }

    private boolean isChatNearBottom() {
        if (chatBox.getChildCount() == 0) return true;
        int scrollY = chatScroll.getScrollY();
        int viewport = chatScroll.getHeight();
        int content = chatBox.getMeasuredHeight();
        return (scrollY + viewport) >= content - ThemeHelper.dp(this, 60);
    }

    private void scrollChatBottom() {
        final ScrollView sv = chatScroll;
        sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
    }

    private void showNewMsg(int n) {
        if (btnNewMsg != null) {
            btnNewMsg.setText("↓ " + n + " 条新消息");
            btnNewMsg.setVisibility(View.VISIBLE);
        }
    }

    private void hideNewMsg() {
        if (btnNewMsg != null) btnNewMsg.setVisibility(View.GONE);
    }

    private TextView makeSmallButton(String label, boolean primary) {
        TextView v = new TextView(this);
        v.setText(label);
        v.setTextSize(12);
        v.setGravity(Gravity.CENTER);
        v.setPadding(ThemeHelper.dp(this, 14), 0, ThemeHelper.dp(this, 14), 0);
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? ThemeHelper.primary(this) : ThemeHelper.trackColor(this));
        g.setCornerRadius(ThemeHelper.dp(this, 16));
        v.setBackground(g);
        v.setTextColor(primary ? 0xFFFFFFFF : ThemeHelper.subColor(this));
        return v;
    }

    private LinearLayout.LayoutParams smallLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, ThemeHelper.dp(this, 30));
        lp.leftMargin = ThemeHelper.dp(this, 6);
        return lp;
    }

    private void confirmRecall(final String id) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("撤回")
                .setMessage("撤回后该消息与文件对房间内所有人生效")
                .setPositiveButton("撤回", (d, w) -> recall(id))
                .setNegativeButton("取消", null)
                .show();
    }

    private void recall(String id) {
        final boolean host = LanService.isOn();
        final String me = myId();
        if (host) {
            LanHub hub = LanService.hub();
            if (hub != null) {
                hub.recall(me, id);
                lastChatSig = "";
            }
            return;
        }
        if (!guestOn) {
            toast("请先加入房间");
            return;
        }
        new Thread(() -> {
            LanClient.Result r = LanClient.recall(guestBase, guestCode, me, id);
            runOnUiThread(() -> {
                lastChatSig = "";
                toast(r.msg);
            });
        }).start();
    }

    private String myId() {
        if (LanService.isOn()) {
            LanHub hub = LanService.hub();
            if (hub != null) return hub.selfId();
        }
        return guestId;
    }

    private String shortTime(long t) {
        if (t <= 0) return "";
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM-dd HH:mm",
                java.util.Locale.CHINA);
        return sdf.format(new java.util.Date(t));
    }

    private void showQr(LanHub hub) {
        String key = hub.url() + "/?code=" + hub.code();
        if (key.equals(lastQrKey)) return;
        lastQrKey = key;
        final String content = key;
        new Thread(() -> {
            try {
                byte[] png = QrUtil.png(content, 480);
                final Bitmap bm = BitmapFactory.decodeByteArray(png, 0, png.length);
                runOnUiThread(() -> {
                    if (!LanService.isOn()) return;
                    qrBitmap = bm;
                    qrImage.setImageBitmap(bm);
                    qrImage.setVisibility(View.VISIBLE);
                    qrHint.setVisibility(View.VISIBLE);
                });
            } catch (Exception ignored) {
            }
        }, "qr-make").start();
    }

    private void hideQr() {
        lastQrKey = "";
        qrBitmap = null;
        qrImage.setVisibility(View.GONE);
        qrHint.setVisibility(View.GONE);
    }

    private void showBigQr() {
        if (qrBitmap == null) return;
        ImageView big = new ImageView(this);
        big.setImageBitmap(qrBitmap);
        big.setBackgroundColor(0xFFFFFFFF);
        int pad = ThemeHelper.dp(this, 14);
        big.setPadding(pad, pad, pad, pad);
        big.setScaleType(ImageView.ScaleType.FIT_CENTER);
        big.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ThemeHelper.dp(this, 320)));
        new android.app.AlertDialog.Builder(this)
                .setTitle("扫一扫加入房间")
                .setView(big)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void splitAddressIntoFields() {
        String raw = inputAddr.getText().toString().trim();
        int q = raw.indexOf('?');
        if (q < 0) return;
        String found = null;
        for (String p : raw.substring(q + 1).split("&")) {
            if (p.startsWith("code=")) found = LanUtil.dec(p.substring(5));
        }
        String base = raw.substring(0, q);
        while (base.endsWith("/") && base.length() > 8) {
            base = base.substring(0, base.length() - 1);
        }
        inputAddr.setText(base);
        if (found != null && found.matches("[0-9]{4,8}")) inputCode.setText(found);
    }

    private void applyScanned(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.matches("[0-9]{4,8}")) {
            inputCode.setText(s);
            toast("已填入口令，请确认地址");
            return;
        }
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            toast("无法识别这个二维码");
            return;
        }
        inputAddr.setText(s);
        inputCode.setText("");
        splitAddressIntoFields();
        if (!inputCode.getText().toString().trim().isEmpty()) {
            toast("已识别房间，正在加入…");
            joinRoom();
        } else {
            toast("已填入地址，请补上口令");
        }
    }

    private void toggleHost() {
        if (LanService.isOn()) {
            LanService.stop(this);
            lastChatSig = "";
            seenTs = 0;
            toast("房间已关闭");
            return;
        }
        seenTs = 0;
        LanService.start(this, codeLen);
        toast("正在开启房间…");
        h.postDelayed(() -> {
            if (!LanService.isOn() && LanService.lastError != null && !LanService.lastError.isEmpty()) {
                toast("开启失败：" + LanService.lastError);
            }
        }, 1600);
    }

    private void copyAddr() {
        LanHub hub = LanService.hub();
        if (hub == null) {
            toast("请先开启房间");
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            toast("无法访问剪贴板");
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("lan", hub.url() + "\n口令 " + hub.code()));
        toast("已复制地址和口令");
    }

    private void joinRoom() {
        splitAddressIntoFields();
        String base = LanClient.normalize(inputAddr.getText().toString());
        String code = inputCode.getText().toString().trim();
        if (base.isEmpty()) {
            toast("请填写主机地址");
            return;
        }
        if (!code.matches("[0-9]{4,8}")) {
            toast("请输入 4~8 位数字口令");
            return;
        }
        String name = inputName.getText().toString().trim();
        if (name.isEmpty()) {
            name = "手机-" + (int) (Math.random() * 900 + 100);
            inputName.setText(name);
        }
        guestOn = false;
        guestStatus = "连接中…";
        updateTexts();
        final String b = base;
        final String c = code;
        final String n = name;
        new Thread(() -> {
            LanClient.Result r = LanClient.join(b, c, n);
            runOnUiThread(() -> {
                if (r.ok) {
                    guestOn = true;
                    guestBase = b;
                    guestCode = c;
                    guestName = n;
                    guestId = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
                    guestChat = new JSONArray();
                    guestStatus = "已加入房间";
                    lastChatSig = "";
                    seenTs = 0;
                    toast("已加入房间 " + c);
                } else {
                    guestStatus = r.msg;
                    toast(r.msg);
                }
                updateTexts();
            });
        }, "lan-join").start();
    }

    private void pickAny() {
        if (LanService.isOn()) pickFiles(REQ_HOST);
        else if (guestOn) pickFiles(REQ_GUEST);
        else toast("请先开启或加入房间");
    }

    private void pickFiles(int req) {
        if (req == REQ_GUEST && !guestOn) {
            toast("请先加入房间");
            return;
        }
        if (req == REQ_HOST && !LanService.isOn()) {
            toast("请先开启房间");
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, req);
        } catch (Exception e) {
            toast("无法打开文件选择器");
        }
    }

    private void pickFolder() {
        if (!LanService.isOn() && !guestOn) {
            toast("请先开启或加入房间");
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        try {
            startActivityForResult(i, REQ_FOLDER);
        } catch (Exception e) {
            toast("无法打开文件夹选择器");
        }
    }

    @Override
    protected void onActivityResult(int req, int resultCode, Intent data) {
        super.onActivityResult(req, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (req == REQ_SCAN) {
            String text = data.getStringExtra(ScanActivity.EXTRA_TEXT);
            if (text != null && !text.isEmpty()) applyScanned(text);
            return;
        }
        if (req == REQ_FOLDER) {
            Uri tree = data.getData();
            if (tree != null) {
                try {
                    getContentResolver().takePersistableUriPermission(tree,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                uploadTree(tree);
            }
            return;
        }
        List<Uri> uris = new ArrayList<>();
        ClipData cd = data.getClipData();
        if (cd != null) {
            for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        for (Uri u : uris) handlePicked(u, req);
    }

    private void handlePicked(final Uri uri, final int req) {
        final String name = displayName(uri);
        final long size = fileSize(uri);
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new Exception("无法读取文件");
                if (req == REQ_HOST) {
                    LanHub hub = LanService.hub();
                    if (hub == null) {
                        runOnUiThread(() -> toast("房间未开启"));
                        return;
                    }
                    hub.addLocal(in, name, myId(), "本机");
                    runOnUiThread(() -> {
                        lastChatSig = "";
                        toast("已添加到房间：" + name);
                    });
                } else {
                    LanClient.Result r = LanClient.upload(guestBase, guestCode, myId(), guestName,
                            name, in, size, null);
                    runOnUiThread(() -> {
                        lastChatSig = "";
                        toast(r.msg);
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> toast("处理失败：" + name));
            }
        }, "lan-pick").start();
    }

    private void uploadTree(Uri tree) {
        final List<Uri> files = new ArrayList<>();
        DocumentFile root = DocumentFile.fromTreeUri(this, tree);
        collect(root, files);
        if (files.isEmpty()) {
            runOnUiThread(() -> toast("该文件夹是空的"));
            return;
        }
        final boolean host = LanService.isOn();
        final String my = myId();
        final String nm = host ? "本机" : guestName;
        final int total = files.size();
        new Thread(() -> {
            int ok = 0;
            for (Uri u : files) {
                final String fn = displayName(u);
                final long sz = fileSize(u);
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    if (in == null) continue;
                    if (host) {
                        LanHub hub = LanService.hub();
                        if (hub == null) break;
                        hub.addLocal(in, fn, my, nm);
                    } else {
                        LanClient.Result r = LanClient.upload(guestBase, guestCode, my, nm, fn, in, sz, null);
                        if (!r.ok) continue;
                    }
                    ok++;
                } catch (Exception ignored) {
                }
            }
            final int done = ok;
            runOnUiThread(() -> {
                lastChatSig = "";
                toast("已上传 " + done + "/" + total + " 个文件");
            });
        }, "lan-folder").start();
    }

    private void collect(DocumentFile dir, List<Uri> out) {
        if (dir == null || !dir.isDirectory()) return;
        for (DocumentFile c : dir.listFiles()) {
            if (c == null) continue;
            if (c.isDirectory()) collect(c, out);
            else if (c.isFile()) out.add(c.getUri());
        }
    }

    private void sendText() {
        String t = chatInput.getText().toString().trim();
        if (t.isEmpty()) return;
        chatInput.setText("");
        final boolean host = LanService.isOn();
        final String me = myId();
        final String nm = host ? "本机" : guestName;
        if (host) {
            LanHub hub = LanService.hub();
            if (hub != null) {
                hub.sendText(me, nm, t);
                lastChatSig = "";
                forceSeen = true;
            }
            return;
        }
        if (!guestOn) {
            toast("请先加入房间");
            return;
        }
        new Thread(() -> {
            LanClient.Result r = LanClient.sendText(guestBase, guestCode, me, nm, t);
            runOnUiThread(() -> {
                if (r.ok) {
                    lastChatSig = "";
                    forceSeen = true;
                } else toast(r.msg);
            });
        }).start();
    }

    private String displayName(Uri u) {
        String n = "file_" + System.currentTimeMillis();
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0 && !c.isNull(i)) n = c.getString(i);
            }
        } catch (Exception ignored) {
        }
        return n;
    }

    private long fileSize(Uri u) {
        try (AssetFileDescriptor afd = getContentResolver().openAssetFileDescriptor(u, "r")) {
            if (afd != null) return afd.getLength();
        } catch (Exception ignored) {
        }
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.SIZE);
                if (i >= 0 && !c.isNull(i)) return c.getLong(i);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private void download(String id, String name, boolean isHost) {
        String cls = LanUtil.mediaClass(name);
        if (isHost) {
            LanHub hub = LanService.hub();
            if (hub == null) {
                toast("房间已关闭");
                return;
            }
            LanHub.RoomFile target = null;
            for (LanHub.RoomFile f : hub.snapshot()) {
                if (f.id.equals(id)) {
                    target = f;
                    break;
                }
            }
            if (target == null) return;
            saveHostMedia(target, cls);
            return;
        }
        if (!guestOn) {
            toast("请先加入房间");
            return;
        }
        try {
            String url = guestBase + "/api/download?code=" + LanUtil.enc(guestCode)
                    + "&id=" + LanUtil.enc(id);
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setTitle(name);
            r.setDescription("极速下载器 · 局域网互传");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            String dir = cls.equals("image") ? Environment.DIRECTORY_DCIM
                    : cls.equals("video") ? Environment.DIRECTORY_MOVIES
                    : cls.equals("audio") ? Environment.DIRECTORY_MUSIC
                    : Environment.DIRECTORY_DOWNLOADS;
            r.setDestinationInExternalPublicDir(dir, "极速下载器/" + Fmt.safeName(name));
            dm.enqueue(r);
            String where = cls.equals("image") ? "相册(DCIM)" : cls.equals("video") ? "视频(Movies)"
                    : cls.equals("audio") ? "音乐(Music)" : "Download";
            toast("已开始下载到系统" + where);
        } catch (Exception e) {
            toast("下载失败：" + (e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    private void saveHostMedia(LanHub.RoomFile target, String cls) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && (cls.equals("image") || cls.equals("video") || cls.equals("audio"))) {
                Uri coll = cls.equals("image") ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        : cls.equals("video") ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                String rel = (cls.equals("image") ? "DCIM" : cls.equals("video") ? "Movies" : "Music")
                        + "/极速下载器";
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, Fmt.safeName(target.name));
                v.put(MediaStore.MediaColumns.MIME_TYPE, LanUtil.mime(target.name));
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = getContentResolver().insert(coll, v);
                if (uri == null) throw new Exception("无法写入媒体库");
                try (InputStream is = new FileInputStream(target.file);
                     OutputStream os = getContentResolver().openOutputStream(uri)) {
                    copyStream(is, os);
                }
                v.clear();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(uri, v, null, null);
                String where = cls.equals("image") ? "相册" : cls.equals("video") ? "视频库" : "音乐库";
                toast("已保存到系统" + where + "（可在图库/播放器查看）");
            } else {
                File dir = fallbackDir(cls);
                String safe = Fmt.safeName(target.name);
                File dest = new File(dir, safe);
                int k = 1;
                while (dest.exists()) {
                    dest = new File(dir, k + "_" + safe);
                    k++;
                }
                try (InputStream is = new FileInputStream(target.file);
                     OutputStream os = new FileOutputStream(dest)) {
                    copyStream(is, os);
                }
                toast("已保存到 " + dest.getAbsolutePath());
            }
        } catch (Exception e) {
            toast("保存失败：" + (e.getMessage() == null ? "请检查存储权限" : e.getMessage()));
        }
    }

    private File fallbackDir(String cls) {
        String d = cls.equals("image") ? Environment.DIRECTORY_DCIM
                : cls.equals("video") ? Environment.DIRECTORY_MOVIES
                : cls.equals("audio") ? Environment.DIRECTORY_MUSIC
                : Environment.DIRECTORY_DOWNLOADS;
        File dir = Environment.getExternalStoragePublicDirectory(d);
        if (dir.exists() && dir.canWrite()) return dir;
        File ef = getExternalFilesDir(d);
        if (ef != null && (ef.exists() || ef.mkdirs())) return ef;
        return getFilesDir();
    }

    private void previewFile(String id, String name, boolean isHost) {
        String cls = LanUtil.mediaClass(name);
        if (cls.equals("other")) {
            toast("该文件类型暂不支持预览");
            return;
        }
        if (isHost) {
            LanHub hub = LanService.hub();
            if (hub == null) {
                toast("房间已关闭");
                return;
            }
            LanHub.RoomFile t = null;
            for (LanHub.RoomFile f : hub.snapshot()) {
                if (f.id.equals(id)) {
                    t = f;
                    break;
                }
            }
            if (t == null) return;
            launchBuiltinPreview(t.file.getAbsolutePath(), name, cls);
            return;
        }
        if (!guestOn) {
            toast("请先加入房间");
            return;
        }
        toast("正在准备预览…");
        new Thread(() -> {
            try {
                File dir = new File(getFilesDir(), "lanpreview");
                dir.mkdirs();
                File out = new File(dir, Fmt.safeName(name));
                String url = guestBase + "/api/download?code=" + LanUtil.enc(guestCode)
                        + "&id=" + LanUtil.enc(id);
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(120000);
                try (InputStream is = c.getInputStream();
                     OutputStream os = new FileOutputStream(out)) {
                    copyStream(is, os);
                }
                c.disconnect();
                final File fout = out;
                runOnUiThread(() -> launchBuiltinPreview(fout.getAbsolutePath(), name, cls));
            } catch (Exception e) {
                runOnUiThread(() -> toast("预览失败：" + (e.getMessage() == null ? "" : e.getMessage())));
            }
        }, "lan-preview").start();
    }

    private void launchBuiltinPreview(String path, String name, String cls) {
        if (path == null) {
            toast("预览失败：文件不可用");
            return;
        }
        Intent it = new Intent(this, PreviewActivity.class);
        it.putExtra(PreviewActivity.EXTRA_PATH, path);
        it.putExtra(PreviewActivity.EXTRA_NAME, name);
        it.putExtra(PreviewActivity.EXTRA_CLASS, cls);
        startActivity(it);
    }

    private static void copyStream(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
