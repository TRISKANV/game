package com.kotlinsurvivors.features.shop.presentation.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kotlinsurvivors.features.game.domain.model.PermanentUpgrade
import com.kotlinsurvivors.features.shop.presentation.viewmodel.ShopViewModel

/**
 * ShopScreen
 *
 * Lets the player spend accumulated coins on permanent stat upgrades
 * that persist across all future runs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShopScreen(
    onBack: () -> Unit,
    viewModel: ShopViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "UPGRADE SHOP",
                        fontWeight    = FontWeight.ExtraBold,
                        letterSpacing = 3.sp,
                        color         = Color(0xFFFFD740)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Back", tint = Color(0xFFAAAAAA))
                    }
                },
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier          = Modifier.padding(end = 16.dp)
                    ) {
                        Text("💰", fontSize = 18.sp)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${uiState.availableCoins}",
                            color      = Color(0xFFFFD740),
                            fontWeight = FontWeight.Bold,
                            fontSize   = 18.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0A0A14)
                )
            )
        },
        containerColor = Color(0xFF0A0A14)
    ) { paddingValues ->

        if (uiState.upgrades.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color(0xFFFFD740))
            }
            return@Scaffold
        }

        LazyVerticalGrid(
            columns             = GridCells.Fixed(2),
            modifier            = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding      = PaddingValues(vertical = 16.dp)
        ) {
            items(uiState.upgrades, key = { it.id }) { upgrade ->
                ShopUpgradeCard(
                    upgrade    = upgrade,
                    coins      = uiState.availableCoins,
                    onBuy      = { viewModel.purchaseUpgrade(upgrade) }
                )
            }
        }
    }
}

@Composable
private fun ShopUpgradeCard(
    upgrade : PermanentUpgrade,
    coins   : Int,
    onBuy   : () -> Unit
) {
    val canAfford = coins >= upgrade.nextCost && !upgrade.isMaxed
    val progress  = if (upgrade.maxLevel > 0) upgrade.currentLevel.toFloat() / upgrade.maxLevel else 0f

    val borderColor = when {
        upgrade.isMaxed -> Color(0xFFFFD740)
        canAfford       -> Color(0xFF4FC3F7).copy(alpha = 0.6f)
        else            -> Color(0xFF333355)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF12121E)),
        shape    = RoundedCornerShape(14.dp),
        border   = BorderStroke(1.5.dp, borderColor)
    ) {
        Column(
            modifier            = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header row
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.Top
            ) {
                Text(upgrade.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                if (upgrade.isMaxed) {
                    Text("MAX", color = Color(0xFFFFD740), fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier   = Modifier
                            .background(Color(0xFFFFD740).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                } else {
                    Text(
                        "Lv ${upgrade.currentLevel}/${upgrade.maxLevel}",
                        color    = Color(0xFF888888),
                        fontSize = 11.sp
                    )
                }
            }

            // Description
            Text(
                upgrade.description,
                color    = Color(0xFF888888),
                fontSize = 11.sp,
                lineHeight = 15.sp
            )

            // Per-level effect
            Text(
                upgrade.effectPerLevel,
                color      = Color(0xFF4FC3F7),
                fontSize   = 11.sp,
                fontWeight = FontWeight.SemiBold
            )

            // Progress bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF222233))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF4FC3F7), Color(0xFFCE93D8))
                            )
                        )
                )
            }

            // Buy button
            if (!upgrade.isMaxed) {
                Button(
                    onClick  = onBuy,
                    enabled  = canAfford,
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = if (canAfford) Color(0xFFFFD740).copy(alpha = 0.2f) else Color(0xFF1A1A2E),
                        contentColor   = if (canAfford) Color(0xFFFFD740) else Color(0xFF555555),
                        disabledContainerColor = Color(0xFF1A1A2E),
                        disabledContentColor   = Color(0xFF555555)
                    ),
                    shape    = RoundedCornerShape(8.dp),
                    border   = BorderStroke(1.dp, if (canAfford) Color(0xFFFFD740).copy(alpha = 0.4f) else Color(0xFF333333))
                ) {
                    Text(
                        "💰 ${upgrade.nextCost}",
                        fontSize   = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Box(
                    modifier         = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .background(Color(0xFFFFD740).copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFFFFD740).copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✦ MAXED ✦", color = Color(0xFFFFD740), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}
