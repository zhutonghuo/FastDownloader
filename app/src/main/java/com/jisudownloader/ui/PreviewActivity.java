package com.jisudownloader.ui;

import android.app.Activity;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import com.jisudownloader.R;

import java.io.File;

public class PreviewActivity extends Activity {
    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_CLASS = "cls";

    private VideoView videoView;
    private ImageView imageView;
    private LinearLayout audioPanel;
    private Button playBtn;
    private SeekBar seek;
    private TextView timeText;
    private MediaPlayer player;
    private boolean prepared = false;
    private boolean playing = false;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        public void run() {
            if (player != null && prepared && playing) {
                int cur = player.getCurrentPosition();
                int dur = player.getDuration();
                seek.setProgress(dur > 0 ? cur : 0);
                timeText.setText(fmt(cur) + " / " + fmt(dur));
            }
            h.postDelayed(this, 250);
        }
    };

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_preview);
        videoView = findViewById(R.id.pv_video);
        imageView = findViewById(R.id.pv_image);
        audioPanel = findViewById(R.id.pv_audio);
        playBtn = findViewById(R.id.pv_play);
        seek = findViewById(R.id.pv_seek);
        timeText = findViewById(R.id.pv_time);

        String path = getIntent().getStringExtra(EXTRA_PATH);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        String cls = getIntent().getStringExtra(EXTRA_CLASS);
        if (name != null) setTitle(name);

        File f = (path == null) ? null : new File(path);
        if (f == null || !f.exists()) {
            timeText.setText("文件不存在或无法访问");
            return;
        }

        if ("image".equals(cls)) {
            imageView.setVisibility(View.VISIBLE);
            imageView.setImageURI(Uri.fromFile(f));
        } else if ("video".equals(cls)) {
            videoView.setVisibility(View.VISIBLE);
            MediaController mc = new MediaController(this);
            videoView.setMediaController(mc);
            videoView.setVideoPath(f.getAbsolutePath());
            videoView.setOnPreparedListener(mp -> videoView.start());
            videoView.requestFocus();
        } else if ("audio".equals(cls)) {
            audioPanel.setVisibility(View.VISIBLE);
            setupAudio(f.getAbsolutePath());
        } else {
            timeText.setText("该类型暂不支持内置预览");
        }
    }

    private void setupAudio(String path) {
        try {
            player = new MediaPlayer();
            player.setDataSource(path);
            player.setOnPreparedListener(mp -> {
                prepared = true;
                seek.setMax(mp.getDuration());
                timeText.setText("00:00 / " + fmt(mp.getDuration()));
            });
            player.setOnCompletionListener(mp -> {
                playing = false;
                playBtn.setText("▶ 播放");
                seek.setProgress(0);
                h.removeCallbacks(tick);
                timeText.setText("00:00 / " + fmt(mp.getDuration()));
            });
            player.prepareAsync();
        } catch (Exception e) {
            timeText.setText("无法播放：" + (e.getMessage() == null ? "" : e.getMessage()));
            return;
        }
        playBtn.setOnClickListener(v -> {
            if (player == null || !prepared) return;
            if (playing) {
                player.pause();
                playing = false;
                playBtn.setText("▶ 播放");
                h.removeCallbacks(tick);
            } else {
                player.start();
                playing = true;
                playBtn.setText("⏸ 暂停");
                h.postDelayed(tick, 250);
            }
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && player != null && prepared) {
                    player.seekTo(p);
                    timeText.setText(fmt(p) + " / " + fmt(player.getDuration()));
                }
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
    }

    private static String fmt(int ms) {
        int s = ms / 1000;
        int m = s / 60;
        s = s % 60;
        return (m < 10 ? "0" : "") + m + ":" + (s < 10 ? "0" : "") + s;
    }

    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacks(tick);
        if (player != null) {
            try { player.stop(); } catch (Exception ignore) {}
            player.release();
            player = null;
        }
        if (videoView != null) videoView.stopPlayback();
    }
}
