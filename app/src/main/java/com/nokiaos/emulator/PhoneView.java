package com.nokiaos.emulator;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

/** Renders the 320x480 ILI9488-style framebuffer scaled to fit the view. */
public class PhoneView extends View {

    private CorePeripherals core;
    private final Bitmap bitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888);
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final int[] pixelBuf = new int[320 * 480];

    public PhoneView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
    }

    public void bind(CorePeripherals core) {
        this.core = core;
    }

    public void refresh() {
        if (core == null) return;
        // Simplified luminance framebuffer -> greenish LCD look
        for (int i = 0; i < pixelBuf.length; i++) {
            int lum = core.framebuffer[i] & 0xFF;
            pixelBuf[i] = 0xFF000000 | (lum << 16) | ((Math.min(255, lum + 40)) << 8) | (lum);
        }
        bitmap.setPixels(pixelBuf, 0, 320, 0, 0, 320, 480);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Rect dst = new Rect(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(bitmap, null, dst, paint);
    }
}
