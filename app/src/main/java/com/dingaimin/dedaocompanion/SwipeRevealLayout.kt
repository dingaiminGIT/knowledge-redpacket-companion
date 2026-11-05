package com.dingaimin.dedaocompanion

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/** A left swipe reveals a separate action. No swipe distance ever executes it. */
@SuppressLint("ViewConstructor") // Programmatic-only layout; never inflated from XML.
class SwipeRevealLayout(
    context: Context,
    private val content: View,
    private val action: View,
    private val onOpened: (SwipeRevealLayout) -> Unit,
    private val onClosed: (SwipeRevealLayout) -> Unit,
) : FrameLayout(context) {
    private val revealWidth = (88 * resources.displayMetrics.density).toInt()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var startOffset = 0f
    private var dragging = false
    private var verticalGesture = false
    private var closingTap = false
    private var cancelledGesture = false
    private var offset = 0f
    var isRevealed = false
        private set

    init {
        clipChildren = true
        layoutDirection = View.LAYOUT_DIRECTION_LTR // The gesture is explicitly leftward.
        action.visibility = View.INVISIBLE
        action.isEnabled = false
        addView(action, LayoutParams(revealWidth, LayoutParams.MATCH_PARENT, Gravity.END))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun close() {
        settle(false)
    }

    fun closeIfRevealed(): Boolean {
        if (!isRevealed && offset == 0f) return false
        close()
        return true
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                content.animate().cancel()
                offset = -content.translationX
                downX = event.x
                downY = event.y
                startOffset = offset
                dragging = false
                verticalGesture = false
                cancelledGesture = false
                closingTap = offset > 0f && event.x < width - offset
                // Decide the axis before allowing ScrollView to take over.
                parent.requestDisallowInterceptTouchEvent(true)
                return closingTap
            }
            MotionEvent.ACTION_MOVE -> {
                if (verticalGesture || cancelledGesture) return false
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dy) > touchSlop && abs(dy) >= abs(dx) / 1.5f) {
                    verticalGesture = true
                    close()
                    parent.requestDisallowInterceptTouchEvent(false)
                    return false
                }
                if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.5f) {
                    dragging = true
                    action.isEnabled = false
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelledGesture = true
                close()
                // Cancel the child's pending tap/long press too.
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelledGesture = true
                close()
            }
            MotionEvent.ACTION_MOVE -> {
                if (cancelledGesture || verticalGesture) return true
                val dx = event.x - downX
                val dy = event.y - downY
                if (!dragging && abs(dy) > touchSlop && abs(dy) >= abs(dx) / 1.5f) {
                    verticalGesture = true
                    close()
                    parent.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                if (!dragging && abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.5f) {
                    dragging = true
                    action.isEnabled = false
                }
                if (dragging) {
                    offset = (startOffset - dx).coerceIn(0f, revealWidth.toFloat())
                    action.visibility = if (offset > 0f) View.VISIBLE else View.INVISIBLE
                    content.translationX = -offset
                }
            }
            MotionEvent.ACTION_UP -> {
                parent.requestDisallowInterceptTouchEvent(false)
                if (closingTap && !dragging && !verticalGesture && !cancelledGesture) {
                    performClick()
                    return true
                }
                val open = dragging && !cancelledGesture && !verticalGesture &&
                    if (startOffset > 0f) offset > revealWidth * 0.75f
                    else offset >= revealWidth * 0.4f
                settle(open)
            }
            MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                settle(false)
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        close()
        return true
    }

    private fun settle(open: Boolean) {
        content.animate().cancel()
        isRevealed = open
        action.isEnabled = open
        if (open) {
            action.visibility = View.VISIBLE
            onOpened(this)
        } else {
            onClosed(this)
        }
        offset = if (open) revealWidth.toFloat() else 0f
        content.animate().translationX(-offset).setDuration(160).withEndAction {
            if (!isRevealed) action.visibility = View.INVISIBLE
        }.start()
    }

    override fun onDetachedFromWindow() {
        content.animate().cancel()
        onClosed(this)
        super.onDetachedFromWindow()
    }
}
