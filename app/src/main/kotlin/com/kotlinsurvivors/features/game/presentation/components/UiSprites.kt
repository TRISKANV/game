package com.kotlinsurvivors.features.game.presentation.components

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
    val image by produceState<ImageBitmap?>(null, fileName) {
        value = withContext(Dispatchers.IO) {
            try {
                SpriteAtlas.bitmap(appContext, fileName).asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }
    return image
}
