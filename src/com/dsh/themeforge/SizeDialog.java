package com.dsh.themeforge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/** 「输出分辨率 / 缩放方式」对话框：可对单个 UI 图片或整组统一设置。 */
public class SizeDialog {

    public interface OnDone {
        void done();
    }

    public static void show(final Activity act, final List<SlotData.Slot> targets,
                            final OnDone onDone) {
        if (targets == null || targets.isEmpty()) return;
        final Context ctx = act;
        SlotData.initScreen(ctx);
        View v = act.getLayoutInflater().inflate(R.layout.dialog_size, null);

        final TextView tvSlot = v.findViewById(R.id.tvSlot);
        final EditText etW = v.findViewById(R.id.etW);
        final EditText etH = v.findViewById(R.id.etH);
        final CheckBox cbRatio = v.findViewById(R.id.cbRatio);
        final TextView chipDef = v.findViewById(R.id.chipDef);
        final TextView chipScreen = v.findViewById(R.id.chipScreen);
        final TextView chipOrig = v.findViewById(R.id.chipOrig);
        final TextView chipSwap = v.findViewById(R.id.chipSwap);
        final RadioGroup rg = v.findViewById(R.id.rgMode);
        final TextView hint = v.findViewById(R.id.tvHint);

        final SlotData.Slot s0 = targets.get(0);
        final boolean single = targets.size() == 1;
        tvSlot.setText(single
                ? (s0.label + "\n" + s0.module + " · " + s0.path)
                : ("将统一设置「" + s0.group + "」下的 " + targets.size() + " 个槽位"));

        final int defW = s0.defW() > 0 ? s0.defW() : 1080;
        final int defH = s0.defH() > 0 ? s0.defH() : 2400;

        // 初始值：已自定义则用它，否则用默认
        int iw = s0.customSize() ? s0.ow : defW;
        int ih = s0.customSize() ? s0.oh : defH;
        etW.setText(String.valueOf(iw));
        etH.setText(String.valueOf(ih));

        final int[] orig = originalSize(ctx, s0);
        final float ratio = (float) iw / Math.max(1, ih);

        cbRatio.setChecked(true);
        rg.check(s0.outMode() == SlotData.MODE_STRETCH ? R.id.rb0
                : s0.outMode() == SlotData.MODE_FIT ? R.id.rb2 : R.id.rb1);

        chipDef.setText("默认 " + defW + "×" + defH);
        chipScreen.setText("屏幕 " + SlotData.screenW + "×" + SlotData.screenH);
        chipOrig.setText(orig[0] > 0 ? ("原图 " + orig[0] + "×" + orig[1]) : "原图 —");
        chipSwap.setText("横竖对调 ↺");
        for (TextView c : new TextView[]{chipDef, chipScreen, chipOrig, chipSwap}) {
            c.setTextColor(T.accent());
            c.setBackground(tintChip(T.accent()));
        }

        final Runnable updHint = () -> hint.setText(buildHint(etW, etH, defW, defH));

        final boolean[] lock = {true};
        cbRatio.setOnCheckedChangeListener((b, checked) -> lock[0] = checked);

        etW.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                if (!lock[0] || editing) return;
                int w = parse(etW.getText().toString());
                if (w <= 0) return;
                editing = true;
                etH.setText(String.valueOf(Math.max(1, Math.round(w / ratio))));
                editing = false;
                updHint.run();
            }
        });
        etH.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                if (!lock[0] || editing) return;
                int h = parse(etH.getText().toString());
                if (h <= 0) return;
                editing = true;
                etW.setText(String.valueOf(Math.max(1, Math.round(h * ratio))));
                editing = false;
                updHint.run();
            }
        });

        chipDef.setOnClickListener(x -> {
            editing = true;
            etW.setText(String.valueOf(defW));
            etH.setText(String.valueOf(defH));
            editing = false;
            updHint.run();
        });
        chipScreen.setOnClickListener(x -> {
            editing = true;
            etW.setText(String.valueOf(SlotData.screenW));
            etH.setText(String.valueOf(SlotData.screenH));
            editing = false;
            updHint.run();
        });
        chipOrig.setOnClickListener(x -> {
            if (orig[0] <= 0) return;
            editing = true;
            etW.setText(String.valueOf(orig[0]));
            etH.setText(String.valueOf(orig[1]));
            editing = false;
            updHint.run();
        });
        chipSwap.setOnClickListener(x -> {
            int a = parse(etW.getText().toString());
            int b = parse(etH.getText().toString());
            editing = true;
            etW.setText(String.valueOf(b));
            etH.setText(String.valueOf(a));
            editing = false;
            updHint.run();
        });

        updHint.run();

        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("输出分辨率 / 缩放方式")
                .setView(v)
                .setPositiveButton("确定", null)
                .setNegativeButton("取消", null)
                .setNeutralButton("恢复默认", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(T.accent());
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(T.accent());
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(0xFF888888);
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
            int w = parse(etW.getText().toString());
            int h = parse(etH.getText().toString());
            if (w <= 0 || h <= 0) {
                hint.setText("宽和高都要填大于 0 的整数");
                return;
            }
            if (w > 8192 || h > 8192) {
                hint.setText("单边最大 8192，再大手机扛不住");
                return;
            }
            int mode = rg.getCheckedRadioButtonId() == R.id.rb0 ? SlotData.MODE_STRETCH
                    : rg.getCheckedRadioButtonId() == R.id.rb2 ? SlotData.MODE_FIT
                    : SlotData.MODE_COVER;
            for (SlotData.Slot s : targets) SlotData.saveSize(ctx, s, w, h, mode);
            dlg.dismiss();
            if (onDone != null) onDone.done();
        });
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(x -> {
            for (SlotData.Slot s : targets) SlotData.resetSize(ctx, s);
            dlg.dismiss();
            if (onDone != null) onDone.done();
        });
    }

    private static boolean editing;

    /** 提示文案：尺寸会不会被系统再裁一刀（方向不对时特别明显）。 */
    private static String buildHint(EditText etW, EditText etH, int defW, int defH) {
        int w = parse(etW.getText().toString());
        int h = parse(etH.getText().toString());
        StringBuilder sb = new StringBuilder();
        sb.append("这张图会先按上面的尺寸缩放，再写进主题包。\n")
                .append("未设置时用「默认」；桌面/锁屏壁纸的默认＝当前设备屏幕分辨率（")
                .append(SlotData.screenW).append("×").append(SlotData.screenH).append("）。");
        if (w > 0 && h > 0 && SlotData.screenW > 0 && SlotData.screenH > 0) {
            double target = (double) w / h;
            double screen = (double) SlotData.screenW / SlotData.screenH;
            double bad = Math.abs(target - screen) / screen;
            if (bad > 0.18) {
                boolean rotated = (w > h) != (SlotData.screenW > SlotData.screenH);
                sb.append("\n\n⚠ 这个比例和你现在的屏幕差得多")
                        .append(rotated ? "，而且方向是反的" : "")
                        .append("：装上去系统会居中裁掉一部分"
                                + (target > screen ? "左右" : "上下") + "。");
                sb.append("\n当前屏幕是 ").append(SlotData.screenW).append("×").append(SlotData.screenH)
                        .append(SlotData.screenW > SlotData.screenH ? "（横屏）" : "（竖屏）")
                        .append("，点「横竖对调 ↺」可以一键换成另一个方向。");
                sb.append("\n平板 / 折叠屏横竖屏共用同一张壁纸，建议按你常用的方向设置。");
            }
        }
        return sb.toString();
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static android.graphics.drawable.GradientDrawable tintChip(int accent) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        d.setColor(T.withAlpha(accent, 0x1F));
        d.setCornerRadius(999);
        d.setStroke(2, T.withAlpha(accent, 0x55));
        return d;
    }

    /** 该槽位当前图片的原始像素尺寸；没有图片时返回 {0,0}。 */
    public static int[] originalSize(Context ctx, SlotData.Slot s) {
        try {
            File f = new Store(ctx).imageFor(s);
            if (f == null) return new int[]{0, 0};
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            return new int[]{o.outWidth, o.outHeight};
        } catch (Throwable t) {
            return new int[]{0, 0};
        }
    }
}
