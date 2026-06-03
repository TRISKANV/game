package com.kotlinsurvivors.features.shop.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotlinsurvivors.core.data.local.GameDatabase
import com.kotlinsurvivors.core.data.local.PermanentUpgradeEntity
import com.kotlinsurvivors.core.data.local.PlayerStatsEntity
import com.kotlinsurvivors.features.game.domain.model.PermanentUpgrade
import com.kotlinsurvivors.features.game.domain.model.PermanentUpgrades
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ShopUiState(
    val upgrades       : List<PermanentUpgrade> = emptyList(),
    val availableCoins : Int                    = 0,
    val isLoading      : Boolean                = true,
    val purchaseMessage: String?                = null
)

@HiltViewModel
class ShopViewModel @Inject constructor(
    private val db: GameDatabase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ShopUiState())
    val uiState: StateFlow<ShopUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            val storedLevels = db.permanentUpgradeDao().getAll()
                .associateBy { it.id }

            val upgrades = PermanentUpgrades.all.map { def ->
                val stored = storedLevels[def.id]
                def.copy(currentLevel = stored?.currentLevel ?: 0)
            }

            val stats = db.playerStatsDao().get()
            val spent = storedLevels.values.sumOf { entity ->
                val def = PermanentUpgrades.all.find { it.id == entity.id }
                if (def != null) {
                    (1..entity.currentLevel).sumOf { lvl -> def.cost * lvl }
                } else 0
            }
            val totalCoins  = stats?.totalCoins ?: 0
            val available   = (totalCoins - spent).coerceAtLeast(0)

            _uiState.value = ShopUiState(
                upgrades       = upgrades,
                availableCoins = available,
                isLoading      = false
            )
        }
    }

    fun purchaseUpgrade(upgrade: PermanentUpgrade) {
        val state = _uiState.value
        if (state.availableCoins < upgrade.nextCost || upgrade.isMaxed) return

        viewModelScope.launch {
            val newLevel = upgrade.currentLevel + 1

            // Persist new level
            db.permanentUpgradeDao().upsert(
                PermanentUpgradeEntity(id = upgrade.id, currentLevel = newLevel)
            )

            // Deduct coins by tracking total spent in player stats
            val stats = db.playerStatsDao().get() ?: PlayerStatsEntity()
            db.playerStatsDao().upsert(
                stats.copy(totalCoinsSpent = stats.totalCoinsSpent + upgrade.nextCost)
            )

            // Refresh state
            loadData()
        }
    }
}
