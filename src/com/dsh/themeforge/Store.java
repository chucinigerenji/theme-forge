package com.dsh.themeforge;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户已选图片的本地存储。
 *
 * 采用「内容寻址」：图片按 MD5 存成 imgs/<md5>.png，槽位只存一个指针文件。
 * 好处：同一个槽位随时单独更换；「整组套用一张图」不会重复占用磁盘。
 */
public class Store {

    private final Context ctx;
    private final SharedPreferences sp;

    public Store(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.sp = this.ctx.getSharedPreferences("forge", Context.MODE_PRIVATE);
    }

    private File markers() {
        File d = new File(ctx.getFilesDir(), "slots");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private File imgs() {
        File d = new File(ctx.getFilesDir(), "imgs");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String safe(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 100; i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    public File marker(SlotData.Slot s) {
        String name = safe(s.path.substring(s.path.lastIndexOf('/') + 1));
        return new File(markers(), Integer.toHexString(s.id().hashCode()) + "_" + name + ".ref");
    }

    /**
     * 「哪些槽位已经有图」的缓存。
     *
     * 注意：必须是**进程级静态**。主界面、裁剪页、主题库取素材页各自 new 了
     * 一个 Store，但它们读写的是同一批 marker 文件；如果缓存是每个实例一份，
     * 「从主题库取素材」在另一个 Activity 里写完图，主界面回来读自己的旧缓存
     * 就会以为这个槽位没图 —— 表现成「点了使用但界面毫无变化」。
     */
    private static final java.util.HashSet<String> FILLED = new java.util.HashSet<>();
    private static boolean cacheReady = false;

    private void ensureCache() {
        synchronized (FILLED) {
            if (cacheReady) return;
            File[] fs = markers().listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (!f.getName().endsWith(".ref")) continue;
                    String h = readLine(f);
                    if (h != null && new File(imgs(), h + ".png").isFile()) {
                        FILLED.add(f.getName());
                    }
                }
            }
            cacheReady = true;
        }
    }

    private static void markFilled(String name, boolean on) {
        synchronized (FILLED) {
            if (on) FILLED.add(name);
            else FILLED.remove(name);
        }
    }

    /** 返回该槽位当前使用的图片文件，未设置时返回 null。 */
    public File imageFor(SlotData.Slot s) {
        ensureCache();
        File mk = marker(s);
        synchronized (FILLED) {
            if (!FILLED.contains(mk.getName())) return null;
        }
        String h = readLine(mk);
        if (h == null || h.length() == 0) return null;
        File img = new File(imgs(), h + ".png");
        return img.isFile() ? img : null;
    }

    public boolean has(SlotData.Slot s) {
        ensureCache();
        synchronized (FILLED) {
            return FILLED.contains(marker(s).getName());
        }
    }

    /** 写入一张图片到该槽位。 */
    public void put(SlotData.Slot s, Bitmap bm) throws IOException {
        File tmp = File.createTempFile("img", ".png", ctx.getCacheDir());
        try {
            Img.savePng(bm, tmp);
            String h = md5(tmp);
            File dst = new File(imgs(), h + ".png");
            if (!dst.isFile()) {
                if (!tmp.renameTo(dst)) {
                    copyFile(tmp, dst);
                }
            }
            File mk = marker(s);
            writeLine(mk, h);
            ensureCache();
            markFilled(mk.getName(), true);
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }

    /** 把一张图片直接按原始字节写进该槽位（内容寻址，不重编码）。 */
    public void putBytes(SlotData.Slot s, byte[] data) throws IOException {
        if (data == null || data.length == 0) throw new IOException("图片内容为空");
        String h = md5(data);
        File dst = new File(imgs(), h + ".png");
        if (!dst.isFile()) {
            File tmp = File.createTempFile("img", ".bin", ctx.getCacheDir());
            try {
                FileOutputStream fo = new FileOutputStream(tmp);
                try {
                    fo.write(data);
                } finally {
                    Img.closeQuietly(fo);
                }
                if (!tmp.renameTo(dst)) {
                    copyFile(tmp, dst);
                }
            } finally {
                if (tmp.exists()) tmp.delete();
            }
        }
        File mk = marker(s);
        writeLine(mk, h);
        ensureCache();
        markFilled(mk.getName(), true);
    }

    public void clear(SlotData.Slot s) {
        File mk = marker(s);
        if (mk.exists()) mk.delete();
        ensureCache();
        markFilled(mk.getName(), false);
    }

    public void clear(List<SlotData.Slot> list) {
        for (SlotData.Slot s : list) clear(s);
    }

    public int countFilled(List<SlotData.Slot> list) {
        int n = 0;
        for (SlotData.Slot s : list) if (has(s)) n++;
        return n;
    }

    public int countAll() {
        int n = 0;
        for (SlotData.Feature f : SlotData.FEATURES) n += countFilled(f.slots);
        return n;
    }

    public List<SlotData.Slot> filledAll() {
        List<SlotData.Slot> out = new ArrayList<>();
        for (SlotData.Feature f : SlotData.FEATURES) {
            for (SlotData.Slot s : f.slots) if (has(s)) out.add(s);
        }
        return out;
    }

    // ---------- 工具 ----------

    private static String readLine(File f) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(f));
            return br.readLine();
        } catch (Exception e) {
            return null;
        } finally {
            Img.closeQuietly(br);
        }
    }

    private static void writeLine(File f, String s) throws IOException {
        FileWriter w = new FileWriter(f, false);
        try {
            w.write(s);
        } finally {
            Img.closeQuietly(w);
        }
    }

    private static void copyFile(File from, File to) throws IOException {
        java.io.InputStream in = new java.io.FileInputStream(from);
        FileOutputStream out = new FileOutputStream(to);
        try {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        } finally {
            Img.closeQuietly(in);
            Img.closeQuietly(out);
        }
    }

    private static String md5(byte[] data) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(data);
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private static String md5(File f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            java.io.InputStream in = new java.io.FileInputStream(f);
            try {
                byte[] b = new byte[1 << 16];
                int n;
                while ((n = in.read(b)) > 0) md.update(b, 0, n);
            } finally {
                Img.closeQuietly(in);
            }
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    // ---------- 模板与元信息 ----------

    public String getTemplatePath() {
        return sp.getString("template", null);
    }

    public void setTemplatePath(String p) {
        sp.edit().putString("template", p).apply();
    }

    public String getLastOut() {
        return sp.getString("lastOut", null);
    }

    public void setLastOut(String p) {
        sp.edit().putString("lastOut", p).apply();
    }

    public String getTitle() {
        return sp.getString("title", "我的主题");
    }

    public String getAuthor() {
        return sp.getString("author", "");
    }

    public void saveMeta(String title, String author) {
        sp.edit().putString("title", title).putString("author", author).apply();
    }
}
