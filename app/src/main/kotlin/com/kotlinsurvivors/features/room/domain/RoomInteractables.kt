package com.kotlinsurvivors.features.room.domain

import com.kotlinsurvivors.features.room.domain.RoomLayout.Box

/** Things in the room the protagonist can use. */
enum class Interactable { COMPUTER, DESK, WINDOW, PHONE, DOOR }

/**
 * Where each [Interactable] can be used. The zones are patches of floor next to the object
 * (never inside the furniture), in pixels of the room image like [RoomLayout]. They live apart
 * from RoomLayout because that file is generated together with the room picture.
 */
object RoomInteractables {

    /** How far from a zone the feet may still be and count as "next to it". */
    const val REACH = 8f

    val zones: Map<Interactable, Box> = mapOf(
        Interactable.COMPUTER to Box(348f, 150f, 64f, 30f),
        Interactable.DESK     to Box(250f, 150f, 60f, 30f),
        Interactable.WINDOW   to Box(446f, 150f, 90f, 30f),
        Interactable.PHONE    to Box(138f, 178f, 34f, 50f),
        Interactable.DOOR     to RoomLayout.door
    )

    /** The nearest of the [enabled] objects the feet at ([x], [y]) are next to, or null. */
    fun near(x: Float, y: Float, enabled: Set<Interactable>): Interactable? =
        enabled
            .mapNotNull { target ->
                val zone = zones.getValue(target)
                val inside = x >= zone.x - REACH && x <= zone.right + REACH &&
                             y >= zone.y - REACH && y <= zone.bottom + REACH
                if (!inside) null else target to distance(x, y, zone)
            }
            .minByOrNull { it.second }
            ?.first

    private fun distance(x: Float, y: Float, zone: Box): Float {
        val dx = x - (zone.x + zone.w / 2f)
        val dy = y - (zone.y + zone.h / 2f)
        return dx * dx + dy * dy
    }
}
