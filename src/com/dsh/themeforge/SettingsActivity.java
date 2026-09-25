package com.dsh.themeforge;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;

/** 设置：软件主题色（预设 + 自己调色） + 关于 + 赞助。 */
public class SettingsActivity extends Activity {

    private LinearLayout colorRow;
    private View customPreview;
    private TextView tvHex;
    private SeekBar sR, sG, sB;
    private int cr, cg, cb;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        T.init(this);
        setContentView(R.layout.activity_settings);

        T.apply(this, findViewById(R.id.topBar));
        T.accentText((TextView) findViewById(R.id.tvThanks));

        Button back = findViewById(R.id.btnBack);
        back.setOnClickListener(v -> finish());
        ((Button) findViewById(R.id.btnApplyCustom)).setBackground(T.rr(T.accent(), dp(10)));

        colorRow = findViewById(R.id.colorRow);
        buildPresets();
        buildCustom();

        // 帮助与文档
        ((TextView) findViewById(R.id.tvTableSub)).setText(
                "共 " + countSlots() + " 项：哪张图对应哪个界面，可搜索");
        findViewById(R.id.rowTutorial).setOnClickListener(v ->
                openDoc(DocActivity.MODE_TUTORIAL));
        findViewById(R.id.rowGuide).setOnClickListener(v ->
                openDoc(DocActivity.MODE_GUIDE));
        findViewById(R.id.rowTable).setOnClickListener(v ->
                openDoc(DocActivity.MODE_TABLE));

        ImageView qr1 = findViewById(R.id.ivQr1);
        loadAsset(qr1, "qr_099.jpg");
        qr1.setOnClickListener(v ->
                Toast.makeText(this, "微信扫一扫即可；长按图片可保存到相册", Toast.LENGTH_SHORT).show());

        // 我的 B 站
        ImageView bili = findViewById(R.id.ivBili);
        loadAsset(bili, "bili_profile.jpg");
        ((TextView) findViewById(R.id.tvBiliName)).setText("初次你个亿济");
        ((TextView) findViewById(R.id.tvBiliUid)).setText("UID 651372769");
        Button openBili = findViewById(R.id.btnOpenBili);
        openBili.setBackground(T.rr(T.accent(), dp(10)));
        openBili.setOnClickListener(v -> openBili());

        ((TextView) findViewById(R.id.tvAbout)).setText(
                "主题工坊 v2.0\n\n"
                        + "· 为小米澎湃OS / MIUI 生成 .mtz 主题包\n"
                        + "· 没设置图片的界面不会被改动，继续使用系统自带样式\n"
                        + "· 每个界面可单独设置输出分辨率与缩放方式\n"
                        + "· 桌面 / 锁屏壁纸默认＝当前设备屏幕分辨率\n"
                        + "· 支持裁剪、缩放、九宫格自动生成\n"
                        + "· 主页可搜索 5088 个槽位\n"
                        + "· 每个大类可一键「清空本项」\n"
                        + "· 「范围」＝资源目录（浅色/深色 × 密度），可一次写入同名全部范围\n"
                        + "· 选了基础模板后，各槽位会直接显示模板里的原图\n"
                        + "· 点缩略图可全屏看图（背景变深+虚化，右上角 ✕ 关闭）\n"
                        + "· 基础模板支持 .mtz 和 .7z（含自解压 exe，会自动取出里面的主题）\n"
                        + "· 壁纸尺寸跟随设备当前方向，可一键「横竖对调」，方向不符会提前警告\n"
                        + "· 设置页内置「新手教程」与「MTZ 文件功能对照表」\n"
                        + "· 本页下方可以找到我的 B 站\n\n"
                        + "本工具只生成主题文件、不修改系统；应用主题由系统主题管理器完成。");
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private int countSlots() {
        try {
            SlotData.load(this);
        } catch (Throwable ignored) {
            return 0;
        }
        int n = 0;
        for (SlotData.Feature f : SlotData.FEATURES) n += f.slots.size();
        return n;
    }

    private static final String BILI_URL = "https://space.bilibili.com/651372769";

    private void openBili() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(BILI_URL));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "打开失败，主页地址：" + BILI_URL, Toast.LENGTH_LONG).show();
        }
    }

    private void openDoc(String mode) {
        Intent i = new Intent(this, DocActivity.class);
        i.putExtra(DocActivity.EXTRA_MODE, mode);
        startActivity(i);
    }

    // ---------------- 预设色 ----------------

    private void buildPresets() {
        int cols = 4;
        LinearLayout row = null;
        for (int i = 0; i < T.PALETTE.length; i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = i == 0 ? 0 : (int) dp(12);
                row.setLayoutParams(lp);
                colorRow.addView(row);
            }
            final int color = T.PALETTE[i];
            LinearLayout cell = new LinearLayout(this);
            cell.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);

            View dot = new View(this);
            int size = (int) dp(48);
            dot.setLayoutParams(new LinearLayout.LayoutParams(size, size));
            boolean on = T.accent() == color;
            GradientDrawable g = T.oval(color);
            if (on) g.setStroke((int) dp(3), Color.WHITE);
            dot.setBackground(g);
            dot.setElevation(dp(on ? 6 : 0));
            cell.addView(dot);

            TextView t = new TextView(this);
            t.setText(T.PALETTE_NAME[i]);
            t.setTextSize(11);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(on ? color : 0xFF8A8A8E);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = (int) dp(6);
            t.setLayoutParams(tlp);
            cell.addView(t);

            cell.setOnClickListener(v -> {
                T.save(this, color);
                Toast.makeText(this, "已切换主题色", Toast.LENGTH_SHORT).show();
                recreate();
            });
            row.addView(cell);
        }
    }

    // ---------------- 自己调色 ----------------

    private void buildCustom() {
        int cur = T.accent();
        cr = Color.red(cur);
        cg = Color.green(cur);
        cb = Color.blue(cur);

        customPreview = findViewById(R.id.customPreview);
        tvHex = findViewById(R.id.tvCustomHex);
        sR = findViewById(R.id.seekR);
        sG = findViewById(R.id.seekG);
        sB = findViewById(R.id.seekB);

        sR.setMax(255);
        sG.setMax(255);
        sB.setMax(255);
        sR.setProgress(cr);
        sG.setProgress(cg);
        sB.setProgress(cb);

        tint(sR, 0xFFD9534F);
        tint(sG, 0xFF3FA45B);
        tint(sB, 0xFF4A7BE8);

        SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                cr = sR.getProgress();
                cg = sG.getProgress();
                cb = sB.getProgress();
                updatePreview();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        };
        sR.setOnSeekBarChangeListener(l);
        sG.setOnSeekBarChangeListener(l);
        sB.setOnSeekBarChangeListener(l);
        updatePreview();

        findViewById(R.id.btnApplyCustom).setOnClickListener(v -> {
            int c = Color.rgb(cr, cg, cb);
            T.save(this, c);
            Toast.makeText(this, "已应用自定义颜色 " + hex(c), Toast.LENGTH_SHORT).show();
            recreate();
        });
    }

    private void tint(SeekBar sb, int color) {
        sb.setProgressTintList(ColorStateList.valueOf(color));
        sb.setThumbTintList(ColorStateList.valueOf(color));
    }

    private void updatePreview() {
        int c = Color.rgb(cr, cg, cb);
        GradientDrawable g = T.oval(c);
        g.setStroke((int) dp(2), 0x22000000);
        customPreview.setBackground(g);
        tvHex.setText(hex(c));
    }

    private static String hex(int c) {
        return String.format("#%06X", c & 0xFFFFFF);
    }

    private void loadAsset(ImageView iv, String name) {
        try {
            InputStream in = getAssets().open(name);
            Bitmap bm = BitmapFactory.decodeStream(in);
            in.close();
            iv.setImageBitmap(bm);
        } catch (Exception ignored) {
        }
    }
}
