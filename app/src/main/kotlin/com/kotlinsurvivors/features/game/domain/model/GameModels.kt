package com.kotlinsurvivors.features.game.domain.model

import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.systems.GameEvent
import com.kotlinsurvivors.engine.ecs.components.WeaponType

/**
 * Immutable snapshot of the game state emitted by GameEngine each frame.
 * The Compose UI collects this and renders accordingly.
 *
 * NOTE: [world] is intentionally excluded from equals/hashCode via custom
 * implementation to prevent unnecessary Compose recompositions — the World
 * is a mutable reference whose content changes every frame.
 */
data class GameState(
    val isRunning        : Boolean = false,
    val isPaused         : Boolean = false,
    val isGameOver       : Boolean = false,
    val isPendingLevelUp : Boolean = false,
    val levelUpOptions   : List<LevelUpOption> = emptyList(),

    // Time
    val elapsedTime      : Float = 0f,

    // Player stats
    val playerHp         : Int = 100,
    val playerMaxHp      : Int = 100,
    val playerLevel      : Int = 1,
    val playerXp         : Int = 0,
    val playerXpToNext   : Int = 10,
    val playerCoins      : Int = 0,
    val playerX          : Float = 0f,
    val playerY          : Float = 0f,

    // World stats
    val enemyCount       : Int = 0,
    val killCount        : Int = 0,

    // ECS world reference for rendering (not compared in equals)
    val world            : World? = null,

    // Events for one-shot UI feedback (sounds, screenshake, etc.)
    val events           : List<GameEvent> = emptyList()
) {
    val hpPercent: Float get() = if (playerMaxHp > 0) playerHp.toFloat() / playerMaxHp else 0f
    val xpPercent: Float get() = if (playerXpToNext > 0) playerXp.toFloat() / playerXpToNext else 0f
    val formattedTime: String get() {
        val mins = (elapsedTime / 60).toInt()
        val secs = (elapsedTime % 60).toInt()
        return "%02d:%02d".format(mins, secs)
    }

    /**
     * Custom equals that excludes [world] from comparison.
     * Without this, every frame emit causes full Compose recomposition
     * because the World reference changes even when game state is identical.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GameState) return false
        return isRunning == other.isRunning &&
            isPaused == other.isPaused &&
            isGameOver == other.isGameOver &&
            isPendingLevelUp == other.isPendingLevelUp &&
            levelUpOptions == other.levelUpOptions &&
            elapsedTime == other.elapsedTime &&
            playerHp == other.playerHp &&
            playerMaxHp == other.playerMaxHp &&
            playerLevel == other.playerLevel &&
            playerXp == other.playerXp &&
            playerXpToNext == other.playerXpToNext &&
            playerCoins == other.playerCoins &&
            enemyCount == other.enemyCount &&
            killCount == other.killCount
        // world intentionally excluded
    }

    override fun hashCode(): Int {
        var result = isRunning.hashCode()
        result = 31 * result + isPaused.hashCode()
        result = 31 * result + isGameOver.hashCode()
        result = 31 * result + playerHp
        result = 31 * result + playerLevel
        result = 31 * result + killCount
        result = 31 * result + enemyCount
        return result
    }
}

// ── Level-Up upgrade model ──────────────────────────────────────────────────

enum class UpgradeType { WEAPON_UPGRADE, NEW_WEAPON, STAT }

data class LevelUpOption(
    val id          : String,
    val title       : String,
    val description : String,
    val type        : UpgradeType,
    val weaponType  : WeaponType? = null,
    val icon        : String = "⭐",
    val rarity      : com.kotlinsurvivors.engine.UpgradeRarity = com.kotlinsurvivors.engine.UpgradeRarity.COMMON
)

// ── Persistent progression models ──────────────────────────────────────────

data class RunResult(
    val survivalTime     : Float,
    val killCount        : Int,
    val coinsEarned      : Int,
    val maxLevel         : Int,
    val timestamp        : Long = System.currentTimeMillis()
)

data class PlayerStats(
    val totalRuns        : Int = 0,
    val totalKills       : Int = 0,
    val totalCoins       : Int = 0,
    val bestTime         : Float = 0f,
    val bestKills        : Int = 0,
    val totalCoinsSpent  : Int = 0
)

data class PermanentUpgrade(
    val id              : String,
    val name            : String,
    val description     : String,
    val cost            : Int,
    val maxLevel        : Int,
    val currentLevel    : Int = 0,
    val effectPerLevel  : String
) {
    val isMaxed     : Boolean get() = currentLevel >= maxLevel
    val nextCost    : Int     get() = cost * (currentLevel + 1)
    val isAffordable: Boolean get() = currentLevel < maxLevel
}

/** All permanent upgrades available in the shop. */
object PermanentUpgrades {
    val all = listOf(
        PermanentUpgrade("perm_hp",       "Iron Body",    "+20 Max HP per level",         50,  10, effectPerLevel = "+20 HP"),
        PermanentUpgrade("perm_speed",    "Fleet Foot",   "+5% Move Speed per level",     60,  10, effectPerLevel = "+5% Spd"),
        PermanentUpgrade("perm_damage",   "Iron Fist",    "+10% Damage per level",        80,  10, effectPerLevel = "+10% DMG"),
        PermanentUpgrade("perm_cooldown", "Tempo",        "-5% Cooldowns per level",      70,  10, effectPerLevel = "-5% CD"),
        PermanentUpgrade("perm_area",     "Expanse",      "+8% Area per level",           65,  10, effectPerLevel = "+8% Area"),
        PermanentUpgrade("perm_armor",    "Plate Mail",   "+1 Armor per level",           90,  5,  effectPerLevel = "+1 Armor"),
        PermanentUpgrade("perm_regen",    "Vitality",     "+0.5 HP Regen per level",      75,  8,  effectPerLevel = "+0.5 Reg"),
        PermanentUpgrade("perm_magnet",   "Magnetism",    "+20% Pickup Range per level",  55,  10, effectPerLevel = "+20% Mag"),
        PermanentUpgrade("perm_luck",     "Fortune",      "+5% Coin Drop per level",      100, 5,  effectPerLevel = "+5% Luck"),
        PermanentUpgrade("perm_xp",       "Scholar",      "+10% XP Gain per level",       85,  10, effectPerLevel = "+10% XP"),
    )
}

// ── Achievement model ────────────────────────────────────────────────────────

data class Achievement(
    val id          : String,
    val name        : String,
    val description : String,
    val icon        : String,
    val isUnlocked  : Boolean = false,
    val unlockedAt  : Long    = 0L
)

object AchievementDefinitions {
    val all = listOf(
        Achievement("ach_first_kill",   "First Blood",      "Kill your first enemy",            "🩸"),
        Achievement("ach_survive_1",    "Survivor",         "Survive for 1 minute",             "⏱️"),
        Achievement("ach_survive_5",    "Veteran",          "Survive for 5 minutes",            "🏅"),
        Achievement("ach_survive_10",   "Legend",           "Survive for 10 minutes",           "🏆"),
        Achievement("ach_survive_20",   "Immortal",         "Survive for 20 minutes",           "👑"),
        Achievement("ach_kill_100",     "Centurion",        "Kill 100 enemies in one run",      "⚔️"),
        Achievement("ach_kill_500",     "Slaughterer",      "Kill 500 enemies in one run",      "💀"),
        Achievement("ach_kill_1000",    "One-Thousand",     "Kill 1000 enemies",                "🔥"),
        Achievement("ach_level_10",     "Seasoned",         "Reach level 10",                   "⭐"),
        Achievement("ach_level_20",     "Master",           "Reach level 20",                   "🌟"),
        Achievement("ach_boss_kill",    "Boss Slayer",      "Kill a boss",                      "👹"),
        Achievement("ach_max_weapons",  "Armory",           "Equip 6 weapons simultaneously",   "🗡️"),
        Achievement("ach_coins_500",    "Rich",             "Collect 500 coins total",          "💰"),
        Achievement("ach_no_damage_60", "Untouchable",      "Survive 60s without being hit",    "🛡️"),
    )
}
