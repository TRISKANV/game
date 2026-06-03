package com.kotlinsurvivors.core.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ── Entities ────────────────────────────────────────────────────────────────

@Entity(tableName = "run_results")
data class RunResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val survivalTime  : Float,
    val killCount     : Int,
    val coinsEarned   : Int,
    val maxLevel      : Int,
    val timestamp     : Long
)

@Entity(tableName = "permanent_upgrades")
data class PermanentUpgradeEntity(
    @PrimaryKey val id     : String,
    val currentLevel       : Int = 0
)

@Entity(tableName = "achievements")
data class AchievementEntity(
    @PrimaryKey val id     : String,
    val isUnlocked         : Boolean = false,
    val unlockedAt         : Long    = 0L
)

@Entity(tableName = "player_stats")
data class PlayerStatsEntity(
    @PrimaryKey val id     : Int = 1,
    val totalRuns          : Int = 0,
    val totalKills         : Int = 0,
    val totalCoins         : Int = 0,
    val bestTime           : Float = 0f,
    val bestKills          : Int = 0,
    val totalCoinsSpent    : Int = 0
)

// ── DAOs ─────────────────────────────────────────────────────────────────────

@Dao
interface RunResultDao {
    @Insert
    suspend fun insert(entity: RunResultEntity)

    @Query("SELECT * FROM run_results ORDER BY survivalTime DESC LIMIT 20")
    fun getTopRuns(): Flow<List<RunResultEntity>>

    @Query("SELECT * FROM run_results ORDER BY killCount DESC LIMIT 20")
    fun getTopKillRuns(): Flow<List<RunResultEntity>>

    @Query("SELECT COUNT(*) FROM run_results")
    suspend fun getCount(): Int
}

@Dao
interface PermanentUpgradeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PermanentUpgradeEntity)

    @Query("SELECT * FROM permanent_upgrades")
    suspend fun getAll(): List<PermanentUpgradeEntity>

    @Query("SELECT currentLevel FROM permanent_upgrades WHERE id = :upgradeId")
    suspend fun getLevel(upgradeId: String): Int?
}

@Dao
interface AchievementDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AchievementEntity)

    @Query("SELECT * FROM achievements")
    fun getAll(): Flow<List<AchievementEntity>>

    @Query("SELECT * FROM achievements WHERE isUnlocked = 1")
    suspend fun getUnlocked(): List<AchievementEntity>
}

@Dao
interface PlayerStatsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlayerStatsEntity)

    @Query("SELECT * FROM player_stats WHERE id = 1")
    suspend fun get(): PlayerStatsEntity?

    @Query("UPDATE player_stats SET totalRuns = totalRuns + 1, totalKills = totalKills + :kills, totalCoins = totalCoins + :coins WHERE id = 1")
    suspend fun addRun(kills: Int, coins: Int)
}

// ── Database ─────────────────────────────────────────────────────────────────

@Database(
    entities  = [
        RunResultEntity::class,
        PermanentUpgradeEntity::class,
        AchievementEntity::class,
        PlayerStatsEntity::class
    ],
    version   = 1,
    exportSchema = true
)
abstract class GameDatabase : RoomDatabase() {
    abstract fun runResultDao()       : RunResultDao
    abstract fun permanentUpgradeDao(): PermanentUpgradeDao
    abstract fun achievementDao()     : AchievementDao
    abstract fun playerStatsDao()     : PlayerStatsDao
}
