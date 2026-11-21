package com.kotlinsurvivors.core.audio

/**
 * Every sound effect of the game. [file] is the name of the .ogg in assets/sfx;
 * replacing a sound later means dropping a new file with the same name over it.
 *
 * [minGapMs] is the shortest time allowed between two plays of the same sound. With
 * dozens of enemies dying per second, without it the same effect would pile up into noise.
 * [volume] is the loudness relative to the others (0..1).
 */
enum class Sound(val file: String, val minGapMs: Long = 0L, val volume: Float = 1f) {
    // Weapons
    SHOT_PACKET   ("shot_packet",    60,  0.55f),
    SHOT_SYRINGE  ("shot_syringe",   60,  0.55f),
    SHOT_CROSSHAIR("shot_crosshair", 60,  0.55f),
    SHOT_FIREWALL ("shot_firewall",  120, 0.6f),
    SHOT_USB      ("shot_usb",       80,  0.55f),
    SHOT_ZAP      ("shot_zap",       80,  0.6f),
    SHOT_HONEYPOT ("shot_honeypot",  100, 0.55f),
    SHOT_WHIP     ("shot_whip",      80,  0.8f),
    SHOT_DDOS     ("shot_ddos",      200, 0.9f),
    SHOT_ZERO_DAY ("shot_zeroday",   150, 0.8f),
    BOMB_DROP     ("bomb_drop",      100, 0.7f),
    BOMB_BLAST    ("bomb_blast",     120, 0.9f),

    // Enemies and the player
    ENEMY_DIE     ("enemy_die",      45,  0.5f),
    ENEMY_BLAST   ("enemy_blast",    100, 0.8f),
    BOSS_APPEAR   ("boss_appear",    0,   1f),
    BOSS_DIE      ("boss_die",       0,   1f),
    PLAYER_HURT   ("player_hurt",    150, 0.9f),

    // Events
    EVENT_WAVE    ("event_wave",     0,   0.9f),
    EVENT_MINIBOSS("event_miniboss", 0,   1f),
    WAVE_CLEAR    ("wave_clear",     0,   0.9f),

    // Pickups
    PICKUP_XP     ("pickup_xp",      40,  0.4f),
    PICKUP_COIN   ("pickup_coin",    60,  0.5f),
    PICKUP_HEAL   ("pickup_heal",    100, 0.8f),
    PICKUP_MAGNET ("pickup_magnet",  100, 0.8f),

    // Level up, roulette, chest
    LEVEL_READY   ("level_ready",    300, 0.9f),
    ROULETTE_TICK ("roulette_tick",  30,  0.6f),
    ROULETTE_WIN  ("roulette_win",   0,   0.9f),
    LEVEL_CHOOSE  ("level_choose",   0,   0.8f),
    CHEST_APPEAR  ("chest_appear",   0,   0.9f),
    CHEST_OPEN    ("chest_open",     0,   1f),

    // Game flow and interface
    GAME_OVER     ("game_over",      0,   1f),
    ACHIEVEMENT   ("achievement",    0,   0.9f),
    UI_CLICK      ("ui_click",       40,  0.7f),
    UI_BACK       ("ui_back",        40,  0.7f),
    UI_BUY        ("ui_buy",         80,  0.8f),
    UI_TOGGLE     ("ui_toggle",      40,  0.7f);
}

/** Where the game asks for a sound to be played. Safe to call from any thread. */
interface SoundEffects {
    fun play(sound: Sound)

    /** Plays nothing: for tests and for anything that runs without audio. */
    object None : SoundEffects {
        override fun play(sound: Sound) = Unit
    }
}
