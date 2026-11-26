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
    DOG_BARK      ("dog_bark",       800, 0.9f),
    PLAYER_HURT   ("player_hurt",    150, 0.9f),

    // Events
    EVENT_WAVE    ("event_wave",     0,   0.9f),
    EVENT_MINIBOSS("event_miniboss", 0,   1f),
    WAVE_CLEAR    ("wave_clear",     0,   0.9f),

    // The siege of the police station
    SIREN         ("siren",          0,   0.7f),
    SHUTTER       ("shutter",        0,   1f),
    CHIEF_WAKE    ("chief_wake",     0,   1f),
    KEY_GET       ("key_get",        0,   1f),
    SHIELD_BREAK  ("shield_break",   0,   1f),

    // Cars
    CAR_PICK      ("car_pick",       0,   0.6f),
    CAR_DOOR      ("car_door",       0,   0.9f),
    CAR_CRASH     ("car_crash",      60,  1f),
    CAR_WRECK     ("car_wreck",      0,   1f),
    CAR_ENGINE    ("car_engine",     0,   0.5f),

    // Kill streak: one blip per tier, each higher than the last
    COMBO_1       ("combo_1",        0,   0.7f),
    COMBO_2       ("combo_2",        0,   0.75f),
    COMBO_3       ("combo_3",        0,   0.8f),
    COMBO_4       ("combo_4",        0,   0.9f),

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
    UI_TOGGLE     ("ui_toggle",      40,  0.7f),

    // Chapter 0, the room: ambience loops (see SoundEffects.startLoop), the phone and the computer
    AMB_FAN       ("amb_fan",        0,   0.35f),
    AMB_RAIN      ("amb_rain",       0,   0.4f),
    AMB_MUSIC     ("amb_music",      0,   0.3f),
    AMB_KEYS      ("amb_keys",       0,   0.5f),
    AMB_STATIC    ("amb_static",     0,   0.35f),
    PHONE_RING    ("phone_ring",     0,   1f),
    PHONE_ANSWER  ("phone_answer",   0,   0.8f),
    PHONE_HANGUP  ("phone_hangup",   0,   0.8f),
    NOTIFY_PING   ("notify_ping",    0,   0.9f),
    GLITCH        ("glitch",         0,   0.8f),
    DOOR_OPEN     ("door_open",      0,   0.8f),
    DIALOGUE_BLIP ("dialogue_blip",  35,  0.3f);
}

/** Where the game asks for a sound to be played. Safe to call from any thread. */
interface SoundEffects {
    fun play(sound: Sound)

    /**
     * Starts [sound] repeating until [stopLoop] is called with the returned handle (-1 when nothing
     * could be started, which [stopLoop] accepts). For ambience and the ringing phone.
     */
    fun startLoop(sound: Sound): Int = -1

    fun stopLoop(handle: Int) = Unit

    /** Scales the volume of a running loop (1 = its own volume), e.g. to lower the ambience during a call. */
    fun setLoopVolume(handle: Int, factor: Float) = Unit

    /** Plays nothing: for tests and for anything that runs without audio. */
    object None : SoundEffects {
        override fun play(sound: Sound) = Unit
    }
}
