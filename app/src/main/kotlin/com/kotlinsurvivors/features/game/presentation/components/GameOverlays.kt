package com.kotlinsurvivors.features.game.presentation.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotlinsurvivors.engine.input.VirtualJoystick
import com.kotlinsurvivors.features.game.domain.model.GameState
import com.kotlinsurvivors.features.game.domain.model.LevelUpOption
import com.kotlinsurvivors.features.game.domain.model.UpgradeType
import com.kotlinsurvivors.features.game.presentation.viewmodel.GameViewModel

// ── Virtual Joystick ────────────────────────────────────────────────────────

@Composable
fun VirtualJoystickOverlay(viewModel: GameViewModel) {
    val joystick = viewModel.joystick ?: return

    Box(modifier = Modifier.fillMaxSize()) {
        JoystickWidget(
            joystick = joystick,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 40.dp, bottom = 40.dp)
        )
    }
}

@Composable
fun JoystickWidget(joystick: VirtualJoystick, modifier: Modifier = Modifier) {
    val baseRadius = joystick.maxRadius.dp
    val isActive   = joystick.isActive
    val knobOffset = joystick.getKnobOffset()

    Box(modifier = modifier) {
        // Base ring
        Canvas(
            modifier = Modifier
                .size(baseRadius * 2)
                .align(Alignment.Center)
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val r      = size.minDimension / 2f

            drawCircle(
                color  = Color(0x33FFFFFF),
                radius = r,
                center = center,
                style  = Stroke(width = 3f)
            )
            drawCircle(
                color  = Color(0x11FFFFFF),
                radius = r,
                center = center
            )

            // Knob
            if (isActive) {
                drawCircle(
                    color  = Color(0x884FC3F7),
                    radius = r * 0.45f,
                    center = Offset(
                        center.x + knobOffset.x,
                        center.y + knobOffset.y
                    )
                )
                drawCircle(
                    color  = Color(0xBB4FC3F7),
                    radius = r * 0.45f,
                    center = Offset(
                        center.x + knobOffset.x,
                        center.y + knobOffset.y
                    ),
                    style  = Stroke(width = 2f)
                )
            } else {
                drawCircle(
                    color  = Color(0x554FC3F7),
                    radius = r * 0.45f,
                    center = center
                )
            }
        }
    }
}

// ── Pause Menu ───────────────────────────────────────────────────────────────

@Composable
fun PauseMenuOverlay(
    onResume   : () -> Unit,
    onRestart  : () -> Unit,
    onMainMenu : () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xBB000000)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.width(300.dp),
            colors   = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
            shape    = RoundedCornerShape(20.dp),
            border   = BorderStroke(1.dp, Color(0xFF4FC3F7).copy(alpha = 0.4f))
        ) {
            Column(
                modifier            = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "PAUSED",
                    color      = Color(0xFF4FC3F7),
                    fontSize   = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 4.sp
                )

                Divider(color = Color(0xFF4FC3F7).copy(alpha = 0.2f))

                PauseButton("▶  Resume",  Color(0xFF4FC3F7), onResume)
                PauseButton("↺  Restart", Color(0xFFFFCA28), onRestart)
                PauseButton("⌂  Main Menu", Color(0xFFEF5350), onMainMenu)
            }
        }
    }
}

@Composable
private fun PauseButton(text: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors   = ButtonDefaults.buttonColors(
            containerColor = color.copy(alpha = 0.15f),
            contentColor   = color
        ),
        shape    = RoundedCornerShape(10.dp),
        border   = BorderStroke(1.dp, color.copy(alpha = 0.4f))
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

// ── Level-Up Overlay ─────────────────────────────────────────────────────────

@Composable
fun LevelUpOverlay(
    options     : List<LevelUpOption>,
    playerLevel : Int,
    onChoice    : (LevelUpOption) -> Unit
) {
    val enterAnim by rememberInfiniteTransition(label = "glow").animateFloat(
        initialValue   = 0.6f,
        targetValue    = 1f,
        animationSpec  = infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label          = "glowPulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier            = Modifier.padding(horizontal = 24.dp)
        ) {
            // Title
            Text(
                "LEVEL UP!",
                color       = Color(0xFFFFD740).copy(alpha = enterAnim),
                fontSize    = 36.sp,
                fontWeight  = FontWeight.ExtraBold,
                letterSpacing = 6.sp
            )
            Text(
                "Level $playerLevel — Choose an upgrade",
                color    = Color(0xFFBBBBBB),
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Option cards
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier              = Modifier.fillMaxWidth()
            ) {
                options.forEach { option ->
                    LevelUpCard(
                        option   = option,
                        onClick  = { onChoice(option) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun LevelUpCard(
    option  : LevelUpOption,
    onClick : () -> Unit,
    modifier: Modifier = Modifier
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue   = if (pressed) 0.96f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label         = "cardScale"
    )

    val accentColor = when (option.type) {
        UpgradeType.NEW_WEAPON     -> Color(0xFF4FC3F7)
        UpgradeType.WEAPON_UPGRADE -> Color(0xFFFFD740)
        UpgradeType.STAT           -> Color(0xFF66BB6A)
    }

    Card(
        onClick   = onClick,
        modifier  = modifier.scale(scale),
        colors    = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
        shape     = RoundedCornerShape(16.dp),
        border    = BorderStroke(2.dp, accentColor.copy(alpha = 0.6f))
    ) {
        Column(
            modifier            = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Icon
            Text(option.icon, fontSize = 40.sp)

            // Type badge
            val badgeText = when (option.type) {
                UpgradeType.NEW_WEAPON     -> "NEW"
                UpgradeType.WEAPON_UPGRADE -> "UPGRADE"
                UpgradeType.STAT           -> "STAT"
            }
            Text(
                badgeText,
                color      = accentColor,
                fontSize   = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp,
                modifier   = Modifier
                    .background(accentColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )

            // Title
            Text(
                option.title,
                color       = Color.White,
                fontSize    = 14.sp,
                fontWeight  = FontWeight.Bold,
                textAlign   = TextAlign.Center
            )

            // Description
            Text(
                option.description,
                color     = Color(0xFFAAAAAA),
                fontSize  = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ── Game Over Overlay ────────────────────────────────────────────────────────

@Composable
fun GameOverOverlay(
    gameState  : GameState,
    onRestart  : () -> Unit,
    onMainMenu : () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xDD000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier            = Modifier.padding(32.dp)
        ) {
            Text(
                "GAME OVER",
                color       = Color(0xFFEF5350),
                fontSize    = 42.sp,
                fontWeight  = FontWeight.ExtraBold,
                letterSpacing = 6.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Stats summary
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
                shape  = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFF4FC3F7).copy(alpha = 0.3f)),
                modifier = Modifier.width(320.dp)
            ) {
                Column(
                    modifier            = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatRow("⏱  Survived",  gameState.formattedTime)
                    StatRow("⚔️  Kills",     gameState.killCount.toString())
                    StatRow("⭐  Level",     gameState.playerLevel.toString())
                    StatRow("💰  Coins",     gameState.playerCoins.toString())
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick  = onRestart,
                modifier = Modifier.fillMaxWidth(0.6f),
                colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF4FC3F7)),
                shape    = RoundedCornerShape(12.dp)
            ) {
                Text("Play Again", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.Black)
            }

            OutlinedButton(
                onClick  = onMainMenu,
                modifier = Modifier.fillMaxWidth(0.6f),
                border   = BorderStroke(1.dp, Color(0xFFAAAAAA)),
                shape    = RoundedCornerShape(12.dp)
            ) {
                Text("Main Menu", color = Color(0xFFAAAAAA), fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color(0xFFBBBBBB), fontSize = 14.sp)
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}
