package com.cztask.face

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.wearable.watchface.CanvasWatchFaceService
import android.support.wearable.watchface.WatchFaceStyle
import android.view.SurfaceHolder
import androidx.core.content.res.ResourcesCompat
import com.cztask.R
import com.cztask.ServiceLocator
import com.cztask.data.time.SystemTimeSource
import com.cztask.data.time.TimerStateStore
import com.cztask.data.time.systemBootCount
import com.cztask.ui.MainActivity
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * "Green Hill Time" — the Sonic 1 HUD, reinterpreted for a 416px round AMOLED.
 *
 * The game's HUD is already a productivity display: TIME and RINGS. Mapping:
 *   TIME  -> the clock (hero element, pixel font, hard drop shadows)
 *   RINGS -> open-task count (gold ring icon; all tasks done = ring-collect
 *            sparkle — clearing your list is collecting every ring)
 *   checkpoint line -> next reminder (star-post icon + HH:MM LABEL)
 *   running timer -> depleting gold ring around the bezel; the final 10 s go
 *            red (the drowning-countdown panic — red means exactly one thing)
 *   footer -> muted Green Hill checkerboard, pre-rendered once to a bitmap
 *
 * All art is drawn by code; the pixel font is Press Start 2P (OFL, license in
 * repo). Ambient: gray time/date only, with burn-in jitter when the platform
 * asks for it.
 */
class CzWatchFaceService : CanvasWatchFaceService() {

    override fun onCreateEngine(): Engine = CzEngine()

    private companion object {
        // Sonic HUD palette, tuned down for AMOLED.
        const val HUD_YELLOW = 0xFFF8E038.toInt()
        const val HUD_ORANGE = 0xFFF09018.toInt()
        const val RING_GOLD = 0xFFF0C018.toInt()
        const val RING_GOLD_DARK = 0xFF9A7A00.toInt()
        const val PANIC_RED = 0xFFE23D28.toInt()
        const val GRASS_GREEN = 0xFF3F8B0F.toInt()
        const val DIRT_LIGHT = 0xFF6B4520.toInt()
        const val DIRT_DARK = 0xFF4A2F14.toInt()
        const val VALUE_WHITE = 0xFFF8F8F8.toInt()
        const val DATE_GRAY = 0xFFC0C0C0.toInt()
        const val AMBIENT_GRAY = 0xFF909090.toInt()
        const val TRACK_GOLD = 0xFF3A2E08.toInt()

        const val PANIC_MS = 10_000L
        const val SHADOW = 3f          // hard pixel shadow offset — never blurred
    }

    inner class CzEngine : CanvasWatchFaceService.Engine() {

        private var ambient = false
        private var lowBit = false
        private var burnIn = false

        @Volatile private var checkpointLine: String? = null
        @Volatile private var openTasks: Int = -1

        private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
        private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM")

        private val pixelFont: Typeface by lazy {
            ResourcesCompat.getFont(this@CzWatchFaceService, R.font.press_start_2p)
                ?: Typeface.MONOSPACE
        }

        // Press Start 2P is built on an 8px grid — sizes stay on multiples.
        private val labelPaint = pxPaint(16f).apply {
            shader = LinearGradient(0f, 80f, 0f, 96f, HUD_YELLOW, HUD_ORANGE, Shader.TileMode.CLAMP)
        }
        private val timePaint = pxPaint(48f).apply { color = VALUE_WHITE }
        private val datePaint = pxPaint(16f).apply { color = DATE_GRAY }
        private val countPaint = pxPaint(24f).apply { color = VALUE_WHITE }
        private val rowPaint = pxPaint(16f).apply { color = VALUE_WHITE }
        private val timerPaint = pxPaint(24f).apply { color = RING_GOLD }
        private val shadowPaint = pxPaint(16f).apply { color = Color.BLACK }

        private val iconPaint = Paint().apply { isAntiAlias = true }
        private val arcPaint = Paint().apply {
            isAntiAlias = true; style = Paint.Style.STROKE
            strokeWidth = 10f; strokeCap = Paint.Cap.ROUND; color = RING_GOLD
        }
        private val trackPaint = Paint().apply {
            isAntiAlias = true; style = Paint.Style.STROKE
            strokeWidth = 10f; color = TRACK_GOLD
        }
        private val arcRect = RectF(14f, 14f, 402f, 402f)

        private var checkerBitmap: Bitmap? = null

        private fun pxPaint(size: Float) = Paint().apply {
            isAntiAlias = true
            textSize = size
            textAlign = Paint.Align.CENTER
            typeface = pixelFont
        }

        // Interactive-mode animation: 20 fps while the screen is actually on
        // and interactive (seconds at a time on a watch — battery-cheap), dead
        // stopped in ambient/invisible. One clock drives every animation.
        private val animTicker = Handler(Looper.getMainLooper())
        private var animRunning = false
        private val animTick = object : Runnable {
            override fun run() {
                if (!ambient && isVisible) {
                    invalidate()
                    animTicker.postDelayed(this, 50)
                } else {
                    animRunning = false
                }
            }
        }

        private fun startAnim() {
            if (!animRunning && !ambient && isVisible) {
                animRunning = true
                animTicker.post(animTick)
            }
        }

        private fun animT(): Float = (SystemClock.uptimeMillis() % 1_000_000L) / 1000f

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            setWatchFaceStyle(
                WatchFaceStyle.Builder(this@CzWatchFaceService)
                    .setAcceptsTapEvents(true)
                    .build()
            )
            refreshData()
        }

        override fun onPropertiesChanged(properties: android.os.Bundle) {
            super.onPropertiesChanged(properties)
            lowBit = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false)
            burnIn = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false)
        }

        override fun onTimeTick() {
            super.onTimeTick()
            refreshData()
            invalidate()
        }

        override fun onAmbientModeChanged(inAmbientMode: Boolean) {
            super.onAmbientModeChanged(inAmbientMode)
            ambient = inAmbientMode
            val aa = !(inAmbientMode && lowBit)
            timePaint.isAntiAlias = aa
            datePaint.isAntiAlias = aa
            if (!inAmbientMode) startAnim()
            invalidate()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                refreshData()
                startAnim()
            } else {
                animTicker.removeCallbacks(animTick)
                animRunning = false
            }
        }

        override fun onTapCommand(tapType: Int, x: Int, y: Int, eventTime: Long) {
            android.util.Log.i("CZTASK_FACE", "tap type=$tapType x=$x y=$y")
            if (tapType == TAP_TYPE_TAP && y > 200) {
                startActivity(
                    Intent(this@CzWatchFaceService, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        override fun onDraw(canvas: Canvas, bounds: Rect) {
            canvas.drawColor(Color.BLACK)
            val cx = bounds.exactCenterX()
            val now = ZonedDateTime.now()

            if (ambient) {
                // Burn-in jitter: nudge the cluster a few px on a minute cycle.
                val j = if (burnIn) ((now.minute % 3) - 1) * 4f else 0f
                timePaint.color = AMBIENT_GRAY
                canvas.drawText(now.format(timeFmt), cx + j, 160f + j, timePaint)
                datePaint.color = 0xFF606060.toInt()
                canvas.drawText(now.format(dateFmt).uppercase(), cx + j, 196f + j, datePaint)
                return
            }
            timePaint.color = VALUE_WHITE
            datePaint.color = DATE_GRAY
            val t = animT()
            val timerLeft = runningTimer()?.let { it.endElapsedRealtimeMillis - SystemClock.elapsedRealtime() }
            val panic = timerLeft != null && timerLeft in 1..PANIC_MS

            drawChecker(canvas, bounds, t, panic)

            // --- HUD: TIME hero (colon blinks at 1 Hz, like a console) ---
            val timeText = now.format(timeFmt).let { if (t % 1f < 0.5f) it else it.replace(':', ' ') }
            drawHud(canvas, "TIME", cx, 92f)
            drawShadowed(canvas, timeText, cx, 152f, timePaint)
            drawShadowed(canvas, now.format(dateFmt).uppercase(), cx, 190f, datePaint)

            // --- RINGS = open tasks ---
            var y = 240f
            if (openTasks >= 0) {
                val count = openTasks.toString()
                val textW = countPaint.measureText(count)
                val group = 30f + textW
                val iconCx = cx - group / 2 + 11f
                if (openTasks == 0) drawSparkle(canvas, iconCx, y - 9f, t)
                else drawRing(canvas, iconCx, y - 9f, t)
                countPaint.color = if (openTasks == 0) RING_GOLD else VALUE_WHITE
                drawShadowed(canvas, count, cx + group / 2 - textW / 2, y, countPaint)
                y += 38f
            }

            // --- checkpoint = next reminder ---
            checkpointLine?.let { line ->
                val textW = rowPaint.measureText(line)
                val group = 22f + textW
                drawStarPost(canvas, cx - group / 2 + 6f, y - 7f)
                drawShadowed(canvas, line, cx + group / 2 - textW / 2, y, rowPaint)
                y += 36f
            }

            // --- running timer: bezel ring + mm:ss, red panic in final 10 s ---
            runningTimer()?.let { t ->
                val left = t.endElapsedRealtimeMillis - SystemClock.elapsedRealtime()
                // Duration isn't persisted; total = left + ran-so-far. The wall
                // anchor is display-grade (a mid-timer clock change only skews
                // the arc fraction, never the countdown, which is elapsed-axis).
                val ranSoFar = (System.currentTimeMillis() - t.startedWallUtcMillis).coerceAtLeast(0)
                val total = (left + ranSoFar).coerceAtLeast(1)
                if (left > 0) {
                    val panic = left <= PANIC_MS
                    canvas.drawArc(arcRect, 0f, 360f, false, trackPaint)
                    arcPaint.color = if (panic) PANIC_RED else RING_GOLD
                    val sweep = 360f * (left.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    canvas.drawArc(arcRect, -90f, sweep, false, arcPaint)
                    timerPaint.color = when {
                        !panic -> RING_GOLD
                        (left / 1000) % 2 == 0L -> PANIC_RED   // drowning-countdown flash
                        else -> VALUE_WHITE
                    }
                    val totalSec = (left + 999) / 1000
                    drawShadowed(canvas, "%d:%02d".format(totalSec / 60, totalSec % 60), cx, y + 2f, timerPaint)
                }
            }
        }

        /** HUD label with the iconic yellow->orange vertical gradient. */
        private fun drawHud(canvas: Canvas, text: String, x: Float, y: Float) {
            shadowPaint.textSize = labelPaint.textSize
            canvas.drawText(text, x + 2f, y + 2f, shadowPaint)
            labelPaint.shader = LinearGradient(
                0f, y - 16f, 0f, y, HUD_YELLOW, HUD_ORANGE, Shader.TileMode.CLAMP
            )
            canvas.drawText(text, x, y, labelPaint)
        }

        /** Hard offset shadow — 16-bit, not modern blur. */
        private fun drawShadowed(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint) {
            shadowPaint.textSize = paint.textSize
            canvas.drawText(text, x + SHADOW, y + SHADOW, shadowPaint)
            canvas.drawText(text, x, y, paint)
        }

        /** Gold ring, spinning like the sprite: horizontal squash oscillation
         *  reads as 3D rotation on a 20 px icon. */
        private fun drawRing(canvas: Canvas, cx: Float, cy: Float, t: Float) {
            val w = kotlin.math.abs(kotlin.math.cos(t * Math.PI.toFloat() / 0.6f))
                .coerceAtLeast(0.22f) * 10f
            val oval = RectF(cx - w, cy - 10f, cx + w, cy + 10f)
            iconPaint.style = Paint.Style.STROKE
            iconPaint.strokeWidth = 6f
            iconPaint.color = RING_GOLD_DARK
            canvas.drawOval(oval, iconPaint)
            iconPaint.strokeWidth = 4f
            iconPaint.color = RING_GOLD
            canvas.drawOval(oval, iconPaint)
            iconPaint.style = Paint.Style.FILL
            iconPaint.color = Color.WHITE
            canvas.drawCircle(cx - w * 0.5f, cy - 6f, 1.8f, iconPaint)
        }

        /** Ring-collect sparkle: all tasks done = every ring collected.
         *  Slow rotation + gentle scale pulse — celebratory, not busy. */
        private fun drawSparkle(canvas: Canvas, cx: Float, cy: Float, t: Float) {
            val pulse = 0.85f + 0.15f * kotlin.math.sin(t * 2f * Math.PI.toFloat() / 0.8f)
            canvas.save()
            canvas.rotate(t * 45f % 360f, cx, cy)
            canvas.scale(pulse, pulse, cx, cy)
            iconPaint.style = Paint.Style.STROKE
            iconPaint.strokeWidth = 3f
            iconPaint.color = RING_GOLD
            canvas.drawLine(cx, cy - 11f, cx, cy + 11f, iconPaint)
            canvas.drawLine(cx - 11f, cy, cx + 11f, cy, iconPaint)
            iconPaint.strokeWidth = 2f
            canvas.drawLine(cx - 6f, cy - 6f, cx + 6f, cy + 6f, iconPaint)
            canvas.drawLine(cx - 6f, cy + 6f, cx + 6f, cy - 6f, iconPaint)
            canvas.restore()
        }

        /** Mini star post: gray pole, Sonic-blue ball. */
        private fun drawStarPost(canvas: Canvas, cx: Float, cy: Float) {
            iconPaint.style = Paint.Style.FILL
            iconPaint.color = 0xFF8A8A8A.toInt()
            canvas.drawRect(cx - 1.5f, cy - 4f, cx + 1.5f, cy + 10f, iconPaint)
            iconPaint.color = 0xFF2A5FC4.toInt()
            canvas.drawCircle(cx, cy - 8f, 5f, iconPaint)
            iconPaint.color = Color.WHITE
            canvas.drawCircle(cx - 1.5f, cy - 9.5f, 1.2f, iconPaint)
        }

        /** Green Hill footer: pre-rendered checker, scrolled like the level is
         *  running by. Pattern period 32 px divides 416 exactly, so drawing the
         *  bitmap twice tiles seamlessly. Timer panic = Sonic runs faster. */
        private fun drawChecker(canvas: Canvas, bounds: Rect, t: Float, panic: Boolean) {
            val bmp = checkerBitmap ?: Bitmap.createBitmap(bounds.width(), 64, Bitmap.Config.ARGB_8888).also { b ->
                val c = Canvas(b)
                val p = Paint()
                val sq = 16
                for (row in 0 until 4) {
                    for (col in 0..(bounds.width() / sq)) {
                        p.color = if ((row + col) % 2 == 0) DIRT_LIGHT else DIRT_DARK
                        c.drawRect(
                            (col * sq).toFloat(), (row * sq).toFloat(),
                            ((col + 1) * sq).toFloat(), ((row + 1) * sq).toFloat(), p,
                        )
                    }
                }
                p.color = GRASS_GREEN
                c.drawRect(0f, 0f, bounds.width().toFloat(), 3f, p)
                checkerBitmap = b
            }
            val speed = if (panic) 96f else 28f
            val off = (t * speed) % 32f
            val y = bounds.height() - 60f
            canvas.drawBitmap(bmp, -off, y, null)
            canvas.drawBitmap(bmp, bounds.width() - off, y, null)
        }

        private fun runningTimer(): TimerStateStore.RunningTimer? =
            (ServiceLocator.timerStateStore.recover(systemBootCount(this@CzWatchFaceService))
                as? TimerStateStore.Recovery.Running)?.timer

        private fun refreshData() {
            ServiceLocator.appScope.launch {
                val plan = ServiceLocator.reminderRepository.schedulePlan()
                val next = plan.nextFireAtUtcMillis?.let { at ->
                    val r = plan.reminderIdsAtNextFire.firstOrNull()
                        ?.let { ServiceLocator.reminderRepository.byId(it) }
                    val label = (r?.taskId?.let { ServiceLocator.db.taskDao().title(it) }
                        ?: r?.label.orEmpty()).uppercase().take(12)
                    val zdt = Instant.ofEpochMilli(at).atZone(SystemTimeSource.zone())
                    "%02d:%02d %s".format(zdt.hour, zdt.minute, label)
                }
                val open = ServiceLocator.db.taskDao().openCount()
                checkpointLine = next
                openTasks = open
                invalidate()
            }
        }

        override fun onDestroy() {
            animTicker.removeCallbacks(animTick)
            super.onDestroy()
        }
    }
}
