package com.dingaimin.dedaocompanion

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** A consistent 24dp icon set, independent of OEM system drawable sizing. */
class MediaIcon(private val kind: Kind, color: Int, private val size: Int) : Drawable() {
    private val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    enum class Kind {
        PLAY,
        PAUSE,
        PREVIOUS,
        NEXT,
        QUEUE,
        REMOVE,
        DRAG,
    }

    init {
        paint.setColor(color)
        paint.setStrokeWidth(1.8f)
        paint.setStrokeCap(Paint.Cap.ROUND)
        paint.setStrokeJoin(Paint.Join.ROUND)
    }

    public override fun draw(canvas: Canvas) {
        val save: Int = canvas.save()
        canvas.translate(getBounds().left.toFloat(), getBounds().top.toFloat())
        canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f)
        paint.setStyle(Paint.Style.FILL)
        if (kind == Kind.PLAY) triangle(canvas, 8f, 5f, 19f, 12f, 8f, 19f)
        else if (kind == Kind.PAUSE) {
            canvas.drawRoundRect(6f, 5f, 10f, 19f, 1f, 1f, paint)
            canvas.drawRoundRect(14f, 5f, 18f, 19f, 1f, 1f, paint)
        } else if (kind == Kind.PREVIOUS || kind == Kind.NEXT) {
            if (kind == Kind.PREVIOUS) {
                canvas.translate(24f, 0f)
                canvas.scale(-1f, 1f)
            }
            triangle(canvas, 5f, 5f, 15f, 12f, 5f, 19f)
            canvas.drawRoundRect(17f, 5f, 19.5f, 19f, 1f, 1f, paint)
        } else {
            paint.setStyle(Paint.Style.STROKE)
            if (kind == Kind.QUEUE || kind == Kind.DRAG) {
                var y: Int = 6
                while (y <= 18) {
                    if (kind == Kind.QUEUE) canvas.drawPoint(4f, y.toFloat(), paint)
                    canvas.drawLine(
                        (if (kind == Kind.QUEUE) 9 else 5).toFloat(),
                        y.toFloat(),
                        20f,
                        y.toFloat(),
                        paint,
                    )
                    y += 6
                }
            } else {
                canvas.drawCircle(12f, 12f, 8f, paint)
                canvas.drawLine(8f, 12f, 16f, 12f, paint)
            }
        }
        canvas.restoreToCount(save)
    }

    private fun triangle(
        canvas: Canvas,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        x3: Float,
        y3: Float,
    ) {
        val path: Path = Path()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        path.lineTo(x3, y3)
        path.close()
        canvas.drawPath(path, paint)
    }

    public override fun getIntrinsicWidth(): Int {
        return size
    }

    public override fun getIntrinsicHeight(): Int {
        return size
    }

    public override fun setAlpha(alpha: Int) {
        paint.setAlpha(alpha)
        invalidateSelf()
    }

    public override fun setColorFilter(filter: ColorFilter?) {
        paint.setColorFilter(filter)
        invalidateSelf()
    }

    public override fun getOpacity(): Int {
        return PixelFormat.TRANSLUCENT
    }
}
