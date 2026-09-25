package com.dsh.themeforge;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主题资源槽位数据：来自 assets/slots.txt
 * 每一行描述「MTZ 里的一个文件」以及它对应的界面功能。
 * 另外负责保存「每个槽位自定义的输出分辨率 / 缩放方式」。
 */
public class SlotData {

    /** 输出缩放方式 */
    public static final int MODE_STRETCH = 0;  // 拉伸铺满
    public static final int MODE_COVER = 1;    // 等比裁切铺满
    public static final int MODE_FIT = 2;      // 等比完整放入

    /** 当前设备真实屏幕分辨率（壁纸默认值） */
    public static int screenW = 1440;
    public static int screenH = 3200;

    public static class Slot {
        public String featureId;
        public String module;   // MTZ 顶层模块名（或 wallpaper / preview）
        public String path;     // 模块内路径
        public int w;           // 内置参考尺寸
        public int h;
        public boolean nine;    // 是否为 .9.png 九宫格
        public boolean key;     // 是否关键槽位
        public String label;    // 中文功能说明
        public String group;    // 分组标题

        public int ow, oh;      // 用户自定义输出尺寸（0 = 未设置）
        public int mode = -1;   // 用户自定义缩放方式（-1 = 自动）

        public String id() {
            return module + "|" + path;
        }

        public boolean isWallpaper() {
            return "wallpaper".equals(module);
        }

        /** 内置默认输出尺寸：壁纸用当前设备屏幕分辨率。 */
        public int defW() {
            return (isWallpaper() && screenW > 0) ? screenW : w;
        }

        public int defH() {
            return (isWallpaper() && screenH > 0) ? screenH : h;
        }

        public int outW() {
            return ow > 0 ? ow : defW();
        }

        public int outH() {
            return oh > 0 ? oh : defH();
        }

        public boolean customSize() {
            return ow > 0 && oh > 0;
        }

        /** 实际使用的缩放方式（未自定义时按图的大小自动判断）。 */
        public int outMode() {
            if (mode >= 0) return mode;
            if (nine) return MODE_STRETCH;
            long area = (long) w * h;
            return area >= 40000 ? MODE_COVER : MODE_FIT;
        }

        public String sizeText() {
            int a = outW(), b = outH();
            if (a <= 0 || b <= 0) return "自动";
            return a + "×" + b;
        }
    }

    public static class Feature {
        public String id;
        public String name;
        public String desc;
        public final List<Slot> slots = new ArrayList<>();
        public final Map<String, List<Slot>> groups = new LinkedHashMap<>();
    }

    public static final List<Feature> FEATURES = new ArrayList<>();
    public static final Map<String, Feature> BY_ID = new LinkedHashMap<>();
    public static final Map<String, Slot> BY_KEY = new LinkedHashMap<>();

    private static boolean loaded = false;
    private static SharedPreferences prefs;

    // ---------------- 载入 ----------------

    public static synchronized void load(Context ctx) throws IOException {
        if (loaded) return;
        prefs = ctx.getApplicationContext().getSharedPreferences("forge", Context.MODE_PRIVATE);
        initScreen(ctx);

        BufferedReader br = new BufferedReader(
                new InputStreamReader(ctx.getAssets().open("slots.txt"), "UTF-8"), 1 << 16);
        String line;
        try {
            while ((line = br.readLine()) != null) {
                if (line.length() == 0 || line.charAt(0) == '#') continue;
                String[] f = line.split("\t");
                if (f.length < 4) continue;
                if ("F".equals(f[0])) {
                    Feature cur = new Feature();
                    cur.id = f[1];
                    cur.name = f[2];
                    cur.desc = f.length > 3 ? f[3] : "";
                    FEATURES.add(cur);
                    BY_ID.put(cur.id, cur);
                } else if ("S".equals(f[0]) && f.length >= 9) {
                    Slot s = new Slot();
                    s.featureId = f[1];
                    s.module = f[2];
                    s.path = f[3];
                    s.w = parseInt(f[4]);
                    s.h = parseInt(f[5]);
                    String fl = f[6];
                    s.nine = fl.indexOf('9') >= 0;
                    s.key = fl.indexOf('k') >= 0;
                    s.label = f[7];
                    s.group = f[8];
                    Feature ft = BY_ID.get(s.featureId);
                    if (ft == null) continue;
                    ft.slots.add(s);
                    List<Slot> g = ft.groups.get(s.group);
                    if (g == null) {
                        g = new ArrayList<>();
                        ft.groups.put(s.group, g);
                    }
                    g.add(s);
                    BY_KEY.put(s.id(), s);
                    loadOverride(s);
                }
            }
        } finally {
            br.close();
        }
        for (Feature ft : FEATURES) {
            for (Map.Entry<String, List<Slot>> e : ft.groups.entrySet()) {
                List<Slot> g = e.getValue();
                List<Slot> sorted = new ArrayList<>(g.size());
                for (Slot s : g) if (s.key) sorted.add(s);
                for (Slot s : g) if (!s.key) sorted.add(s);
                e.setValue(sorted);
            }
        }
        loaded = true;
    }

    public static void initScreen(Context ctx) {
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            Display d = wm.getDefaultDisplay();
            Point p = new Point();
            d.getRealSize(p);
            if (p.x > 0 && p.y > 0) {
                screenW = p.x;
                screenH = p.y;
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
        } catch (Throwable ignored) {
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------------- 槽位尺寸覆盖 ----------------

    private static String sizeKey(Slot s) {
        return "sz_" + Integer.toHexString(s.id().hashCode());
    }

    private static String modeKey(Slot s) {
        return "md_" + Integer.toHexString(s.id().hashCode());
    }

    private static void loadOverride(Slot s) {
        if (prefs == null) return;
        String v = prefs.getString(sizeKey(s), null);
        if (v != null) {
            String[] p = v.split(",");
            if (p.length == 2) {
                s.ow = parseInt(p[0]);
                s.oh = parseInt(p[1]);
            }
        }
        s.mode = prefs.getInt(modeKey(s), -1);
    }

    public static void saveSize(Context ctx, Slot s, int w, int h, int mode) {
        s.mode = mode;
        if (w > 0 && h > 0) {
            s.ow = w;
            s.oh = h;
            prefs(ctx).edit()
                    .putString(sizeKey(s), w + "," + h)
                    .putInt(modeKey(s), mode)
                    .apply();
        } else {
            s.ow = 0;
            s.oh = 0;
            prefs(ctx).edit().remove(sizeKey(s)).remove(modeKey(s)).apply();
        }
    }

    public static void saveSize(Context ctx, Slot s, int w, int h) {
        saveSize(ctx, s, w, h, s.mode >= 0 ? s.mode : s.outMode());
    }

    public static void resetSize(Context ctx, Slot s) {
        s.ow = 0;
        s.oh = 0;
        s.mode = -1;
        prefs(ctx).edit().remove(sizeKey(s)).remove(modeKey(s)).apply();
    }

    private static SharedPreferences prefs(Context c) {
        if (prefs == null) {
            prefs = c.getApplicationContext().getSharedPreferences("forge", Context.MODE_PRIVATE);
        }
        return prefs;
    }
}
