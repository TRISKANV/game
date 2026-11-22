package com.kotlinsurvivors.features.game.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotlinsurvivors.R
import com.kotlinsurvivors.engine.ecs.components.EnemyType
import com.kotlinsurvivors.engine.ecs.components.PickupType
import com.kotlinsurvivors.engine.ecs.components.WeaponType
import com.kotlinsurvivors.engine.ecs.systems.EventKind
import com.kotlinsurvivors.engine.lab.LabCommand

private val Cyan   = Color(0xFF34DCF2)
private val PanelBg = Color(0xEA0A0A14)

private enum class LabTab(val title: Int) {
    ENEMIES(R.string.lab_tab_enemies),
    BOSSES(R.string.lab_tab_bosses),
    ITEMS(R.string.lab_tab_items),
    EVENTS(R.string.lab_tab_events),
    WEAPONS(R.string.lab_tab_weapons),
    PLAYER(R.string.lab_tab_player)
}

private val ENEMY_COUNTS = intArrayOf(1, 5, 20)
private val COMBO_STEPS  = intArrayOf(10, 25, 50, 100)

/**
 * The laboratory's test panel: a small tab on the right edge that opens a list of buttons, to spawn
 * enemies, bosses and items, start events, hand out weapons and tweak the player, while the game keeps
 * running behind it. The left half of the screen stays free for the joystick.
 */
@Composable
fun LabPanel(
    onCommand: (LabCommand) -> Unit,
    modifier: Modifier = Modifier
) {
    var open by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var countIndex by rememberSaveable { mutableStateOf(0) }
    var invincible by rememberSaveable { mutableStateOf(true) }

    if (!open) {
        Button(
            onClick  = { open = true },
            modifier = modifier.padding(end = 4.dp),
            shape    = RoundedCornerShape(2.dp),
            colors   = ButtonDefaults.buttonColors(containerColor = PanelBg, contentColor = Cyan),
            border   = BorderStroke(2.dp, Cyan.copy(alpha = 0.6f)),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)
        ) { Text(stringResource(R.string.lab_open), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold) }
        return
    }

    Column(
        modifier = modifier
            .padding(vertical = 8.dp, horizontal = 4.dp)
            .width(260.dp)
            .fillMaxHeight()
            .background(PanelBg, RoundedCornerShape(3.dp))
            .border(2.dp, Cyan.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(R.string.lab_title), color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
            LabButton(stringResource(R.string.lab_close), Modifier.width(60.dp), tint = Color(0xFFFF8A80)) { open = false }
        }
        Spacer(Modifier.height(6.dp))

        // Tabs, two rows of three so they fit the panel's width.
        for (row in LabTab.values().toList().chunked(3)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (t in row) {
                    LabButton(stringResource(t.title), Modifier.weight(1f), selected = tab == t.ordinal) { tab = t.ordinal }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(2.dp))

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (LabTab.values()[tab]) {
                LabTab.ENEMIES -> {
                    val count = ENEMY_COUNTS[countIndex]
                    LabButton(stringResource(R.string.lab_count, count), Modifier.fillMaxWidth(), selected = true) {
                        countIndex = (countIndex + 1) % ENEMY_COUNTS.size
                    }
                    ButtonGrid(
                        EnemyType.values().filter { !it.isBoss }.map { type ->
                            pretty(type.name) to { onCommand(LabCommand.SpawnEnemy(type, count)) }
                        }
                    )
                }
                LabTab.BOSSES -> {
                    SectionLabel(stringResource(R.string.lab_mini_bosses))
                    ButtonGrid(
                        listOf(EnemyType.BASIC, EnemyType.TANK, EnemyType.FAST, EnemyType.RANGED).map { type ->
                            pretty(type.name) to { onCommand(LabCommand.SpawnMiniBoss(type)) }
                        }
                    )
                    SectionLabel(stringResource(R.string.lab_bosses))
                    ButtonGrid(
                        EnemyType.values().filter { it.isBoss }.map { type ->
                            pretty(type.name.removePrefix("BOSS_")) to { onCommand(LabCommand.SpawnEnemy(type, 1)) }
                        }
                    )
                }
                LabTab.ITEMS -> ButtonGrid(
                    PickupType.values().map { type ->
                        pickupName(type) to { onCommand(LabCommand.DropPickup(type)) }
                    }
                )
                LabTab.EVENTS -> ButtonGrid(
                    EventKind.values().map { kind ->
                        pretty(kind.name) to { onCommand(LabCommand.StartEvent(kind)) }
                    }
                )
                LabTab.WEAPONS -> {
                    SectionLabel(stringResource(R.string.lab_weapons_hint))
                    ButtonGrid(
                        WeaponType.values().map { type ->
                            pretty(type.name) to { onCommand(LabCommand.GiveWeapon(type)) }
                        }
                    )
                }
                LabTab.PLAYER -> {
                    val invincibleText = stringResource(if (invincible) R.string.lab_invincible_on else R.string.lab_invincible_off)
                    LabButton(invincibleText, Modifier.fillMaxWidth(), selected = invincible) {
                        invincible = !invincible
                        onCommand(LabCommand.SetInvincible(invincible))
                    }
                    ButtonGrid(
                        listOf(
                            stringResource(R.string.lab_level_up) to { onCommand(LabCommand.LevelUp) },
                            stringResource(R.string.lab_heal)     to { onCommand(LabCommand.HealFull) },
                            stringResource(R.string.lab_clear)    to { onCommand(LabCommand.ClearEnemies) }
                        )
                    )
                    SectionLabel(stringResource(R.string.lab_combo))
                    ButtonGrid(COMBO_STEPS.map { kills -> "+$kills" to { onCommand(LabCommand.AddCombo(kills)) } })
                }
            }
        }
    }
}

/** Buttons two to a row, filling the width. */
@Composable
private fun ButtonGrid(items: List<Pair<String, () -> Unit>>) {
    for (row in items.chunked(2)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((text, action) in row) LabButton(text, Modifier.weight(1f), onClick = action)
            if (row.size == 1) Box(Modifier.weight(1f))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Color(0xFFA5B3A0), fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun LabButton(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    tint: Color = Cyan,
    onClick: () -> Unit
) {
    Button(
        onClick  = onClick,
        modifier = modifier.height(34.dp),
        shape    = RoundedCornerShape(2.dp),
        colors   = ButtonDefaults.buttonColors(
            containerColor = if (selected) tint.copy(alpha = 0.30f) else Color(0xFF16161F),
            contentColor   = tint
        ),
        border   = BorderStroke(1.dp, tint.copy(alpha = if (selected) 0.9f else 0.4f)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp)
    ) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** SWARM_RUSH → "Swarm Rush". */
private fun pretty(name: String): String =
    name.lowercase().split('_').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

/** Pickups get their in-game names where the enum name would not say what they are. */
private fun pickupName(type: PickupType): String = when (type) {
    PickupType.HEALTH_SMALL -> "Energy can"
    PickupType.HEALTH_LARGE -> "Big can"
    PickupType.EXPERIENCE_SMALL  -> "XP small"
    PickupType.EXPERIENCE_MEDIUM -> "XP medium"
    PickupType.EXPERIENCE_LARGE  -> "XP large"
    else -> pretty(type.name)
}
