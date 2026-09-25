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
    }

    public synchronized Bitmap cached(SlotData.Slot s) {
        return cache.get(s.id());
    }

    private synchronized void put(String id, Bitmap bm) {
        cache.put(id, bm);
    }

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
