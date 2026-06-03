package com.kotlinsurvivors.features.achievements.presentation.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kotlinsurvivors.features.achievements.presentation.viewmodel.AchievementsViewModel
import com.kotlinsurvivors.features.game.domain.model.Achievement

/**
 * AchievementsScreen
 *
 * Displays all achievements with lock/unlock state.
 * Unlocked achievements show their timestamp.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AchievementsScreen(
    onBack: () -> Unit,
    viewModel: AchievementsViewModel = hiltViewModel()
) {
    val achievements by viewModel.achievements.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "ACHIEVEMENTS",
                        fontWeight    = FontWeight.ExtraBold,
                        letterSpacing = 3.sp,
                        color         = Color(0xFF66BB6A)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Back", tint = Color(0xFFAAAAAA))
                    }
                },
                actions = {
                    val unlocked = achievements.count { it.isUnlocked }
                    Text(
                        "$unlocked / ${achievements.size}",
                        color    = Color(0xFF66BB6A),
                        modifier = Modifier.padding(end = 16.dp),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0A14))
            )
        },
        containerColor = Color(0xFF0A0A14)
    ) { padding ->
        LazyColumn(
            modifier       = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            items(achievements, key = { it.id }) { achievement ->
                AchievementCard(achievement)
            }
        }
    }
}

@Composable
private fun AchievementCard(achievement: Achievement) {
    val alpha       = if (achievement.isUnlocked) 1f else 0.4f
    val borderColor = if (achievement.isUnlocked) Color(0xFF66BB6A).copy(alpha = 0.7f)
                      else Color(0xFF333355)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = if (achievement.isUnlocked) Color(0xFF121E12) else Color(0xFF12121E)
        ),
        shape  = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier          = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Icon with lock overlay
            Box(
                modifier         = Modifier
                    .size(52.dp)
                    .background(
                        if (achievement.isUnlocked) Color(0xFF66BB6A).copy(alpha = 0.15f)
                        else Color(0xFF1A1A2E),
                        RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (achievement.isUnlocked) achievement.icon else "🔒",
                    fontSize = 26.sp
                )
            }

            // Text column
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    achievement.name,
                    color      = Color.White.copy(alpha = alpha),
                    fontWeight = FontWeight.Bold,
                    fontSize   = 14.sp
                )
                Text(
                    achievement.description,
                    color    = Color(0xFF888888).copy(alpha = alpha),
                    fontSize = 12.sp
                )
                if (achievement.isUnlocked && achievement.unlockedAt > 0L) {
                    val date = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
                        .format(java.util.Date(achievement.unlockedAt))
                    Text(
                        "Unlocked $date",
                        color    = Color(0xFF66BB6A).copy(alpha = 0.8f),
                        fontSize = 10.sp
                    )
                }
            }

            // Checkmark
            if (achievement.isUnlocked) {
                Text("✓", color = Color(0xFF66BB6A), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}
