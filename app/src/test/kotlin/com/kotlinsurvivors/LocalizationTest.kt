package com.kotlinsurvivors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The two string files must stay in step, and every image with words baked in must
 * exist in both languages. Run from the app module directory.
 */
class LocalizationTest {

    private val res    = File("src/main/res")
    private val assets = File("src/main/assets/sprites")

    private fun keys(dir: String): Set<String> =
        Regex("""<string name="([^"]+)"""").findAll(File(res, "$dir/strings.xml").readText())
            .map { it.groupValues[1] }.toSet()

    private fun placeholders(dir: String): Map<String, List<String>> =
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""").findAll(File(res, "$dir/strings.xml").readText())
            .associate { m -> m.groupValues[1] to Regex("""%\d\$[sd]""").findAll(m.groupValues[2]).map { it.value }.toList() }

    @Test fun spanishHasEveryEnglishString() {
        val missing = keys("values") - keys("values-es")
        assertTrue("Missing in Spanish: $missing", missing.isEmpty())
        val extra = keys("values-es") - keys("values")
        assertTrue("Only in Spanish: $extra", extra.isEmpty())
    }

    @Test fun placeholdersMatchBetweenLanguages() {
        val en = placeholders("values")
        val es = placeholders("values-es")
        for ((k, v) in en) assertEquals("Placeholders of $k", v, es[k])
    }

    @Test fun imagesWithTextExistInBothLanguages() {
        val names = assets.listFiles()!!.map { it.name }.toSet()
        val withText = names.filter {
            !it.contains("_es") && (it.startsWith("ui_btn_") || it in setOf("ui_title_gameover.png", "ui_title_levelup.png", "ui_title_paused.png"))
        }
        assertTrue(withText.isNotEmpty())
        for (n in withText) assertTrue("Missing Spanish image for $n", n.replace(".png", "").let { b ->
            val base = b.removeSuffix("_down"); val down = b.endsWith("_down")
            "${base}_es${if (down) "_down" else ""}.png" in names
        })
    }
}
