package com.dsh.themeforge;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** 图片解码 / 缩放 / 九宫格生成 / 按目标后缀编码。 */
public class Img {

    /** 解码为 ARGB_8888，最长边不超过 maxDim。 */
    public static Bitmap decodeFile(File f, int maxDim) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        o.inSampleSize = sample(o.outWidth, o.outHeight, maxDim);
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
    }

    public static Bitmap decodeStream(InputStream in, int maxDim) throws IOException {
        // 先读进内存（图片场景可接受），再按需采样
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        byte[] data = bos.toByteArray();
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        o.inSampleSize = sample(o.outWidth, o.outHeight, maxDim);
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        return bm;
    }

    public static Bitmap decodeUri(ContentResolver cr, Uri u, int maxDim) throws IOException {
        InputStream in = cr.openInputStream(u);
        if (in == null) throw new IOException("无法读取图片");
        try {
            return decodeStream(in, maxDim);
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
    }

    /** 从字节数组解码（模板里取出来的素材走这里）。 */
    public static Bitmap decodeBytes(byte[] data, int maxDim) {
        if (data == null || data.length == 0) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            o.inSampleSize = sample(o.outWidth, o.outHeight, maxDim);
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 判断一张图是不是已经带九宫格边的 .9.png：
     * 右边一列、最下面一行必须全透明，上边一行 / 左边一列只能出现透明或纯黑（拉伸标记）。
     */
    public static boolean looksNinePatch(Bitmap bm) {
        if (bm == null) return false;
        int w = bm.getWidth(), h = bm.getHeight();
        if (w < 3 || h < 3) return false;
        try {
            int[] top = new int[w];
            int[] bottom = new int[w];
            int[] left = new int[h];
            int[] right = new int[h];
            bm.getPixels(top, 0, w, 0, 0, w, 1);
            bm.getPixels(bottom, 0, w, 0, h - 1, w, 1);
            bm.getPixels(left, 0, 1, 0, 0, 1, h);
            bm.getPixels(right, 0, 1, w - 1, 0, 1, h);
            boolean mark = false;
            for (int i = 0; i < w; i++) {
                if ((bottom[i] >>> 24) != 0) return false;
                int a = top[i] >>> 24;
                if (a == 0) continue;
                if (top[i] != 0xFF000000) return false;
                mark = true;
            }
            for (int i = 0; i < h; i++) {
                if ((right[i] >>> 24) != 0) return false;
                int a = left[i] >>> 24;
                if (a == 0) continue;
                if (left[i] != 0xFF000000) return false;
                mark = true;
            }
            return mark;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 去掉 .9.png 的 1px 九宫格边，只留图案本体。
     * 从别的主题搬素材时必须做这一步：不然打包时会再补一层边，变成 3px 双层边。
     */
    public static Bitmap stripNinePatch(Bitmap bm) {
        if (!looksNinePatch(bm)) return bm;
        try {
            Bitmap out = Bitmap.createBitmap(bm, 1, 1, bm.getWidth() - 2, bm.getHeight() - 2);
            if (out != bm) bm.recycle();
            return out;
        } catch (Throwable t) {
            return bm;
        }
    }

    private static int sample(int w, int h, int maxDim) {
        int s = 1;
        if (w <= 0 || h <= 0) return 1;
        while (Math.max(w / s, h / s) > maxDim) s *= 2;
        return s;
    }

    /** 等比缩放并居中裁切，铺满 w×h（用于壁纸、背景图）。 */
    public static Bitmap cover(Bitmap src, int w, int h) {
        if (w <= 0 || h <= 0) return src;
        if (src.getWidth() == w && src.getHeight() == h) return src;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Matrix m = new Matrix();
        float sw = (float) w / src.getWidth();
        float sh = (float) h / src.getHeight();
        float sc = Math.max(sw, sh);
        m.setScale(sc, sc);
        m.postTranslate((w - src.getWidth() * sc) / 2f, (h - src.getHeight() * sc) / 2f);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        c.drawBitmap(src, m, p);
        return out;
    }

    /** 等比缩放完整放入 w×h，居中，透明留边（用于图标）。 */
    public static Bitmap fit(Bitmap src, int w, int h) {
        if (w <= 0 || h <= 0) return src;
        if (src.getWidth() == w && src.getHeight() == h) return src;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Matrix m = new Matrix();
        float sc = Math.min((float) w / src.getWidth(), (float) h / src.getHeight());
        m.setScale(sc, sc);
        m.postTranslate((w - src.getWidth() * sc) / 2f, (h - src.getHeight() * sc) / 2f);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        c.drawBitmap(src, m, p);
        return out;
    }

    /** 直接拉伸到 w×h（不保持比例）。 */
    public static Bitmap stretch(Bitmap src, int w, int h) {
        if (w <= 0 || h <= 0) return src;
        if (src.getWidth() == w && src.getHeight() == h) return src;
        return Bitmap.createScaledBitmap(src, w, h, true);
    }

    /**
     * 把普通图片转成合法的九宫格 .9.png：
     * 在四周补 1px 透明边，并在上边/左边画黑色拉伸标记（取中间 30%~70% 区域）。
     */
    public static Bitmap ninePatch(Bitmap src) {
        int w = Math.max(1, src.getWidth());
        int h = Math.max(1, src.getHeight());
        Bitmap out = Bitmap.createBitmap(w + 2, h + 2, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawBitmap(src, 1f, 1f, null);
        int x0 = Math.min(w - 1, Math.max(0, (int) (w * 0.30)));
        int x1 = Math.min(w - 1, Math.max(x0, (int) (w * 0.70)));
        int y0 = Math.min(h - 1, Math.max(0, (int) (h * 0.30)));
        int y1 = Math.min(h - 1, Math.max(y0, (int) (h * 0.70)));
        for (int x = x0; x <= x1; x++) out.setPixel(x + 1, 0, 0xFF000000);
        for (int y = y0; y <= y1; y++) out.setPixel(0, y + 1, 0xFF000000);
        return out;
    }

    /** 按目标文件后缀选择编码格式写流。 */
    public static void write(Bitmap bm, OutputStream os, String targetPath) throws IOException {
        String p = targetPath.toLowerCase();
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) {
            Bitmap flat = bm;
            if (bm.hasAlpha()) {
                flat = Bitmap.createBitmap(bm.getWidth(), bm.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(flat);
                c.drawColor(0xFF000000);
                c.drawBitmap(bm, 0, 0, null);
            }
            flat.compress(Bitmap.CompressFormat.JPEG, 93, os);
            if (flat != bm) flat.recycle();
        } else if (p.endsWith(".webp")) {
            if (Build.VERSION.SDK_INT >= 30) {
                bm.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, os);
            } else {
                bm.compress(Bitmap.CompressFormat.WEBP, 95, os);
            }
        } else {
            bm.compress(Bitmap.CompressFormat.PNG, 100, os);
        }
    }

    public static void savePng(Bitmap bm, File dst) throws IOException {
        FileOutputStream fos = new FileOutputStream(dst);
        try {
            bm.compress(Bitmap.CompressFormat.PNG, 100, fos);
        } finally {
            try { fos.close(); } catch (Exception ignored) {}
        }
    }

    public static void closeQuietly(java.io.Closeable c) {
        if (c != null) try { c.close(); } catch (Exception ignored) {}
    }
}
