package com.dsh.themeforge;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** 简易裁剪框：拖动白框移动，拖四角缩放；对外给出原图坐标下的裁剪矩形。 */
public class CropView extends View {

    private Bitmap bmp;
    private final Matrix mat = new Matrix();
    private final Matrix inv = new Matrix();
    private final RectF disp = new RectF();   // 图片在控件中的显示区域
    private final RectF crop = new RectF();   // 裁剪框（控件坐标）
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint dimPaint = new Paint();
    private final Paint framePaint = new Paint();
    private final Paint gridPaint = new Paint();
    private final Paint cornerPaint = new Paint();

    private float touchSlop;
    private int mode; // 0 无，1 移动，2 左上，3 右上，4 左下，5 右下
    private float lastX, lastY;

    public CropView(Context c) {
        super(c);
        init();
    }

    public CropView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private void init() {
        touchSlop = dp(12);
        dimPaint.setColor(0x9E000000);
        framePaint.setColor(0xFFFFFFFF);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(dp(2));
        gridPaint.setColor(0x66FFFFFF);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(1));
        cornerPaint.setColor(0xFFFFFFFF);
        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeWidth(dp(4));
        setBackgroundColor(0xFF14181C);
    }

    public void setBitmap(Bitmap b) {
        bmp = b;
        invalidate();
        post(this::resetCrop);
    }

    public Bitmap bitmap() {
        return bmp;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        computeMatrix();
        resetCrop();
    }

    private void computeMatrix() {
        if (bmp == null || getWidth() == 0) return;
        float vw = getWidth(), vh = getHeight();
        float s = Math.min(vw / bmp.getWidth(), vh / bmp.getHeight());
        float dw = bmp.getWidth() * s, dh = bmp.getHeight() * s;
        float left = (vw - dw) / 2f, top = (vh - dh) / 2f;
        mat.reset();
        mat.postScale(s, s);
        mat.postTranslate(left, top);
        mat.invert(inv);
        disp.set(left, top, left + dw, top + dh);
    }

    /** 裁剪框复位为整张图（留 4% 边距便于拖角）。 */
    public void resetCrop() {
        if (disp.width() <= 0) return;
        float mx = disp.width() * 0.04f, my = disp.height() * 0.04f;
        crop.set(disp.left + mx, disp.top + my, disp.right - mx, disp.bottom - my);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (bmp == null) return;
        c.drawBitmap(bmp, mat, bmpPaint);
        // 四块遮罩
        c.drawRect(0, 0, getWidth(), crop.top, dimPaint);
        c.drawRect(0, crop.bottom, getWidth(), getHeight(), dimPaint);
        c.drawRect(0, crop.top, crop.left, crop.bottom, dimPaint);
        c.drawRect(crop.right, crop.top, getWidth(), crop.bottom, dimPaint);
        // 三分线
        for (int i = 1; i <= 2; i++) {
            float x = crop.left + crop.width() * i / 3f;
            float y = crop.top + crop.height() * i / 3f;
            c.drawLine(x, crop.top, x, crop.bottom, gridPaint);
            c.drawLine(crop.left, y, crop.right, y, gridPaint);
        }
        c.drawRect(crop, framePaint);
        // 四角
        float L = Math.min(dp(26), Math.min(crop.width(), crop.height()) / 3f);
        drawCorner(c, crop.left, crop.top, L, 1, 1);
        drawCorner(c, crop.right, crop.top, L, -1, 1);
        drawCorner(c, crop.left, crop.bottom, L, 1, -1);
        drawCorner(c, crop.right, crop.bottom, L, -1, -1);
    }

    private void drawCorner(Canvas c, float x, float y, float L, int sx, int sy) {
        c.drawLine(x, y, x + L * sx, y, cornerPaint);
        c.drawLine(x, y, x, y + L * sy, cornerPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (bmp == null) return false;
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                lastX = x;
                lastY = y;
                mode = hitTest(x, y);
                return mode != 0;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mode == 0) return false;
                float dx = x - lastX, dy = y - lastY;
                lastX = x;
                lastY = y;
                if (mode == 1) {
                    dx = clampDelta(dx, disp.left - crop.left, disp.right - crop.right);
                    dy = clampDelta(dy, disp.top - crop.top, disp.bottom - crop.bottom);
                    crop.offset(dx, dy);
                } else {
                    float min = dp(48);
                    if (mode == 2 || mode == 4) {
                        float nl = crop.left + dx;
                        nl = Math.max(disp.left, Math.min(nl, crop.right - min));
                        crop.left = nl;
                    } else {
                        float nr = crop.right + dx;
                        nr = Math.min(disp.right, Math.max(nr, crop.left + min));
                        crop.right = nr;
                    }
                    if (mode == 2 || mode == 3) {
                        float nt = crop.top + dy;
                        nt = Math.max(disp.top, Math.min(nt, crop.bottom - min));
                        crop.top = nt;
                    } else {
                        float nb = crop.bottom + dy;
                        nb = Math.min(disp.bottom, Math.max(nb, crop.top + min));
                        crop.bottom = nb;
                    }
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mode = 0;
                return true;
        }
        return super.onTouchEvent(e);
    }

    private float clampDelta(float d, float lo, float hi) {
        return Math.max(lo, Math.min(d, hi));
    }

    private int hitTest(float x, float y) {
        if (near(x, y, crop.left, crop.top)) return 2;
        if (near(x, y, crop.right, crop.top)) return 3;
        if (near(x, y, crop.left, crop.bottom)) return 4;
        if (near(x, y, crop.right, crop.bottom)) return 5;
        if (crop.contains(x, y)) return 1;
        return 0;
    }

    private boolean near(float x, float y, float px, float py) {
        return Math.abs(x - px) <= touchSlop && Math.abs(y - py) <= touchSlop;
    }

    /** 返回原图坐标系下的裁剪矩形（已与图片边界求交）。 */
    public Rect getCropRect() {
        if (bmp == null) return null;
        RectF r = new RectF(crop);
        inv.mapRect(r);
        Rect out = new Rect(Math.round(r.left), Math.round(r.top),
                Math.round(r.right), Math.round(r.bottom));
        Rect b = new Rect(0, 0, bmp.getWidth(), bmp.getHeight());
        if (!out.intersect(b)) return b;
        if (out.width() < 1 || out.height() < 1) return b;
        return out;
    }
}
