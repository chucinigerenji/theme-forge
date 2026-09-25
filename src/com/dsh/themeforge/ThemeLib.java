package com.dsh.themeforge;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * 主题模板库：把 .mtz（或 .7z / 自解压 exe）主题文件存进 App，可以命名、复用、换着当基础模板。
 *
 * 目录结构：
 *   files/themes/&lt;id&gt;/theme.mtz    主题文件本体
 *   files/themes/&lt;id&gt;/meta.txt     第 1 行名称 / 第 2 行导入时间 / 第 3 行来源文件名
 *
 * id 取文件内容 MD5 的前 16 位，所以同一个主题重复导入不会占两份空间。
 */
public class ThemeLib {

    public static class Item {
        public String id;
        public File dir;
        public File file;
        public String name;
        public long addedAt;
        public String source = "";
        public long size;
        /** add() 时发现库里已有同内容的主题（本次没有新增）。 */
        public boolean duplicate;

        public String sizeText() {
            if (size >= 1048576L) return String.format("%.1f MB", size / 1048576.0);
            if (size >= 1024L) return (size / 1024) + " KB";
            return size + " B";
        }

        public String dateText() {
            try {
                return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(addedAt));
            } catch (Throwable t) {
                return "";
            }
        }
    }

    // ---------------- 目录 ----------------

    public static File root(Context c) {
        File d = new File(c.getFilesDir(), "themes");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static List<Item> list(Context c) {
        List<Item> out = new ArrayList<>();
        File[] ds = root(c).listFiles();
        if (ds != null) {
            for (File d : ds) {
                if (!d.isDirectory()) continue;
                Item it = read(d);
                if (it != null) out.add(it);
            }
        }
        Collections.sort(out, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return Long.compare(b.addedAt, a.addedAt);
            }
        });
        return out;
    }

    public static Item get(Context c, String id) {
        if (id == null || id.length() == 0) return null;
        File d = new File(root(c), id);
        return d.isDirectory() ? read(d) : null;
    }

    /** 按文件内容找库里已有的同款主题。 */
    public static Item findByFile(Context c, File f) {
        if (f == null || !f.isFile()) return null;
        try {
            return get(c, md5(f).substring(0, 16));
        } catch (Exception e) {
            return null;
        }
    }

    private static Item read(File d) {
        File mtz = new File(d, "theme.mtz");
        if (!mtz.isFile() || mtz.length() == 0) return null;
        Item it = new Item();
        it.id = d.getName();
        it.dir = d;
        it.file = mtz;
        it.size = mtz.length();
        it.name = it.id;
        String meta = readText(new File(d, "meta.txt"));
        if (meta != null) {
            String[] lines = meta.split("\n");
            if (lines.length > 0 && lines[0].trim().length() > 0) it.name = lines[0].trim();
            if (lines.length > 1) it.addedAt = parseLong(lines[1].trim());
            if (lines.length > 2) it.source = lines[2].trim();
        }
        if (it.addedAt <= 0) it.addedAt = mtz.lastModified();
        return it;
    }

    private static void writeMeta(Item it) {
        StringBuilder sb = new StringBuilder();
        sb.append(it.name.replace('\n', ' ').replace('\r', ' ')).append('\n');
        sb.append(it.addedAt).append('\n');
        sb.append(it.source == null ? "" : it.source.replace('\n', ' ')).append('\n');
        writeText(new File(it.dir, "meta.txt"), sb.toString());
    }

    // ---------------- 增删改 ----------------

    /** 把 src 收进主题库。内容相同的主题直接复用已有条目。 */
    public static Item add(Context c, File src, String name) throws IOException {
        if (src == null || !src.isFile() || src.length() == 0) {
            throw new IOException("主题文件为空或不存在");
        }
        String id = md5(src).substring(0, 16);
        File dir = new File(root(c), id);
        File dst = new File(dir, "theme.mtz");

        if (dst.isFile() && dst.length() == src.length()) {
            Item old = read(dir);
            if (old != null) {
                old.duplicate = true;
                return old;
            }
        }
        if (dir.exists()) deleteTree(dir);
        dir.mkdirs();
        copy(src, dst);

        Item it = new Item();
        it.id = id;
        it.dir = dir;
        it.file = dst;
        it.size = dst.length();
        it.name = (name == null || name.trim().length() == 0) ? stripExt(src.getName()) : name.trim();
        it.addedAt = System.currentTimeMillis();
        it.source = src.getName();
        writeMeta(it);
        return it;
    }

    /**
     * 导入任意主题文件：是 7z / 自解压 exe 就先取出里面的 .mtz，再收进库。
     * 解包在调用方线程做，耗时操作，请放在后台线程。
     */
    public static Item addAny(Context c, File src, String name, SevenZ.Log log) throws Exception {
        if (SevenZ.is7z(src)) {
            File tmp = File.createTempFile("themelib", ".mtz", c.getCacheDir());
            try {
                SevenZ.extractMtz(src, tmp, log);
                return add(c, tmp, name == null ? stripExt(src.getName()) : name);
            } finally {
                if (tmp.exists()) tmp.delete();
            }
        }
        return add(c, src, name);
    }

    public static Item addAny(Context c, File src, String name) throws Exception {
        return addAny(c, src, name, null);
    }

    public static void rename(Context c, Item it, String name) {
        if (it == null || name == null || name.trim().length() == 0) return;
        it.name = name.trim();
        writeMeta(it);
    }

    /** 记录来源文件名（导入时用选择器里看到的原始名字，而不是临时文件名）。 */
    public static void setSource(Item it, String source) {
        if (it == null || source == null || source.length() == 0) return;
        it.source = source;
        writeMeta(it);
    }

    public static void remove(Item it) {
        if (it == null || it.dir == null) return;
        deleteTree(it.dir);
    }

    // ---------------- 迁移旧的基础模板 ----------------

    /**
     * 老版本把基础模板放在 files/../base-template.mtz。
     * 库里还没有它时把它「搬」进库：同分区直接 rename，不额外占空间，用户原来的模板不会丢。
     */
    public static Item adoptLegacy(Context c, File legacy) {
        if (legacy == null || !legacy.isFile() || legacy.length() == 0) return null;
        try {
            String id = md5(legacy).substring(0, 16);
            File dir = new File(root(c), id);
            File dst = new File(dir, "theme.mtz");
            if (dst.isFile() && dst.length() == legacy.length()) {
                Item old = read(dir);
                if (old != null) {
                    legacy.delete();
                    return old;
                }
            }
            if (dir.exists()) deleteTree(dir);
            dir.mkdirs();
            if (!legacy.renameTo(dst)) {
                copy(legacy, dst);
                legacy.delete();
            }
            Item it = new Item();
            it.id = id;
            it.dir = dir;
            it.file = dst;
            it.size = dst.length();
            it.name = "原基础模板";
            it.addedAt = System.currentTimeMillis();
            it.source = legacy.getName();
            writeMeta(it);
            return it;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- 工具 ----------------

    public static String stripExt(String n) {
        if (n == null) return "主题";
        int i = n.lastIndexOf('.');
        String s = i > 0 ? n.substring(0, i) : n;
        return s.trim().length() == 0 ? "主题" : s.trim();
    }

    public static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] cs = f.listFiles();
            if (cs != null) for (File x : cs) deleteTree(x);
        }
        f.delete();
    }

    private static String readText(File f) {
        if (f == null || !f.isFile()) return null;
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) bos.write(b, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            Img.closeQuietly(in);
        }
    }

    private static void writeText(File f, String s) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(s.getBytes("UTF-8"));
        } catch (Exception ignored) {
        } finally {
            Img.closeQuietly(out);
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static void copy(File from, File to) throws IOException {
        InputStream in = new FileInputStream(from);
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

    public static String md5(File f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            InputStream in = new FileInputStream(f);
            try {
                byte[] b = new byte[1 << 16];
                int n;
                while ((n = in.read(b)) > 0) md.update(b, 0, n);
            } finally {
                Img.closeQuietly(in);
            }
            return hex(md.digest());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    public static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
