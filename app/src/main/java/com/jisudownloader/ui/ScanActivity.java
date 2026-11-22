package com.jisudownloader.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import com.jisudownloader.R;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ScanActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {

    public static final String EXTRA_TEXT = "text";

    private SurfaceHolder holder;
    private Camera camera;
    private MultiFormatReader reader;
    private boolean surfaceReady = false;
    private int previewW = 0;
    private int previewH = 0;
    private volatile boolean decoding = false;
    private volatile boolean done = false;
    private volatile boolean finishing = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_scan);
        SurfaceView surface = findViewById(R.id.surface);
        holder = surface.getHolder();
        holder.addCallback(this);

        Map<DecodeHintType, Object> hints = new HashMap<>();
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        reader = new MultiFormatReader();
        reader.setHints(hints);

        findViewById(R.id.btnCancel).setOnClickListener(v -> finish());
        if (!granted()) requestPermissions(new String[]{Manifest.permission.CAMERA}, 31);
    }

    private boolean granted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (req == 31) {
            if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) {
                tryOpen();
            } else {
                Toast.makeText(this, "需要相机权限才能扫码", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        surfaceReady = true;
        tryOpen();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        surfaceReady = false;
        release();
    }

    @Override
    protected void onResume() {
        super.onResume();
        tryOpen();
    }

    @Override
    protected void onPause() {
        super.onPause();
        release();
    }

    @Override
    protected void onDestroy() {
        release();
        super.onDestroy();
    }

    private void tryOpen() {
        if (!surfaceReady || camera != null || !granted() || isFinishing() || done) return;
        try {
            camera = Camera.open();
            Camera.Parameters p = camera.getParameters();
            Camera.Size size = pick(p.getSupportedPreviewSizes());
            if (size != null) {
                p.setPreviewSize(size.width, size.height);
                previewW = size.width;
                previewH = size.height;
            }
            try {
                List<String> modes = p.getSupportedFocusModes();
                if (modes != null && modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                }
            } catch (Exception ignored) {
            }
            camera.setParameters(p);
            camera.setDisplayOrientation(90);
            camera.setPreviewDisplay(holder);
            camera.setPreviewCallback(this);
            camera.startPreview();
        } catch (Exception e) {
            release();
            Toast.makeText(this, "相机打开失败，可改用复制地址加入", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private Camera.Size pick(List<Camera.Size> list) {
        if (list == null || list.isEmpty()) return null;
        Camera.Size best = null;
        for (Camera.Size s : list) {
            long px = (long) s.width * s.height;
            if (px >= 640L * 480 && px <= 1920L * 1080) {
                if (best == null || px > (long) best.width * best.height) best = s;
            }
        }
        return best == null ? list.get(0) : best;
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera cam) {
        if (decoding || done || finishing || previewW <= 0 || previewH <= 0) return;
        decoding = true;
        final byte[] copy = data.clone();
        new Thread(() -> {
            try {
                PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(
                        copy, previewW, previewH, 0, 0, previewW, previewH, false);
                Result r;
                synchronized (reader) {
                    reader.reset();
                    r = reader.decode(new BinaryBitmap(new HybridBinarizer(src)));
                }
                if (r != null && !done) {
                    done = true;
                    final String text = r.getText();
                    runOnUiThread(() -> finishWith(text));
                }
            } catch (Exception ignored) {
            } finally {
                decoding = false;
            }
        }, "qr-decode").start();
    }

    private void finishWith(String text) {
        if (finishing) return;
        finishing = true;
        Intent i = new Intent();
        i.putExtra(EXTRA_TEXT, text);
        setResult(RESULT_OK, i);
        finish();
    }

    private void release() {
        if (camera != null) {
            try {
                camera.setPreviewCallback(null);
                camera.stopPreview();
            } catch (Exception ignored) {
            }
            try {
                camera.release();
            } catch (Exception ignored) {
            }
            camera = null;
        }
    }
}
