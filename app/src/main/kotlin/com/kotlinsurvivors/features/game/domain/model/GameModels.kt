package com.kotlinsurvivors.features.game.domain.model

import com.kotlinsurvivors.engine.ecs.systems.EventKind
import com.kotlinsurvivors.engine.rendering.RenderSnapshot
import com.kotlinsurvivors.engine.ecs.systems.GameEvent
import com.kotlinsurvivors.engine.ChestReward
import com.kotlinsurvivors.engine.ecs.components.WeaponType

/**
 * Immutable snapshot of the game state emitted by GameEngine each frame.
 *
 * KEY CHANGE: `world: World?` replaced by `renderSnapshot: RenderSnapshot`.
 * The World is now private to the engine thread. The UI thread receives
 * only a RenderSnapshot — a plain immutable list of value objects.
 * This eliminates the ConcurrentModificationException data race that
 * caused crashes when collecting XP (enemies removed while UI iterates).
 */
data class GameState(
    val isRunning        : Boolean        = false,
    val isPaused         : Boolean        = false,
    val isGameOver       : Boolean        = false,
    /** True while the level-up roulette is open (the game is frozen). */
    val isPendingLevelUp : Boolean        = false,
    /** Level-ups gained and not opened yet; the game keeps running while they wait. */
    val levelUpsWaiting  : Int            = 0,
    val levelUpOptions   : List<LevelUpOption> = emptyList(),
    /** Which of [levelUpOptions] the level-up roulette lands on. */
    val levelUpWinner    : Int            = 0,
    /** What the boss chest the player just opened holds; the game is frozen and the chest screen shown while this is set. */
    val chestReward      : ChestReward?   = null,
    /** The special wave or mini-boss that was just announced, shown as a banner for a few seconds; null otherwise. */
    val eventBanner      : EventKind?     = null,
    /** Kills in the current streak (shown from 3), its tier (0..4) and the share of the countdown left (0..1, in steps of 0.05). */
    val comboCount       : Int            = 0,
    val comboTier        : Int            = 0,
    val comboFraction    : Float          = 0f,

    // Time
    val elapsedTime      : Float          = 0f,

    // Player stats
    val playerHp         : Int            = 100,
    val playerMaxHp      : Int            = 100,
    val playerLevel      : Int            = 1,
    val playerXp         : Int            = 0,
    val playerXpToNext   : Int            = 25,
    val playerCoins      : Int            = 0,
    val playerX          : Float          = 0f,
    val playerY          : Float          = 0f,

    // World stats
    val enemyCount       : Int            = 0,
    val killCount        : Int            = 0,

    // Times the player has been hit this run. The UI reacts to changes (vibration, screen shake).
    val hitCount         : Int            = 0,

    // Run progress read by the achievement rules
    val bossKills        : Int            = 0,
    val weaponCount      : Int            = 0,
    val secondsWithoutHit: Int            = 0,

    // Immutable render data built by engine thread — safe for UI thread to read
    val renderSnapshot   : RenderSnapshot = RenderSnapshot(),

    // Events for one-shot UI feedback
    val events           : List<GameEvent> = emptyList()
) {
    val hpPercent: Float get() = if (playerMaxHp > 0) playerHp.toFloat() / playerMaxHp else 0f
    val xpPercent: Float get() = if (playerXpToNext > 0) playerXp.toFloat() / playerXpToNext else 0f
    val elapsedSeconds: Int get() = elapsedTime.toInt()
    val formattedTime: String get() = formatClock(elapsedSeconds)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GameState) return false
        return isRunning == other.isRunning &&
            isPaused == other.isPaused &&
            isGameOver == other.isGameOver &&
            isPendingLevelUp == other.isPendingLevelUp &&
            levelUpsWaiting == other.levelUpsWaiting &&
            levelUpOptions == other.levelUpOptions &&
            levelUpWinner == other.levelUpWinner &&
            chestReward == other.chestReward &&
            eventBanner == other.eventBanner &&
            comboCount == other.comboCount &&
            comboTier == other.comboTier &&
            comboFraction == other.comboFraction &&
            elapsedTime == other.elapsedTime &&
            playerHp == other.playerHp &&
            playerMaxHp == other.playerMaxHp &&
            playerLevel == other.playerLevel &&
            playerXp == other.playerXp &&
            playerXpToNext == other.playerXpToNext &&
            playerCoins == other.playerCoins &&
            enemyCount == other.enemyCount &&
            killCount == other.killCount &&
            hitCount == other.hitCount &&
            bossKills == other.bossKills &&
            weaponCount == other.weaponCount &&
            secondsWithoutHit == other.secondsWithoutHit
        // renderSnapshot intentionally excluded — changes every frame
    }

    override fun hashCode(): Int {
        var result = isRunning.hashCode()
        result = 31 * result + isPaused.hashCode()
        result = 31 * result + isGameOver.hashCode()
        result = 31 * result + playerHp
        result = 31 * result + playerLevel
        result = 31 * result + killCount
        result = 31 * result + enemyCount
        result = 31 * result + hitCount
        result = 31 * result + bossKills
        result = 31 * result + weaponCount
        return result
    }
}

/** Formats a number of seconds as mm:ss without going through String.format. */
fun formatClock(totalSeconds: Int): String {
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    return buildString(5) {
        if (mins < 10) append('0')
        append(mins)
        append(':')
        if (secs < 10) append('0')
        append(secs)
    }
}

enum class UpgradeType { WEAPON_UPGRADE, NEW_WEAPON, STAT }

data class LevelUpOption(
    val id          : String,
    val title       : String,
    val description : String,
    val type        : UpgradeType,
    val weaponType  : WeaponType? = null,
    val icon        : String = "star",
    val rarity      : com.kotlinsurvivors.engine.UpgradeRarity = com.kotlinsurvivors.engine.UpgradeRarity.COMMON,
    /** For a weapon upgrade: the level the weapon reaches. The screen needs it to word the title in any language. */
    val level       : Int = 0
)

data class RunResult(
    val survivalTime : Float,
    val killCount    : Int,
    val coinsEarned  : Int,
    val maxLevel     : Int,
    val timestamp    : Long = System.currentTimeMillis()
)

data class PlayerStats(
    val totalRuns       : Int   = 0,
    val totalKills      : Int   = 0,
    val totalCoins      : Int   = 0,
    val bestTime        : Float = 0f,
    val bestKills       : Int   = 0,
    val totalCoinsSpent : Int   = 0
)

data class PermanentUpgrade(
    val id           : String,
    val name         : String,
    val description  : String,
    val cost         : Int,
    val maxLevel     : Int,
    val currentLevel : Int = 0,
    val effectPerLevel: String
) {
    val isMaxed      : Boolean get() = currentLevel >= maxLevel
    val nextCost     : Int     get() = cost * (currentLevel + 1)
    val isAffordable : Boolean get() = currentLevel < maxLevel
}

object PermanentUpgrades {
    val all = listOf(
        PermanentUpgrade("perm_hp",       "Iron Body",   "+20 Max HP per level",        50,  10, effectPerLevel = "+20 HP"),
        PermanentUpgrade("perm_speed",    "Fleet Foot",  "+5% Move Speed per level",    60,  10, effectPerLevel = "+5% Spd"),
        PermanentUpgrade("perm_damage",   "Iron Fist",   "+10% Damage per level",       80,  10, effectPerLevel = "+10% DMG"),
        PermanentUpgrade("perm_cooldown", "Tempo",       "-5% Cooldowns per level",     70,  10, effectPerLevel = "-5% CD"),
        PermanentUpgrade("perm_area",     "Expanse",     "+8% Area per level",          65,  10, effectPerLevel = "+8% Area"),
        PermanentUpgrade("perm_armor",    "Plate Mail",  "+1 Armor per level",          90,  5,  effectPerLevel = "+1 Armor"),
        PermanentUpgrade("perm_regen",    "Vitality",    "+0.5 HP Regen per level",     75,  8,  effectPerLevel = "+0.5 Reg"),
        PermanentUpgrade("perm_magnet",   "Magnetism",   "+20% Pickup Range per level", 55,  10, effectPerLevel = "+20% Mag"),
        PermanentUpgrade("perm_luck",     "Fortune",     "+5% Coin Drop per level",     100, 5,  effectPerLevel = "+5% Luck"),
        PermanentUpgrade("perm_xp",       "Scholar",     "+10% XP Gain per level",      85,  10, effectPerLevel = "+10% XP"),
    )
}

data class Achievement(
    val id         : String,
    val name       : String,
    val description: String,
    val icon       : String,
    val isUnlocked : Boolean = false,
    val unlockedAt : Long    = 0L
)

object AchievementDefinitions {
    val all = listOf(
        Achievement("ach_first_kill",   "First Blood",   "Kill your first enemy",           "blood"),
        Achievement("ach_survive_1",    "Survivor",      "Survive for 1 minute",            "clock"),
        Achievement("ach_survive_5",    "Veteran",       "Survive for 5 minutes",           "medal"),
        Achievement("ach_survive_10",   "Legend",        "Survive for 10 minutes",          "trophy"),
        Achievement("ach_survive_20",   "Immortal",      "Survive for 20 minutes",          "crown"),
        Achievement("ach_kill_100",     "Centurion",     "Kill 100 enemies in one run",     "sword"),
        Achievement("ach_kill_500",     "Slaughterer",   "Kill 500 enemies in one run",     "skull"),
        Achievement("ach_kill_1000",    "One-Thousand",  "Kill 1000 enemies in one run",     "fire"),
        Achievement("ach_level_10",     "Seasoned",      "Reach level 10",                  "star"),
        Achievement("ach_level_20",     "Master",        "Reach level 20",                  "sparkle"),
        Achievement("ach_boss_kill",    "Boss Slayer",   "Kill a boss",                     "demon"),
        Achievement("ach_max_weapons",  "Armory",        "Equip 6 weapons simultaneously",  "knife"),
        Achievement("ach_coins_500",    "Rich",          "Collect 500 coins in one run",    "coin"),
        Achievement("ach_no_damage_60", "Untouchable",   "Survive 60s without being hit",   "shield"),
    )
}
