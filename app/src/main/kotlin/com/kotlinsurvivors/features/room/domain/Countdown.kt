package com.kotlinsurvivors.features.room.domain

/** The two timers of the ransom screen, and how they are written ("DD:HH:MM:SS"). */
object Countdown {
    /** Seconds on the clock when the infection starts: 2 days 23:57:55 and 6 days 23:57:55. */
    const val PAYMENT_DOUBLES_SECONDS = 2L * 86_400 + 23 * 3_600 + 57 * 60 + 55
    const val FILES_LOST_SECONDS      = 6L * 86_400 + 23 * 3_600 + 57 * 60 + 55

    /** "02:23:57:55" for 259075 seconds; never negative. */
    fun format(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val d = s / 86_400
        val h = s % 86_400 / 3_600
        val m = s % 3_600 / 60
        val sec = s % 60
        return "%02d:%02d:%02d:%02d".format(d, h, m, sec)
    }

    /** "71:57:55": the same time as hours, minutes and seconds, short enough for the small monitor. */
    fun formatHours(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        return "%02d:%02d:%02d".format(s / 3_600, s % 3_600 / 60, s % 60)
    }
}
