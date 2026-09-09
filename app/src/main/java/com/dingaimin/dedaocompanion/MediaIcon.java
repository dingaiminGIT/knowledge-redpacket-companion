package com.dingaimin.dedaocompanion;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** A consistent 24dp icon set, independent of OEM system drawable sizing. */
final class MediaIcon extends Drawable {
    enum Kind { PLAY, PAUSE, PREVIOUS, NEXT, QUEUE, REMOVE, DRAG }
    private final Kind kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int size;

    MediaIcon(Kind kind, int color, int size) {
        this.kind = kind;
        this.size = size;
        paint.setColor(color);
        paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override public void draw(Canvas canvas) {
        int save = canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
        paint.setStyle(Paint.Style.FILL);
        if (kind == Kind.PLAY) triangle(canvas, 8, 5, 19, 12, 8, 19);
        else if (kind == Kind.PAUSE) {
            canvas.drawRoundRect(6, 5, 10, 19, 1, 1, paint);
            canvas.drawRoundRect(14, 5, 18, 19, 1, 1, paint);
        } else if (kind == Kind.PREVIOUS || kind == Kind.NEXT) {
            if (kind == Kind.PREVIOUS) {
                canvas.translate(24, 0);
                canvas.scale(-1, 1);
            }
            triangle(canvas, 5, 5, 15, 12, 5, 19);
            canvas.drawRoundRect(17, 5, 19.5f, 19, 1, 1, paint);
        } else {
            paint.setStyle(Paint.Style.STROKE);
            if (kind == Kind.QUEUE || kind == Kind.DRAG) {
                for (int y = 6; y <= 18; y += 6) {
                    if (kind == Kind.QUEUE) canvas.drawPoint(4, y, paint);
                    canvas.drawLine(kind == Kind.QUEUE ? 9 : 5, y, 20, y, paint);
                }
            } else {
                canvas.drawCircle(12, 12, 8, paint);
                canvas.drawLine(8, 12, 16, 12, paint);
            }
        }
        canvas.restoreToCount(save);
    }

    private void triangle(Canvas canvas, float x1, float y1, float x2, float y2, float x3, float y3) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        path.lineTo(x3, y3);
        path.close();
        canvas.drawPath(path, paint);
    }

    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
