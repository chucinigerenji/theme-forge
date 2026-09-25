package com.dsh.themeforge;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;

/**
 * 全屏看图：窗口背景透明 —— 图片直接浮在当前屏幕上面，保留透明通道。
 * 点任意位置或用右上角 ✕ 关闭。
 */
public class PreviewActivity extends Activity {

    public static final String EXTRA_SLOT = "slot";

    private final Handler ui = new Handler(Looper.getMainLooper());

    public static void open(Context ctx, String slotId) {
        Intent i = new Intent(ctx, PreviewActivity.class);
        i.putExtra(EXTRA_SLOT, slotId);
        ctx.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        T.init(this);
        setContentView(R.layout.activity_preview);

        // 背景变深 + 虚化（Android 12+ 走系统「背景模糊」，低版本至少保证变深）
        android.view.Window win = getWindow();
        win.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        win.setDimAmount(0.35f);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                android.view.WindowManager.LayoutParams lp = win.getAttributes();
                lp.setBlurBehindRadius((int) (24f * getResources().getDisplayMetrics().density));
                win.setAttributes(lp);
                win.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            } catch (Throwable ignored) {
            }
        }

        final FrameLayout root = findViewById(R.id.pvRoot);
        final ImageView iv = findViewById(R.id.pvImage);
        final TextView load = findViewById(R.id.pvLoading);

        // 点任意处关闭（刚打开 400ms 内的点击忽略，免得「点缩略图」那一下把预览又关掉）
        final long openedAt = android.os.SystemClock.uptimeMillis();
        View.OnClickListener close = v -> {
            if (android.os.SystemClock.uptimeMillis() - openedAt < 400) return;
            finish();
        };
        root.setOnClickListener(close);
        iv.setOnClickListener(close);
        findViewById(R.id.pvClose).setOnClickListener(v -> finish());

        String id = getIntent().getStringExtra(EXTRA_SLOT);
        try {
            SlotData.load(this);
        } catch (Exception e) {
            finish();
            return;
        }
        final SlotData.Slot s = id == null ? null : SlotData.BY_KEY.get(id);
        if (s == null) {
            finish();
            return;
        }

        final Store store = new Store(this);
        final int maxDim = Math.max(Math.max(SlotData.screenW, SlotData.screenH), 2048);

        new Thread(() -> {
            Bitmap bm = null;
            File f = store.imageFor(s);
            try {
                if (f != null) {
                    bm = Img.decodeFile(f, maxDim);
                } else {
                    String p = store.getTemplatePath();
                    File tpl = p == null ? null : new File(p);
                    if (tpl == null || !tpl.isFile()) {
                        tpl = new File(getExternalFilesDir(null), "base-template.mtz");
                    }
                    TemplatePreview tp = new TemplatePreview(tpl);
                    if (tp.valid()) bm = tp.loadFull(this, s, maxDim);
                }
            } catch (Throwable ignored) {
            }
            final Bitmap show = bm;
            ui.post(() -> {
                if (isFinishing()) {
                    if (show != null) show.recycle();
                    return;
                }
                if (show != null) {
                    load.setVisibility(View.GONE);
                    // 小图最多放大 3 倍（再大就糊了），大图按屏幕缩放
                    float d = getResources().getDisplayMetrics().density;
                    int maxW = (int) (SlotData.screenW - 24 * d);
                    int maxH = (int) (SlotData.screenH - 110 * d);
                    iv.setMaxWidth(Math.min(maxW, Math.max(show.getWidth() * 3, show.getWidth())));
                    iv.setMaxHeight(Math.min(maxH, Math.max(show.getHeight() * 3, show.getHeight())));
                    iv.setImageBitmap(show);
                } else {
                    load.setText("这张图读不出来了");
                }
            });
        }).start();
    }
}
