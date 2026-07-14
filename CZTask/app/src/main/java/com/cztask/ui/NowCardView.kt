package com.cztask.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.cztask.R
import com.cztask.timer.TimerService

/**
 * The hero of the NOW screen: one canvas card answering "what should I be
 * doing right now?" All Paints/rects pre-allocated — zero allocation in
 * onDraw (96 MB heap, lowRam). Self-ticks once a second only while a timer
 * state is displayed AND the view is attached+visible.
 *
 * States (Phase A): ACT (timer running), FEATURED (task + GO pill),
 * ALL_CLEAR (sparkle + next checkpoint).
 */
class NowCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    sealed interface State {
        data class Act(val endElapsed: Long, val label: String?) : State
        data class Featured(val taskId: Long, val title: String, val pinned: Boolean, val goSeconds: Int) : State
        data class AllClear(val nextCheckpoint: String?) : State
    }

    var state: State = State.AllClear(null)
        set(value) {
            field = value
            syncTicker()
            invalidate()
        }

    var onGo: ((taskId: Long, title: String) -> Unit)? = null
    var onOpenTimers: (() -> Unit)? = null
    var onNotNow: ((taskId: Long, wasPinned: Boolean) -> Unit)? = null

    private companion object {
        const val GOLD = 0xFFF0C018.toInt()
        const val WHITE = 0xFFF8F8F8.toInt()
        const val GRAY = 0xFF9E9E9E.toInt()
        const val PANIC_RED = 0xFFE23D28.toInt()
        const val PILL_BG = 0xFF203048.toInt()
    }

    private val pixelFont = ResourcesCompat.getFont(context, R.font.press_start_2p)

    private val bigPaint = Paint().apply {
        isAntiAlias = true; textAlign = Paint.Align.CENTER
        typeface = pixelFont; textSize = 48f; color = WHITE
    }
    private val labelPaint = Paint().apply {
        isAntiAlias = true; textAlign = Paint.Align.CENTER
        textSize = 26f; color = WHITE
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val subPaint = Paint().apply {
        isAntiAlias = true; textAlign = Paint.Align.CENTER
        textSize = 20f; color = GRAY
    }
    private val pillTextPaint = Paint().apply {
        isAntiAlias = true; textAlign = Paint.Align.CENTER
        typeface = pixelFont; textSize = 18f; color = GOLD
    }
    private val pillBgPaint = Paint().apply { isAntiAlias = true; color = PILL_BG }
    private val arcPaint = Paint().apply {
        isAntiAlias = true; style = Paint.Style.STROKE
        strokeWidth = 8f; strokeCap = Paint.Cap.ROUND; color = GOLD
    }
    private val iconPaint = Paint().apply { isAntiAlias = true }

    private val pillRect = RectF()
    private val arcRect = RectF()

    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private var ticking = false
    private val tick = object : Runnable {
        override fun run() {
            if (isAttachedToWindow && state is State.Act) {
                invalidate()
                ticker.postDelayed(this, 1000)
            } else {
                ticking = false
            }
        }
    }

    private fun syncTicker() {
        if (state is State.Act && !ticking && isAttachedToWindow) {
            ticking = true
            ticker.postDelayed(tick, 1000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncTicker()
    }

    override fun onDetachedFromWindow() {
        ticker.removeCallbacks(tick)
        ticking = false
        super.onDetachedFromWindow()
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            when (val s = state) {
                is State.Act -> onOpenTimers?.invoke()
                is State.Featured ->
                    if (pillRect.contains(e.x, e.y)) onGo?.invoke(s.taskId, s.title)
                is State.AllClear -> Unit
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            (state as? State.Featured)?.let { s ->
                if (!pillRect.contains(e.x, e.y)) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    onNotNow?.invoke(s.taskId, s.pinned)
                }
            }
        }
    })

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        canvas.drawColor(Color.BLACK)

        when (val s = state) {
            is State.Act -> {
                val left = (s.endElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                val panic = left in 1..10_000
                arcRect.set(cx - 78f, 8f, cx + 78f, 164f)
                arcPaint.color = if (panic) PANIC_RED else GOLD
                // Simple sweep by seconds within the minute for liveliness;
                // the face's bezel ring holds the true total fraction.
                val frac = ((left / 1000) % 60) / 60f
                canvas.drawArc(arcRect, -90f, -360f * (1f - frac), false, arcPaint)
                bigPaint.color = if (panic && (left / 1000) % 2 == 0L) PANIC_RED else WHITE
                canvas.drawText(TimerService.format(left), cx, 100f, bigPaint)
                s.label?.let {
                    subPaint.color = GRAY
                    canvas.drawText(it.take(18).uppercase(), cx, 140f, subPaint)
                }
            }
            is State.Featured -> {
                if (s.pinned) {
                    iconPaint.color = GOLD
                    iconPaint.textSize = 22f
                    iconPaint.textAlign = Paint.Align.CENTER
                    iconPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
                    canvas.drawText("★", cx, 34f, iconPaint)
                }
                labelPaint.color = WHITE
                drawWrapped(canvas, s.title, cx, if (s.pinned) 68f else 52f, labelPaint, 18)
                val pillText = "▶ GO ${TimerService.format(s.goSeconds * 1000L)}"
                val tw = pillTextPaint.measureText(pillText)
                pillRect.set(cx - tw / 2 - 26f, h - 66f, cx + tw / 2 + 26f, h - 18f)
                canvas.drawRoundRect(pillRect, 24f, 24f, pillBgPaint)
                canvas.drawText(pillText, cx, h - 34f, pillTextPaint)
            }
            is State.AllClear -> {
                drawSparkle(canvas, cx, 52f)
                labelPaint.color = GOLD
                canvas.drawText("ALL CLEAR", cx, 104f, labelPaint)
                subPaint.color = GRAY
                canvas.drawText(s.nextCheckpoint ?: "no checkpoints", cx, 140f, subPaint)
            }
        }
    }

    /** Two-line wrap, centered; keeps titles readable without a scroll view. */
    private fun drawWrapped(canvas: Canvas, text: String, cx: Float, y: Float, paint: Paint, perLine: Int) {
        if (text.length <= perLine) {
            canvas.drawText(text, cx, y + 26f, paint)
            return
        }
        val cut = text.lastIndexOf(' ', perLine).takeIf { it > 0 } ?: perLine
        canvas.drawText(text.substring(0, cut).trim(), cx, y + 12f, paint)
        canvas.drawText(text.substring(cut).trim().take(perLine), cx, y + 46f, paint)
    }

    private fun drawSparkle(canvas: Canvas, cx: Float, cy: Float) {
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeWidth = 3f
        iconPaint.color = GOLD
        canvas.drawLine(cx, cy - 14f, cx, cy + 14f, iconPaint)
        canvas.drawLine(cx - 14f, cy, cx + 14f, cy, iconPaint)
        iconPaint.strokeWidth = 2f
        canvas.drawLine(cx - 8f, cy - 8f, cx + 8f, cy + 8f, iconPaint)
        canvas.drawLine(cx - 8f, cy + 8f, cx + 8f, cy - 8f, iconPaint)
        iconPaint.style = Paint.Style.FILL
    }
}
