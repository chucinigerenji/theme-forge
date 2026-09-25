package com.dsh.themeforge;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/** 裁剪 + 输出分辨率设置。 */
public class EditorActivity extends Activity {

    public static final String EXTRA_SLOT = "slot";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private CropView cropView;
    private EditText etW, etH;
    private CheckBox cbRatio;
    private TextView tvHint;
    private TextView chipDef, chipScreen, chipOrig, chipSwap;
    private SlotData.Slot slot;
    private Store store;
    private Bitmap bitmap;
    private int[] origCrop = new int[]{0, 0};
    private boolean lock = true;
    private boolean editing;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        T.init(this);
        setContentView(R.layout.activity_editor);
        store = new Store(this);
        T.apply(this, findViewById(R.id.topBar), (Button) findViewById(R.id.btnReset),
                (Button) findViewById(R.id.btnCancel));
        ((Button) findViewById(R.id.btnApply)).setBackground(T.rr(T.accent(), dp(10)));

        try {
            SlotData.load(this);
        } catch (Exception e) {
            finish();
            return;
        }
        slot = SlotData.BY_KEY.get(getIntent().getStringExtra(EXTRA_SLOT));
        if (slot == null) {
            finish();
            return;
        }

        cropView = findViewById(R.id.cropView);
        etW = findViewById(R.id.etW);
        etH = findViewById(R.id.etH);
        cbRatio = findViewById(R.id.cbRatio);
        tvHint = findViewById(R.id.tvHint);
        chipDef = findViewById(R.id.chipDef);
        chipScreen = findViewById(R.id.chipScreen);
        chipOrig = findViewById(R.id.chipOrig);
        chipSwap = findViewById(R.id.chipSwap);
        chipSwap.setText("横竖对调 ↺");
        chipSwap.setOnClickListener(v -> swapSize());
        for (TextView c : new TextView[]{chipDef, chipScreen, chipOrig, chipSwap}) {
            c.setTextColor(T.accent());
        }

        ((TextView) findViewById(R.id.tvSlot)).setText(slot.label + "   ·   " + slot.path);

        File f = store.imageFor(slot);
        if (f == null) {
            Toast.makeText(this, "该槽位还没有图片，先点「选图」", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        bitmap = Img.decodeFile(f, 4096);
        if (bitmap == null) {
            Toast.makeText(this, "图片读取失败", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        cropView.setBitmap(bitmap);

        final int defW = slot.defW() > 0 ? slot.defW() : bitmap.getWidth();
        final int defH = slot.defH() > 0 ? slot.defH() : bitmap.getHeight();
        int iw = slot.customSize() ? slot.ow : defW;
        int ih = slot.customSize() ? slot.oh : defH;
        etW.setText(String.valueOf(iw));
        etH.setText(String.valueOf(ih));
        final float ratio = (float) iw / Math.max(1, ih);

        chipDef.setText("默认 " + defW + "×" + defH);
        chipScreen.setText("屏幕 " + SlotData.screenW + "×" + SlotData.screenH);
        chipOrig.setText("裁剪原尺寸 " + bitmap.getWidth() + "×" + bitmap.getHeight());

        cbRatio.setChecked(true);
        cbRatio.setOnCheckedChangeListener((v, checked) -> lock = checked);

        TextWatcher w = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                if (!lock || editing) return;
                int x = num(etW.getText().toString());
                if (x <= 0) return;
                editing = true;
                etH.setText(String.valueOf(Math.max(1, Math.round(x / ratio))));
                editing = false;
            }
        };
        TextWatcher h = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                if (!lock || editing) return;
                int y = num(etH.getText().toString());
                if (y <= 0) return;
                editing = true;
                etW.setText(String.valueOf(Math.max(1, Math.round(y * ratio))));
                editing = false;
            }
        };
        etW.addTextChangedListener(w);
        etH.addTextChangedListener(h);

        chipDef.setOnClickListener(v -> setSize(defW, defH));
        chipScreen.setOnClickListener(v -> setSize(SlotData.screenW, SlotData.screenH));
        chipOrig.setOnClickListener(v -> setSize(bitmap.getWidth(), bitmap.getHeight()));

        tvHint.setText("默认尺寸＝内置参考值；桌面/锁屏壁纸的默认＝当前设备屏幕分辨率（"
                + SlotData.screenW + "×" + SlotData.screenH
                + (SlotData.screenW > SlotData.screenH ? "，横屏" : "，竖屏") + "）。\n"
                + "平板 / 折叠屏横竖屏共用同一张壁纸，方向不对会被系统裁掉一部分，可用「横竖对调 ↺」。");

        findViewById(R.id.btnReset).setOnClickListener(v -> cropView.resetCrop());
        findViewById(R.id.btnCancel).setOnClickListener(v -> finish());
        findViewById(R.id.btnApply).setOnClickListener(v -> apply());
    }

    private void swapSize() {
        int w = num(etW.getText().toString());
        int h = num(etH.getText().toString());
        editing = true;
        etW.setText(String.valueOf(h));
        etH.setText(String.valueOf(w));
        editing = false;
    }

    private void setSize(int w, int h) {
        editing = true;
        etW.setText(String.valueOf(w));
        etH.setText(String.valueOf(h));
        editing = false;
    }

    private int num(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private void apply() {
        final Rect r = cropView.getCropRect();
        if (r == null) {
            finish();
            return;
        }
        final int w = num(etW.getText().toString());
        final int h = num(etH.getText().toString());
        if (w <= 0 || h <= 0) {
            tvHint.setText("宽和高都要填大于 0 的整数");
            return;
        }
        if (w > 8192 || h > 8192) {
            tvHint.setText("单边最大 8192");
            return;
        }
        final int mode = slot.outMode();
        final Bitmap src = bitmap;
        etW.setEnabled(false);
        etH.setEnabled(false);
        ((Button) findViewById(R.id.btnApply)).setEnabled(false);
        tvHint.setText("正在生成 " + w + "×" + h + " …");
        new Thread(() -> {
            try {
                Bitmap cut = Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height());
                Bitmap out;
                if (mode == SlotData.MODE_STRETCH) {
                    out = Img.stretch(cut, w, h);
                } else if (mode == SlotData.MODE_FIT) {
                    out = Img.fit(cut, w, h);
                } else {
                    out = Img.cover(cut, w, h);
                }
                if (out != cut) cut.recycle();
                store.put(slot, out);
                SlotData.saveSize(EditorActivity.this, slot, w, h, mode);
                out.recycle();
                ui.post(() -> {
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (final Throwable t) {
                ui.post(() -> {
                    etW.setEnabled(true);
                    etH.setEnabled(true);
                    ((Button) findViewById(R.id.btnApply)).setEnabled(true);
                    tvHint.setText("保存失败：" + t);
                });
            }
        }).start();
    }
}
