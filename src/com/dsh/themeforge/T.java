package com.dsh.themeforge;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;

/** 软件自身主题色（与生成的主题无关）。 */
public class T {

    public static final int[] PALETTE = {
            0xFF3A6FF7, 0xFF7C4DFF, 0xFF17A673, 0xFFE8720C,
            0xFFE64A6B, 0xFF00A0B0, 0xFF5B6B7C, 0xFFB0306A,
    };
    public static final String[] PALETTE_NAME = {
            "蓝", "紫", "绿", "橙", "红", "青", "石墨", "玫红",
    };

    private static int accent = PALETTE[0];

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("forge", Context.MODE_PRIVATE);
    }

    public static void init(Context c) {
        accent = sp(c).getInt("accent", PALETTE[0]);
    }

    public static int accent() {
        return accent;
    }

    public static void save(Context c, int color) {
        accent = color;
        sp(c).edit().putInt("accent", color).apply();
    }

    public static int dark() {
        return mix(accent, 0xFF101418, 0.42f);
    }

    private static int mix(int a, int b, float f) {
        int r = (int) (Color.red(a) * (1 - f) + Color.red(b) * f);
        int g = (int) (Color.green(a) * (1 - f) + Color.green(b) * f);
        int bl = (int) (Color.blue(a) * (1 - f) + Color.blue(b) * f);
        return Color.argb(255, clamp(r), clamp(g), clamp(bl));
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    public static int withAlpha(int c, int a) {
        return (c & 0x00FFFFFF) | ((a & 0xFF) << 24);
    }

    public static GradientDrawable rr(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    public static GradientDrawable oval(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    /** 把一个 Activity 的顶栏 / 按钮按当前主题色着色。 */
    public static void apply(Activity a, View topBar, Button... accentButtons) {
        if (topBar != null) {
            topBar.setBackground(rr(dark(), 0));
        }
        if (accentButtons != null) {
            for (Button b : accentButtons) {
                if (b != null) b.setTextColor(accent);
            }
        }
        Window w = a.getWindow();
        if (w != null) w.setStatusBarColor(dark());
    }

    public static void accentText(TextView v) {
        if (v != null) v.setTextColor(accent);
    }
}
