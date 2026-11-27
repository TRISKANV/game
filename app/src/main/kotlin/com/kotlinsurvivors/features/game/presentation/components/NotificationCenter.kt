package com.kotlinsurvivors.features.game.presentation.components

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kotlinsurvivors.R
import com.kotlinsurvivors.core.presentation.language.localName
import com.kotlinsurvivors.engine.ecs.systems.EventKind
import com.kotlinsurvivors.features.game.domain.model.Achievement
import com.kotlinsurvivors.features.game.domain.model.LevelNotice
import com.kotlinsurvivors.features.game.domain.model.StationPhase
import kotlinx.coroutines.delay

/**
 * One thing to tell the player: a mission step, a warning, an event or an unlocked achievement.
 * [achievement] is set instead of [subtitle] when the name has to be localised while drawing.
 */
class Notice(
    val id: Long,
    val key: String,
    val icon: String,
    val title: String,
    val subtitle: String,
    val accent: Color,
    val urgent: Boolean = false,
    val achievement: Achievement? = null
)

/**
 * Everything the game wants to say goes through here, one notice at a time. Notices that arrive while
 * another is on screen wait their turn (an urgent one goes first), so none covers or replaces another.
 */
@Stable
class NoticeQueue {
    private val waiting = mutableStateListOf<Notice>()
    private var nextId = 1L

    val current: Notice? get() = waiting.firstOrNull()

    /** Queues a notice made with [levelNotice], [eventNotice] or [achievementNotice]; one already waiting with the same key is not queued twice. */
    fun post(draft: Notice) {
        if (waiting.any { it.key == draft.key }) return
        val notice = Notice(nextId++, draft.key, draft.icon, draft.title, draft.subtitle, draft.accent, draft.urgent, draft.achievement)
        if (notice.urgent && waiting.isNotEmpty()) waiting.add(1, notice) else waiting.add(notice)
        if (waiting.size > MAX_WAITING) waiting.removeAt(1)
    }

    fun finishCurrent() {
        if (waiting.isNotEmpty()) waiting.removeAt(0)
    }

    private companion object { const val MAX_WAITING = 5 }
}

/**
 * The only place notices are drawn: the top-left corner, under the health and experience bars, where it
 * covers neither the clock and counters in the middle nor the coins and the combo on the right, and
 * where the player (always in the middle of the screen) is not. The current objective sits in the same
 * slot whenever no notice is passing, so the two can never overlap.
 */
@Composable
fun NotificationHost(
    queue: NoticeQueue,
    objective: Notice?,
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    val head = queue.current
    LaunchedEffect(head?.id, visible) {
        if (visible && head != null) {
            delay(NOTICE_MS)
            queue.finishCurrent()
        }
    }
    Box(modifier = modifier.padding(start = 12.dp, top = SLOT_TOP)) {
        if (!visible) return@Box
        val shown = head ?: objective ?: return@Box
        key(shown.id) {
            PixelToast(
                icon       = shown.icon,
                title      = shown.title,
                subtitle   = shown.achievement?.localName() ?: shown.subtitle,
                accent     = shown.accent,
                lifetimeMs = if (head != null) NOTICE_MS else null
            )
        }
    }
}

private val SLOT_TOP = 92.dp
private const val NOTICE_MS = 3000L

private val Gold   = Color(0xFFFFD36B)
private val Red    = Color(0xFFFF5A5A)
private val Green  = Color(0xFF7CE04A)
private val Orange = Color(0xFFFF7043)
private val Blue   = Color(0xFF4FC3F7)

fun levelNotice(context: Context, notice: LevelNotice): Notice = when (notice) {
    LevelNotice.EXIT_OPEN    -> make("level_exit_open", "mission", R.string.level_exit_open, R.string.level_exit_open_sub, Green, context)
    LevelNotice.LEVEL_TWO    -> make("level_two", "alert", R.string.level_two_title, R.string.level_two_sub, Gold, context)
    LevelNotice.STATION      -> make("level_station", "alert", R.string.level_station_title, R.string.level_station_sub, Gold, context)
    LevelNotice.ALARM        -> make("level_alarm", "alert", R.string.level_alarm_title, R.string.level_alarm_sub, Red, context, urgent = true)
    LevelNotice.CHIEF_AWAKE  -> make("level_chief", "skull", R.string.level_chief_title, R.string.level_chief_sub, Red, context, urgent = true)
    LevelNotice.KEY_DROPPED  -> make("level_key", "mission", R.string.level_key_title, R.string.level_key_sub, Gold, context)
    LevelNotice.CELL_KEY     -> make("level_cell_key", "mission", R.string.level_cell_key_title, R.string.level_cell_key_sub, Blue, context)
    LevelNotice.DOG_LOCKED   -> make("level_dog_locked", "alert", R.string.level_dog_locked_title, R.string.level_dog_locked_sub, Gold, context)
    LevelNotice.DOG_FREED    -> make("level_dog", "check", R.string.level_dog_title, R.string.level_dog_sub, Green, context)
    LevelNotice.STATION_OPEN -> make("level_station_open", "check", R.string.level_station_open, R.string.level_station_open_sub, Green, context)
}

fun eventNotice(context: Context, kind: EventKind): Notice {
    val title = when (kind) {
        EventKind.SWARM_RUSH   -> R.string.event_swarm
        EventKind.DRONE_RAID   -> R.string.event_drones
        EventKind.SIEGE        -> R.string.event_siege
        EventKind.K9_PATROL    -> R.string.event_k9
        EventKind.MINI_BOSS    -> R.string.event_miniboss
        EventKind.WAVE_CLEARED -> R.string.event_cleared
    }
    val sub = when (kind) {
        EventKind.MINI_BOSS    -> R.string.event_miniboss_sub
        EventKind.WAVE_CLEARED -> R.string.event_cleared_sub
        EventKind.K9_PATROL    -> R.string.event_k9_sub
        else                   -> R.string.event_wave_sub
    }
    val icon = when (kind) {
        EventKind.SWARM_RUSH, EventKind.SIEGE -> "alert"
        EventKind.DRONE_RAID   -> "drone"
        EventKind.K9_PATROL    -> "k9"
        EventKind.MINI_BOSS    -> "skull"
        EventKind.WAVE_CLEARED -> "check"
    }
    val accent = when (kind) {
        EventKind.SWARM_RUSH, EventKind.DRONE_RAID, EventKind.SIEGE -> Orange
        EventKind.K9_PATROL    -> Blue
        EventKind.MINI_BOSS    -> Color(0xFFFFC107)
        EventKind.WAVE_CLEARED -> Green
    }
    return make("event_${kind.name}", icon, title, sub, accent, context, urgent = kind == EventKind.MINI_BOSS)
}

/** What the player has to do right now while the siege of the station runs; its text changes but it stays one notice. */
fun objectiveNotice(context: Context, phase: StationPhase, copsDefeated: Int, goal: Int): Notice? {
    val (icon, title, sub) = when (phase) {
        StationPhase.SIEGE -> Triple("skull", context.getString(R.string.siege_counter, copsDefeated, goal), context.getString(R.string.siege_counter_sub))
        StationPhase.CHIEF -> Triple("skull", context.getString(R.string.siege_chief_obj), context.getString(R.string.siege_chief_obj_sub))
        StationPhase.KEY   -> Triple("mission", context.getString(R.string.siege_key_obj), context.getString(R.string.siege_key_obj_sub))
        else -> return null
    }
    return Notice(-1L - phase.ordinal, "objective_${phase.name}", icon, title, sub, Red)
}

fun achievementNotice(context: Context, achievement: Achievement): Notice = Notice(
    0L, "achievement_${achievement.id}", achievement.icon, context.getString(R.string.hud_achievement_unlocked), "", Color(0xFFFFD740),
    achievement = achievement
)

private fun make(
    key: String, icon: String, title: Int, subtitle: Int, accent: Color, context: Context, urgent: Boolean = false
) = Notice(0L, key, icon, context.getString(title), context.getString(subtitle), accent, urgent)
