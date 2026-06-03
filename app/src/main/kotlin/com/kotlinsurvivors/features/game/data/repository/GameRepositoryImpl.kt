package com.kotlinsurvivors.features.game.data.repository

import com.kotlinsurvivors.core.data.local.GameDatabase
import com.kotlinsurvivors.core.data.local.PlayerStatsEntity
import com.kotlinsurvivors.core.data.local.RunResultEntity
import com.kotlinsurvivors.features.game.domain.model.PlayerStats
import com.kotlinsurvivors.features.game.domain.model.RunResult
import com.kotlinsurvivors.features.game.domain.repository.GameRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class GameRepositoryImpl @Inject constructor(
    private val db: GameDatabase
) : GameRepository {

    override suspend fun saveRunResult(result: RunResult) {
        db.runResultDao().insert(
            RunResultEntity(
                survivalTime = result.survivalTime,
                killCount    = result.killCount,
                coinsEarned  = result.coinsEarned,
                maxLevel     = result.maxLevel,
                timestamp    = result.timestamp
            )
        )
        // Update global stats
        val existing = db.playerStatsDao().get()
        val updated  = if (existing == null) {
            PlayerStatsEntity(
                totalRuns   = 1,
                totalKills  = result.killCount,
                totalCoins  = result.coinsEarned,
                bestTime    = result.survivalTime,
                bestKills   = result.killCount
            )
        } else {
            existing.copy(
                totalRuns  = existing.totalRuns + 1,
                totalKills = existing.totalKills + result.killCount,
                totalCoins = existing.totalCoins + result.coinsEarned,
                bestTime   = maxOf(existing.bestTime, result.survivalTime),
                bestKills  = maxOf(existing.bestKills, result.killCount)
            )
        }
        db.playerStatsDao().upsert(updated)
    }

    override fun getTopRunsByTime(): Flow<List<RunResult>> =
        db.runResultDao().getTopRuns().map { list ->
            list.map { it.toDomain() }
        }

    override fun getTopRunsByKills(): Flow<List<RunResult>> =
        db.runResultDao().getTopKillRuns().map { list ->
            list.map { it.toDomain() }
        }

    override suspend fun getPlayerStats(): PlayerStats {
        val e = db.playerStatsDao().get() ?: return PlayerStats()
        return PlayerStats(
            totalRuns        = e.totalRuns,
            totalKills       = e.totalKills,
            totalCoins       = e.totalCoins,
            bestTime         = e.bestTime,
            bestKills        = e.bestKills,
            totalCoinsSpent  = e.totalCoinsSpent
        )
    }

    private fun RunResultEntity.toDomain() = RunResult(
        survivalTime = survivalTime,
        killCount    = killCount,
        coinsEarned  = coinsEarned,
        maxLevel     = maxLevel,
        timestamp    = timestamp
    )
}
