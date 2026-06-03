package com.kotlinsurvivors.features.game.domain.repository

import com.kotlinsurvivors.features.game.domain.model.RunResult
import com.kotlinsurvivors.features.game.domain.model.PlayerStats
import kotlinx.coroutines.flow.Flow

interface GameRepository {
    suspend fun saveRunResult(result: RunResult)
    fun getTopRunsByTime(): Flow<List<RunResult>>
    fun getTopRunsByKills(): Flow<List<RunResult>>
    suspend fun getPlayerStats(): PlayerStats
}
