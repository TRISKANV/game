package com.kotlinsurvivors.features.game.presentation.components

import android.content.Context
import com.kotlinsurvivors.core.presentation.language.AppLanguage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.kotlinsurvivors.engine.rendering.SpriteAtlas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads one of the interface images from assets/sprites/ off the main thread.
 * Returns null while it loads or if the file is missing, so callers keep a
 * plain fallback and the screen never waits on it.
 *
 * The ui_* images come from the CC0 "Mobile Controls" pack by Kenney.
 */
@Composable
fun rememberUiImage(fileName: String): ImageBitmap? {
    val appContext = LocalContext.current.applicationContext
    val image by produceState(UiImageCache.get(fileName), fileName) {
        if (value == null) value = UiImageCache.load(appContext, fileName)
    }
    return image
}

/**
 * Interface images stay decoded for the life of the process, so a screen that opens
 * mid-game (level up, pause, game over) finds them ready instead of decoding them
 * while its first frames are being drawn.
 */
object UiImageCache {
    private val images = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()

    fun get(fileName: String): ImageBitmap? = images[fileName]

    /** Decodes [fileName] on the IO dispatcher unless it is already cached. Null if the file is missing. */
    suspend fun load(context: Context, fileName: String): ImageBitmap? {
        images[fileName]?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                SpriteAtlas.bitmap(context, fileName).asImageBitmap().also { images[fileName] = it }
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Warms the cache with the images the in-game overlays use. */
    suspend fun preloadGameOverlays(context: Context) {
        val lang = if (AppLanguage.effective(context) == AppLanguage.SPANISH) "_es" else ""
        for (name in gameOverlayImages(lang)) load(context, name)
    }

    private fun gameOverlayImages(lang: String): List<String> {
        val buttons = listOf("resume", "restart", "mainmenu", "playagain")
            .flatMap { listOf("ui_btn_$it$lang.png", "ui_btn_$it${lang}_down.png") }
        return listOf(
            "ui_title_levelup$lang.png", "ui_title_paused$lang.png", "ui_title_gameover$lang.png",
            "ui_panel_pause.png", "ui_icon_pause.png", "ui_joystick_pad.png", "ui_joystick_nub.png"
        ) + buttons
    }
}
