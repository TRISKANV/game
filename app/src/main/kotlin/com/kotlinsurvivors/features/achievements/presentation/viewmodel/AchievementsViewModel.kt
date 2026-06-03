package com.kotlinsurvivors.features.achievements.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotlinsurvivors.core.data.local.GameDatabase
import com.kotlinsurvivors.features.game.domain.model.Achievement
import com.kotlinsurvivors.features.game.domain.model.AchievementDefinitions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AchievementsViewModel @Inject constructor(
    db: GameDatabase
) : ViewModel() {

    val achievements = db.achievementDao().getAll()
        .map { storedList ->
            val storedMap = storedList.associateBy { it.id }
            AchievementDefinitions.all.map { def ->
                val stored = storedMap[def.id]
                def.copy(
                    isUnlocked = stored?.isUnlocked ?: false,
                    unlockedAt = stored?.unlockedAt ?: 0L
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AchievementDefinitions.all)
}
