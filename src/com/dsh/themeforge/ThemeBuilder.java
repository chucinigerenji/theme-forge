package com.dsh.themeforge;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 生成 MIUI / 澎湃OS 主题包 (.mtz)。
 *
 * 两种模式：
 *  1) 无基础模板 —— 只打包用户上传的图片，生成「局部主题」；
 *  2) 有基础模板 —— 在模板上逐项替换用户上传的图片，其余内容原样保留。
 */
public class ThemeBuilder {

    public interface Log {
        void log(String msg);
    }

    public static class Fill {
        public SlotData.Slot slot;
        public File src;

        public Fill(SlotData.Slot slot, File src) {
            this.slot = slot;
            this.src = src;
        }
    }

    public static class Result {
        public File out;
        public int replaced;
        public int modules;
        public final List<String> warnings = new ArrayList<>();
        public long bytes;
    }

    private static final String AOD_DESC =
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                    + "<MIUI_Theme_Values>\n"
                    + "\t<theme_type>maml_style</theme_type>\n"
                    + "\t<support_lunar_calendar>0</support_lunar_calendar>\n"
                    + "\t<support_battery>1</support_battery>\n"
                    + "\t<support_notification>1</support_notification>\n"
                    + "\t<lunar_calendar_enable>0</lunar_calendar_enable>\n"
                    + "\t<battery_enable>1</battery_enable>\n"
                    + "\t<notification_enable>1</notification_enable>\n"
                    + "</MIUI_Theme_Values>\n";

    public static Result build(Context ctx, File out, List<Fill> fills, File base,
                               String title, String author, String note, Log log) throws Exception {
        Result res = new Result();
        res.out = out;
        // 导出这一刻重新读一次屏幕：壁纸的「默认」尺寸始终跟着当前方向
        SlotData.initScreen(ctx);

        LinkedHashMap<String, Fill> roots = new LinkedHashMap<>();
        LinkedHashMap<String, LinkedHashMap<String, Fill>> mods = new LinkedHashMap<>();
        for (Fill f : fills) {
            String m = f.slot.module;
            if ("wallpaper".equals(m) || "preview".equals(m)) {
                roots.put(f.slot.path, f);
            } else {
                LinkedHashMap<String, Fill> mm = mods.get(m);
                if (mm == null) {
                    mm = new LinkedHashMap<>();
                    mods.put(m, mm);
                }
                mm.put(f.slot.path, f);
            }
        }
        res.replaced = fills.size();

        // 壁纸换了以后，自动同步主题预览图（用户没单独指定时）
        autoPreview(roots, "wallpaper/default_wallpaper.jpg", "preview/preview_wallpaper_0.jpg");
        autoPreview(roots, "wallpaper/default_lock_wallpaper.jpg", "preview/preview_lockscreen_0.jpg");

        if (mods.containsKey("lockscreen") && base == null) {
            res.warnings.add("锁屏样式模块需要基础模板才能生效（已写入图片，但可能不被应用）");
        }
        if (mods.containsKey("aod") && base == null) {
            res.warnings.add("息屏显示已附带 aod_description.xml，可直接使用");
        }

        File tmp = new File(out.getAbsolutePath() + ".part");
        if (tmp.exists()) tmp.delete();
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16));
        try {
            zos.setLevel(2);
            boolean descWritten = false;
            if (base == null || !base.isFile()) {
                writeText(zos, "description.xml", description(title, author, note));
                descWritten = true;
                for (String name : roots.keySet()) {
                    zos.putNextEntry(new ZipEntry(name));
                    encode(roots.get(name), zos);
                    zos.closeEntry();
                }
                for (String module : mods.keySet()) {
                    res.modules++;
                    writeModule(ctx, zos, module, mods.get(module), null, null, log);
                }
                if (roots.size() > 0) log.log("已写入 " + roots.size() + " 个根目录文件");
            } else {
                log.log("读取基础模板：" + base.getName());
                ZipFile zf = new ZipFile(base);
                Set<String> doneRoots = new LinkedHashSet<>();
                Set<String> doneMods = new LinkedHashSet<>();
                try {
                    Enumeration<? extends ZipEntry> en = zf.entries();
                    while (en.hasMoreElements()) {
                        ZipEntry e = en.nextElement();
                        String name = e.getName();
                        if ("description.xml".equals(name)) {
                            if (!descWritten) {
                                writeText(zos, "description.xml", description(title, author, note));
                                descWritten = true;
                            }
                            continue;
                        }
                        if (e.isDirectory()) {
                            zos.putNextEntry(new ZipEntry(name));
                            zos.closeEntry();
                            continue;
                        }
                        Fill rf = roots.get(name);
                        if (rf != null) {
                            zos.putNextEntry(new ZipEntry(name));
                            encode(rf, zos);
                            zos.closeEntry();
                            doneRoots.add(name);
                            continue;
                        }
                        if (mods.containsKey(name)) {
                            res.modules++;
                            writeModule(ctx, zos, name, mods.get(name), zf, e, log);
                            doneMods.add(name);
                            continue;
                        }
                        zos.putNextEntry(new ZipEntry(name));
                        copy(zf.getInputStream(e), zos);
                        zos.closeEntry();
                    }
                } finally {
                    zf.close();
                }
                if (!descWritten) writeText(zos, "description.xml", description(title, author, note));
                for (String name : roots.keySet()) {
                    if (doneRoots.contains(name)) continue;
                    zos.putNextEntry(new ZipEntry(name));
                    encode(roots.get(name), zos);
                    zos.closeEntry();
                }
                for (String module : mods.keySet()) {
                    if (doneMods.contains(module)) continue;
                    res.modules++;
                    writeModule(ctx, zos, module, mods.get(module), null, null, log);
                }
            }
        } finally {
            try {
                zos.close();
            } catch (Exception ignored) {
            }
        }
        if (out.exists()) out.delete();
        if (!tmp.renameTo(out)) {
            copy(new java.io.FileInputStream(tmp), new FileOutputStream(out));
            tmp.delete();
        }
        res.bytes = out.length();
        return res;
    }

    /** 若目标预览图未单独指定，则从来源图生成。 */
    private static void autoPreview(LinkedHashMap<String, Fill> roots, String srcPath, String previewPath) {
        if (roots.containsKey(previewPath)) return;
        Fill src = roots.get(srcPath);
        if (src == null) return;
        SlotData.Slot s = new SlotData.Slot();
        s.featureId = "preview";
        s.module = "preview";
        s.path = previewPath;
        s.w = src.slot.outW();
        s.h = src.slot.outH();
        s.nine = false;
        s.key = true;
        s.label = previewPath;
        s.group = "预览图";
        roots.put(previewPath, new Fill(s, src.src));
    }

    private static void writeModule(Context ctx, ZipOutputStream outer, String module,
                                    LinkedHashMap<String, Fill> repl, ZipFile baseZip, ZipEntry baseEntry,
                                    Log log) throws Exception {
        log.log("· 打包模块 " + module + "（替换 " + repl.size() + " 项）");
        File tmp = File.createTempFile("mod", ".zip", ctx.getCacheDir());
        Set<String> left = new LinkedHashSet<>(repl.keySet());
        boolean sawAodDesc = false;
        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16));
        try {
            zos.setLevel(2);
            if (baseZip != null && baseEntry != null) {
                long size = baseEntry.getSize();
                InputStream raw = baseZip.getInputStream(baseEntry);
                ZipInputStream zin = new ZipInputStream(
                        new BoundedStream(raw, size >= 0 ? size : Long.MAX_VALUE));
                ZipEntry ne;
                byte[] buf = new byte[1 << 16];
                while ((ne = zin.getNextEntry()) != null) {
                    String nm = ne.getName();
                    if (nm.endsWith("/")) {
                        zos.putNextEntry(new ZipEntry(nm));
                        zos.closeEntry();
                        continue;
                    }
                    if ("aod_description.xml".equals(nm)) sawAodDesc = true;
                    Fill f = repl.get(nm);
                    zos.putNextEntry(new ZipEntry(nm));
                    if (f != null) {
                        encode(f, zos);
                        left.remove(nm);
                    } else {
                        int n;
                        while ((n = zin.read(buf)) > 0) zos.write(buf, 0, n);
                    }
                    zos.closeEntry();
                    zin.closeEntry();
                }
                zin.close();
            }
            if ("aod".equals(module) && !sawAodDesc) {
                zos.putNextEntry(new ZipEntry("aod_description.xml"));
                zos.write(AOD_DESC.getBytes("UTF-8"));
                zos.closeEntry();
            }
            for (String nm : left) {
                zos.putNextEntry(new ZipEntry(nm));
                encode(repl.get(nm), zos);
                zos.closeEntry();
            }
        } finally {
            try {
                zos.close();
            } catch (Exception ignored) {
            }
        }
        outer.putNextEntry(new ZipEntry(module));
        copy(new java.io.FileInputStream(tmp), outer);
        outer.closeEntry();
        tmp.delete();
    }

    private static void encode(Fill f, OutputStream os) throws IOException {
        SlotData.Slot s = f.slot;
        final int tw = s.outW();
        final int th = s.outH();
        int maxDim = 2400;
        if (tw > 0 && th > 0) {
            maxDim = Math.min(4096, Math.max(1024, Math.max(tw, th) * 2));
        }
        Bitmap src = Img.decodeFile(f.src, maxDim);
        if (src == null) throw new IOException("图片解码失败：" + f.src.getName());
        Bitmap out = src;
        try {
            if (s.nine) {
                Bitmap t = (tw > 0 && th > 0)
                        ? (s.outMode() == SlotData.MODE_STRETCH ? Img.stretch(src, tw, th) : Img.cover(src, tw, th))
                        : src;
                out = Img.ninePatch(t);
                if (t != src) t.recycle();
            } else if (tw > 0 && th > 0 && (src.getWidth() != tw || src.getHeight() != th)) {
                switch (s.outMode()) {
                    case SlotData.MODE_STRETCH:
                        out = Img.stretch(src, tw, th);
                        break;
                    case SlotData.MODE_FIT:
                        out = Img.fit(src, tw, th);
                        break;
                    default:
                        out = Img.cover(src, tw, th);
                        break;
                }
            }
            Img.write(out, os, s.path);
        } finally {
            if (out != src) out.recycle();
            src.recycle();
        }
    }

    private static void writeText(ZipOutputStream zos, String name, String text) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(text.getBytes("UTF-8"));
        zos.closeEntry();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        try {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            Img.closeQuietly(in);
        }
    }

    public static String description(String title, String author, String note) {
        String t = esc(title == null || title.length() == 0 ? "我的主题" : title);
        String a = esc(author == null ? "" : author);
        String d = esc(note == null || note.length() == 0
                ? "由「主题工坊」生成，适用于小米澎湃OS / MIUI 主题管理器。"
                : note);
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n<theme>\n");
        sb.append("<version><![CDATA[1]]></version>\n");
        sb.append("<uiVersion>17</uiVersion>\n");
        sb.append("<author><![CDATA[").append(a).append("]]></author>\n");
        sb.append("<designer><![CDATA[").append(a).append("]]></designer>\n");
        sb.append("<title><![CDATA[").append(t).append("]]></title>\n");
        sb.append("<description><![CDATA[").append(d).append("]]></description>\n");
        sb.append("<authors><author locale=\"zh_CN\"><![CDATA[").append(a).append("]]></author></authors>\n");
        sb.append("<designers><designer locale=\"zh_CN\"><![CDATA[").append(a).append("]]></designer></designers>\n");
        sb.append("<titles><title locale=\"zh_CN\"><![CDATA[").append(t).append("]]></title></titles>\n");
        sb.append("<descriptions><description locale=\"zh_CN\"><![CDATA[").append(d)
                .append("]]></description></descriptions>\n");
        sb.append("<miuiAdapterVersion>4.0</miuiAdapterVersion>\n");
        sb.append("</theme>\n");
        return sb.toString();
    }

    private static String esc(String s) {
        return s.replace("]]>", "]]&gt;");
    }

    /** 限定长度的输入流，避免 ZipInputStream 读过头。 */
    private static class BoundedStream extends FilterInputStream {
        private long left;

        BoundedStream(InputStream in, long limit) {
            super(in);
            this.left = limit;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int c = super.read();
            if (c >= 0) left--;
            return c;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int want = (int) Math.min(len, left);
            int n = super.read(b, off, want);
            if (n > 0) left -= n;
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long k = super.skip(Math.min(n, left));
            left -= k;
            return k;
        }
    }
}
