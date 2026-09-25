package com.dsh.themeforge;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * 基础模板预览：把 .mtz 里对应槽位的图片抽出来。
 *
 * · prepare() 批量抽缩略图（≤128px）放内存 LRU，用于列表显示；
 * · loadFull() 单独抽一张原图（降采样到 maxDim），用于全屏看图。
 *
 * 全程流式扫模块内层 zip，只解码需要的条目，不落盘、不整包解压。
 */
public class TemplatePreview {

    private final java.io.File tpl;
    /** 主题里确实存在的槽位 id（不受缩略图 LRU 淘汰影响）。 */
    private final java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>();
    /** 已经探测过的槽位 id（不论结果有没有），避免重复扫包。 */
    private final java.util.LinkedHashSet<String> scanned = new java.util.LinkedHashSet<>();
    private final LinkedHashMap<String, Bitmap> cache =
            new LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) {
                    return size() > 400;
                }
            };

    public TemplatePreview(java.io.File tpl) {
        this.tpl = tpl;
    }

    public boolean valid() {
        return tpl != null && tpl.isFile() && tpl.length() > 0;
    }

    public synchronized void reset() {
        cache.clear();
        found.clear();
        scanned.clear();
    }

    public synchronized Bitmap cached(SlotData.Slot s) {
        return cache.get(s.id());
    }

    /**
     * 这个主题里到底有没有这张图。
     * 与缩略图 LRU 分开记录：缩略图会被挤掉，但「存在」这个事实要一直留着，
     * 「从主题取素材」就是靠它筛出模板里真实可用的资源。
     */
    public synchronized boolean exists(SlotData.Slot s) {
        return s != null && found.contains(s.id());
    }

    public synchronized int foundCount() {
        return found.size();
    }

    private synchronized void markFound(String id) {
        found.add(id);
    }

    private synchronized boolean known(String id) {
        return found.contains(id) || scanned.contains(id);
    }

    private synchronized void put(String id, Bitmap bm) {
        found.add(id);
        if (bm != null) cache.put(id, bm);
    }

    /** 缩略图是否已经在内存里（跟 exists 不是一回事）。 */
    private synchronized boolean has(String id) {
        return cache.containsKey(id);
    }

    // ---------------- 缩略图（列表用） ----------------

    public void prepare(Context ctx, List<SlotData.Slot> slots, int size) {
        if (!valid() || slots == null || slots.isEmpty()) return;
        Map<String, List<SlotData.Slot>> byMod = new LinkedHashMap<>();
        for (SlotData.Slot s : slots) {
            if (has(s.id())) continue;
            List<SlotData.Slot> l = byMod.get(s.module);
            if (l == null) {
                l = new ArrayList<>();
                byMod.put(s.module, l);
            }
            l.add(s);
        }
        if (byMod.isEmpty()) return;

        ZipFile zf = null;
        try {
            zf = new ZipFile(tpl);
            for (Map.Entry<String, List<SlotData.Slot>> e : byMod.entrySet()) {
                String module = e.getKey();
                List<SlotData.Slot> want = e.getValue();
                if (isRoot(module)) {
                    for (SlotData.Slot s : want) {
                        ZipEntry ze = zf.getEntry(s.path);
                        if (ze == null) continue;
                        InputStream in = zf.getInputStream(ze);
                        Bitmap bm = decode(in, size);
                        Img.closeQuietly(in);
                        if (bm != null) put(s.id(), bm);
                    }
                } else {
                    Map<String, Bitmap> sink = new LinkedHashMap<>();
                    scanModule(zf, module, want, size, sink, true);
                    for (SlotData.Slot s : want) {
                        Bitmap bm = sink.get(s.path);
                        if (bm != null) put(s.id(), bm);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 模板损坏/格式异常就放弃预览，不影响其它功能
        } finally {
            close(zf);
        }
    }

    // ---------------- 存在性索引（只登记、不解码） ----------------

    /**
     * 快速登记「主题里有哪些槽位」，只比对条目名，不解码图片。
     * 给「从主题取素材」用来在一个大分类（上千个图标）里筛出真正有的那些：
     * 解码上千张缩略图会爆内存也没必要，先筛名字，再把可见的几十行解码出来看。
     */
    public synchronized void index(List<SlotData.Slot> slots) {
        if (!valid() || slots == null || slots.isEmpty()) return;

        Map<String, List<SlotData.Slot>> byMod = new LinkedHashMap<>();
        for (SlotData.Slot s : slots) {
            if (known(s.id())) continue;
            List<SlotData.Slot> l = byMod.get(s.module);
            if (l == null) {
                l = new ArrayList<>();
                byMod.put(s.module, l);
            }
            l.add(s);
        }
        if (byMod.isEmpty()) return;

        ZipFile zf = null;
        try {
            zf = new ZipFile(tpl);
            for (Map.Entry<String, List<SlotData.Slot>> e : byMod.entrySet()) {
                String module = e.getKey();
                List<SlotData.Slot> want = e.getValue();
                if (isRoot(module)) {
                    for (SlotData.Slot s : want) {
                        if (zf.getEntry(s.path) != null) markFound(s.id());
                    }
                } else {
                    indexModule(zf, module, want);
                }
                synchronized (this) {
                    for (SlotData.Slot s : want) scanned.add(s.id());
                }
            }
        } catch (Throwable ignored) {
            // 主题损坏就当作没有素材，不影响别的功能
        } finally {
            close(zf);
        }
    }

    private void indexModule(ZipFile zf, String module, List<SlotData.Slot> want) {
        ZipEntry e = zf.getEntry(module);
        if (e == null) return;

        Map<String, List<SlotData.Slot>> byPath = new LinkedHashMap<>();
        for (SlotData.Slot s : want) {
            List<SlotData.Slot> l = byPath.get(s.path);
            if (l == null) {
                l = new ArrayList<>();
                byPath.put(s.path, l);
            }
            l.add(s);
        }

        InputStream raw = null;
        ZipInputStream zin = null;
        try {
            raw = zf.getInputStream(e);
            zin = new ZipInputStream(new BoundedStream(raw,
                    e.getSize() >= 0 ? e.getSize() : Long.MAX_VALUE));
            byte[] buf = new byte[1 << 16];
            ZipEntry ne;
            while ((ne = zin.getNextEntry()) != null) {
                List<SlotData.Slot> hit = byPath.remove(ne.getName());
                if (hit == null) {
                    while (zin.read(buf) > 0) {
                        // 跳过不关心的条目
                    }
                    continue;
                }
                for (SlotData.Slot s : hit) markFound(s.id());
                if (byPath.isEmpty()) break;
            }
        } catch (Throwable ignored) {
        } finally {
            Img.closeQuietly(zin);
            Img.closeQuietly(raw);
        }
    }

    // ---------------- 原图（全屏看图用） ----------------

    /** 取模板里这张图的完整版本，最长边不超过 maxDim；没有就返回 null。 */
    public Bitmap loadFull(Context ctx, SlotData.Slot s, int maxDim) {
        if (!valid() || s == null) return null;
        ZipFile zf = null;
        try {
            zf = new ZipFile(tpl);
            if (isRoot(s.module)) {
                ZipEntry ze = zf.getEntry(s.path);
                if (ze == null) return null;
                InputStream in = zf.getInputStream(ze);
                Bitmap bm = decode(in, maxDim);
                Img.closeQuietly(in);
                return bm;
            }
            Map<String, Bitmap> out = new LinkedHashMap<>();
            scanModule(zf, s.module, java.util.Collections.singletonList(s), maxDim, out, false);
            return out.get(s.path);
        } catch (Throwable t) {
            return null;
        } finally {
            close(zf);
        }
    }

    // ---------------- 原始字节（从主题取素材用） ----------------

    /**
     * 取模板里这张图的「原始文件字节」，不做任何重编码 —— 用于把别的主题里的图标/图片
     * 原样搬过来当素材，画质和 .9.png 的边都不丢。
     */
    public byte[] loadRaw(SlotData.Slot s) {
        if (!valid() || s == null) return null;
        ZipFile zf = null;
        try {
            zf = new ZipFile(tpl);
            if (isRoot(s.module)) {
                ZipEntry ze = zf.getEntry(s.path);
                if (ze == null) return null;
                InputStream in = zf.getInputStream(ze);
                byte[] d = readAll(in, 1 << 28);
                Img.closeQuietly(in);
                if (d != null) markFound(s.id());
                return d;
            }
            return scanModuleRaw(zf, s.module, s.path);
        } catch (Throwable t) {
            return null;
        } finally {
            close(zf);
        }
    }

    private byte[] scanModuleRaw(ZipFile zf, String module, String path) {
        ZipEntry e = zf.getEntry(module);
        if (e == null) return null;
        InputStream raw = null;
        ZipInputStream zin = null;
        try {
            raw = zf.getInputStream(e);
            zin = new ZipInputStream(new BoundedStream(raw,
                    e.getSize() >= 0 ? e.getSize() : Long.MAX_VALUE));
            byte[] buf = new byte[1 << 16];
            ZipEntry ne;
            while ((ne = zin.getNextEntry()) != null) {
                if (!path.equals(ne.getName())) {
                    while (zin.read(buf) > 0) {
                        // 跳过不需要的条目
                    }
                    continue;
                }
                byte[] d = readAll(zin, 1 << 28);
                if (d != null) markFound(module + "|" + path);
                return d;
            }
        } catch (Throwable ignored) {
        } finally {
            Img.closeQuietly(zin);
            Img.closeQuietly(raw);
        }
        return null;
    }

    private static byte[] readAll(InputStream in, int limit) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > limit) return null;
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- 内部 ----------------

    private static boolean isRoot(String module) {
        return "wallpaper".equals(module) || "preview".equals(module);
    }

    private static void close(ZipFile zf) {
        try {
            if (zf != null) zf.close();
        } catch (Exception ignored) {
        }
    }

    /**
     * 流式扫一个模块内层 zip。
     *
     * @param sink   找到的图放这里（key = 模块内路径）
     * @param stopAt 集齐 sink 里需要的路径就提前结束
     */
    private void scanModule(ZipFile zf, String module, List<SlotData.Slot> want, int size,
                            Map<String, Bitmap> sink, boolean stopAt) {
        ZipEntry e = zf.getEntry(module);
        if (e == null) return;

        Map<String, List<SlotData.Slot>> byPath = new LinkedHashMap<>();
        for (SlotData.Slot s : want) {
            if (sink.containsKey(s.path)) continue;
            List<SlotData.Slot> l = byPath.get(s.path);
            if (l == null) {
                l = new ArrayList<>();
                byPath.put(s.path, l);
            }
            l.add(s);
        }
        if (byPath.isEmpty()) return;

        InputStream raw = null;
        ZipInputStream zin = null;
        try {
            raw = zf.getInputStream(e);
            zin = new ZipInputStream(new BoundedStream(raw,
                    e.getSize() >= 0 ? e.getSize() : Long.MAX_VALUE));
            byte[] buf = new byte[1 << 16];
            ZipEntry ne;
            while ((ne = zin.getNextEntry()) != null) {
                List<SlotData.Slot> hit = byPath.remove(ne.getName());
                if (hit == null) {
                    while (zin.read(buf) > 0) {
                        // 跳过不需要的条目
                    }
                    continue;
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                while ((n = zin.read(buf)) > 0) bos.write(buf, 0, n);
                Bitmap bm = decode(new ByteArrayInputStream(bos.toByteArray()), size);
                if (bm != null) sink.put(ne.getName(), bm);
                if (byPath.isEmpty() && stopAt) break;
            }
        } catch (Throwable ignored) {
        } finally {
            Img.closeQuietly(zin);
            Img.closeQuietly(raw);
        }
    }

    private static Bitmap decode(InputStream in, int size) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            byte[] data = bos.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int s = 1;
            while (o.outWidth / s > size || o.outHeight / s > size) s *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = s;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Throwable t) {
            return null;
        }
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
