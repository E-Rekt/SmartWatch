package com.cztask.face

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.wearable.watchface.CanvasWatchFaceService
import android.support.wearable.watchface.WatchFaceStyle
import android.view.SurfaceHolder
import com.cztask.ServiceLocator
import com.cztask.data.time.SystemTimeSource
import com.cztask.data.time.TimerStateStore
import com.cztask.data.time.systemBootCount
import com.cztask.timer.TimerService
import com.cztask.ui.MainActivity
import com.cztask.ui.formatTimeOfDay
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * The CZTask face: the watch face IS the app's glanceable surface.
 * Time + date, next reminder, open-task count, running timer. Tap anywhere
 * below the time to open the app. Ambient mode draws time-only in gray
 * (AMOLED burn-in-safe: no large fills, thin glyphs).
 *
 * Data freshness: re-queried on visibility gain and every minute tick —
 * plenty for a glanceable surface over a tens-of-rows database. The running
 * timer line ticks per-second only while interactive AND a timer runs.
 */
class CzWatchFaceService : CanvasWatchFaceService() {

    override fun onCreateEngine(): Engine = CzEngine()

    inner class CzEngine : CanvasWatchFaceService.Engine() {

        private var ambient = false

        // Snapshot strings, refreshed off the main thread.
        @Volatile private var nextReminderLine: String? = null
        @Volatile private var tasksLine: String = ""

        private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
        private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM")

        private val timePaint = Paint().apply {
            color = Color.WHITE; textSize = 96f; isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        private val datePaint = Paint().apply {
            color = 0xFF9E9E9E.toInt(); textSize = 24f; isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        private val linePaint = Paint().apply {
            color = 0xFF64B5F6.toInt(); textSize = 26f; isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        private val subPaint = Paint().apply {
            color = 0xFF9E9E9E.toInt(); textSize = 24f; isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        private val timerPaint = Paint().apply {
            color = 0xFFEF9A9A.toInt(); textSize = 30f; isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        private val secondTicker = Handler(Looper.getMainLooper())
        private val secondTick = object : Runnable {
            override fun run() {
                if (!ambient && isVisible && runningTimer() != null) {
                    invalidate()
                    secondTicker.postDelayed(this, 1000)
                }
            }
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            setWatchFaceStyle(
                WatchFaceStyle.Builder(this@CzWatchFaceService)
                    .setAcceptsTapEvents(true)
                    .build()
            )
            refreshData()
        }

        override fun onTimeTick() {
            super.onTimeTick()
            refreshData()
            invalidate()
        }

        override fun onAmbientModeChanged(inAmbientMode: Boolean) {
            super.onAmbientModeChanged(inAmbientMode)
            ambient = inAmbientMode
            timePaint.isAntiAlias = !inAmbientMode
            timePaint.color = if (inAmbientMode) 0xFFBDBDBD.toInt() else Color.WHITE
            if (!inAmbientMode) secondTicker.post(secondTick)
            invalidate()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                refreshData()
                secondTicker.post(secondTick)
            } else {
                secondTicker.removeCallbacks(secondTick)
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

            canvas.drawText(now.format(timeFmt), cx, 150f, timePaint)
            canvas.drawText(now.format(dateFmt), cx, 188f, datePaint)

            if (ambient) return   // time-only ambient: burn-in-safe, battery-safe

            var y = 250f
            runningTimer()?.let { t ->
                val left = t.endElapsedRealtimeMillis - SystemClock.elapsedRealtime()
                if (left > 0) {
                    canvas.drawText("⏱ ${TimerService.format(left)}", cx, y, timerPaint)
                    y += 40f
                }
            }
            nextReminderLine?.let {
                canvas.drawText(it, cx, y, linePaint)
                y += 36f
            }
            canvas.drawText(tasksLine, cx, y, subPaint)
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
                    val label = r?.taskId?.let { ServiceLocator.db.taskDao().title(it) }
                        ?: r?.label.orEmpty()
                    val zdt = java.time.Instant.ofEpochMilli(at).atZone(SystemTimeSource.zone())
                    "○ ${formatTimeOfDay(zdt.hour * 60 + zdt.minute)} ${label.take(18)}"
                }
                val open = ServiceLocator.db.taskDao().openCount()
                nextReminderLine = next
                tasksLine = when (open) {
                    0 -> "no open tasks"
                    1 -> "1 task"
                    else -> "$open tasks"
                }
                invalidate()
            }
        }

        override fun onDestroy() {
            secondTicker.removeCallbacks(secondTick)
            super.onDestroy()
        }
    }
}
