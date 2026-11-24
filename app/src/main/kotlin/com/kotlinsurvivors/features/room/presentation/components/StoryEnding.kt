package com.kotlinsurvivors.features.room.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The black screen that closes the alternative ending: three quiet lines that appear one by one,
 * then a small card with the name of the ending and the way back to the menu. Every part comes in
 * with its own opacity, so nothing pops; the director decides when.
 */
@Composable
fun StoryEnding(
    lines: List<String>,
    lineAlpha: (Int) -> Float,
    title: String,
    subtitle: String,
    titleAlpha: Float,
    menuLabel: String,
    menuVisible: Boolean,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)
        ) {
            lines.forEachIndexed { i, text ->
                Text(
                    text = text,
                    color = Color(0xFFE6E2F2),
                    fontSize = 19.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.alpha(lineAlpha(i)).padding(vertical = 5.dp)
                )
            }

            Spacer(Modifier.height(22.dp))
            Box(
                Modifier.width(96.dp).height(2.dp)
                    .alpha(titleAlpha).background(Color(0xFF8A52E0))
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = title,
                color = Color(0xFFC9A0FF),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.alpha(titleAlpha)
            )
            Text(
                text = subtitle,
                color = Color(0xFF8C86A8),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.alpha(titleAlpha).padding(top = 4.dp)
            )

            Spacer(Modifier.height(22.dp))
            // Always laid out, so the card does not jump when the button arrives.
            Box(Modifier.alpha(if (menuVisible) 1f else 0f)) {
                if (menuVisible) StoryButton(label = menuLabel, onClick = onMenu, strong = true)
                else Spacer(Modifier.height(48.dp))
            }
        }
    }
}
