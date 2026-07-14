package com.cztask.tile

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.tiles.ActionBuilders
import androidx.wear.tiles.ColorBuilders.argb
import androidx.wear.tiles.DimensionBuilders.dp
import androidx.wear.tiles.DimensionBuilders.sp
import androidx.wear.tiles.LayoutElementBuilders.Box
import androidx.wear.tiles.LayoutElementBuilders.Column
import androidx.wear.tiles.LayoutElementBuilders.FontStyle
import androidx.wear.tiles.LayoutElementBuilders.Layout
import androidx.wear.tiles.LayoutElementBuilders.LayoutElement
import androidx.wear.tiles.LayoutElementBuilders.Spacer
import androidx.wear.tiles.LayoutElementBuilders.Text
import androidx.wear.tiles.ModifiersBuilders.Background
import androidx.wear.tiles.ModifiersBuilders.Clickable
import androidx.wear.tiles.ModifiersBuilders.Corner
import androidx.wear.tiles.ModifiersBuilders.Modifiers
import androidx.wear.tiles.ModifiersBuilders.Padding
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.ResourceBuilders
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import androidx.wear.tiles.TimelineBuilders.Timeline
import androidx.wear.tiles.TimelineBuilders.TimelineEntry
import com.google.common.util.concurrent.ListenableFuture
import com.cztask.ServiceLocator
import com.cztask.data.time.NextFireCalculator
import com.cztask.data.time.SystemTimeSource
import com.cztask.ui.formatTimeOfDay
import java.time.Instant
import kotlinx.coroutines.launch

/**
 * Swipe-left tile: glanceable RINGS count + next checkpoint + a "+ TASK"
 * button straight into quick capture. Palette matches the watch face.
 * Refreshes on a 60 s freshness interval plus explicit updates from
 * ReminderScheduler.reconcile (which runs after every mutation).
 */
class CzTileService : TileService() {

    private companion object {
        const val GOLD = 0xFFF0C018.toInt()
        const val WHITE = 0xFFF8F8F8.toInt()
        const val GRAY = 0xFF9E9E9E.toInt()
        const val BTN_BG = 0xFF203048.toInt()
    }

    override fun onTileRequest(request: RequestBuilders.TileRequest): ListenableFuture<Tile> =
        CallbackToFutureAdapter.getFuture { completer ->
            ServiceLocator.appScope.launch {
                try {
                    val open = ServiceLocator.db.taskDao().openCount()
                    val plan = ServiceLocator.reminderRepository.schedulePlan()
                    val next = plan.nextFireAtUtcMillis?.let { at ->
                        val r = plan.reminderIdsAtNextFire.firstOrNull()
                            ?.let { ServiceLocator.reminderRepository.byId(it) }
                        val label = (r?.taskId?.let { ServiceLocator.db.taskDao().title(it) }
                            ?: r?.label.orEmpty()).take(14)
                        val zdt = Instant.ofEpochMilli(at).atZone(SystemTimeSource.zone())
                        "${formatTimeOfDay(zdt.hour * 60 + zdt.minute)} $label"
                    }
                    completer.set(
                        Tile.Builder()
                            .setResourcesVersion("1")
                            .setFreshnessIntervalMillis(60_000)
                            .setTimeline(
                                Timeline.Builder().addTimelineEntry(
                                    TimelineEntry.Builder()
                                        .setLayout(Layout.Builder().setRoot(layout(open, next)).build())
                                        .build()
                                ).build()
                            )
                            .build()
                    )
                } catch (t: Throwable) {
                    completer.setException(t)
                }
            }
            "CzTile"
        }

    override fun onResourcesRequest(request: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(ResourceBuilders.Resources.Builder().setVersion("1").build())
            "CzTileResources"
        }

    private fun layout(open: Int, next: String?): LayoutElement =
        Column.Builder()
            .addContent(text("CZ TASK", 14f, GOLD))
            .addContent(Spacer.Builder().setHeight(dp(6f)).build())
            .addContent(text(if (open == 0) "ALL CLEAR" else "$open TASKS", 26f, WHITE))
            .addContent(Spacer.Builder().setHeight(dp(6f)).build())
            .addContent(text(next ?: "no checkpoint", 15f, if (next != null) WHITE else GRAY))
            .addContent(Spacer.Builder().setHeight(dp(14f)).build())
            .addContent(
                Box.Builder()
                    .setModifiers(
                        Modifiers.Builder()
                            .setBackground(
                                Background.Builder()
                                    .setColor(argb(BTN_BG))
                                    .setCorner(Corner.Builder().setRadius(dp(20f)).build())
                                    .build()
                            )
                            .setPadding(
                                Padding.Builder()
                                    .setStart(dp(22f)).setEnd(dp(22f))
                                    .setTop(dp(9f)).setBottom(dp(9f))
                                    .build()
                            )
                            .setClickable(
                                Clickable.Builder()
                                    .setId("add_task")
                                    .setOnClick(
                                        ActionBuilders.LaunchAction.Builder()
                                            .setAndroidActivity(
                                                ActionBuilders.AndroidActivity.Builder()
                                                    .setPackageName(packageName)
                                                    .setClassName("com.cztask.ui.QuickTaskActivity")
                                                    .build()
                                            )
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .addContent(text("+ TASK", 16f, GOLD))
                    .build()
            )
            .build()

    private fun text(s: String, size: Float, color: Int): Text =
        Text.Builder()
            .setText(s)
            .setFontStyle(
                FontStyle.Builder()
                    .setSize(sp(size))
                    .setColor(argb(color))
                    .build()
            )
            .build()
}
