package com.kotlinsurvivors.core.presentation.language

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.kotlinsurvivors.engine.UpgradeRarity
import com.kotlinsurvivors.engine.ecs.components.WeaponType
import com.kotlinsurvivors.features.game.domain.model.Achievement
import com.kotlinsurvivors.features.game.domain.model.LevelUpOption
import com.kotlinsurvivors.features.game.domain.model.PermanentUpgrade
import com.kotlinsurvivors.features.game.domain.model.UpgradeType

/**
 * Texts that the game logic produces (upgrades, weapons, achievements, shop items) are
 * identified by an id and carry an English wording. The screens show them through these
 * functions, which look the id up in the string resources of the current language and
 * fall back to the English wording when a translation is missing.
 */
@Composable
private fun text(name: String, fallback: String, vararg args: Any): String {
    val context = LocalContext.current
    val id = remember(name, context) { context.resources.getIdentifier(name, "string", context.packageName) }
    return if (id != 0) context.getString(id, *args) else fallback
}

private fun WeaponType.key() = name.lowercase()

@Composable
fun weaponName(type: WeaponType, fallback: String): String = text("weapon_${type.key()}_name", fallback)

@Composable
fun LevelUpOption.localTitle(): String = when {
    type == UpgradeType.WEAPON_UPGRADE && weaponType != null ->
        text("upgrade_weapon_title", title, weaponName(weaponType, title), level)
    type == UpgradeType.NEW_WEAPON && weaponType != null ->
        text("upgrade_new_weapon_title", title, weaponName(weaponType, title))
    else -> text("upgrade_${id}_title", title)
}

@Composable
fun LevelUpOption.localDescription(): String = when {
    type == UpgradeType.WEAPON_UPGRADE ->
        text(if (level % 3 == 0) "upgrade_weapon_desc_projectile" else "upgrade_weapon_desc_generic", description)
    type == UpgradeType.NEW_WEAPON && weaponType != null -> text("weapon_${weaponType.key()}_desc", description)
    else -> text("upgrade_${id}_desc", description)
}

@Composable
fun Achievement.localName(): String = text("${id}_name", name)

@Composable
fun Achievement.localDescription(): String = text("${id}_desc", description)

@Composable
fun PermanentUpgrade.localName(): String = text("${id}_name", name)

@Composable
fun PermanentUpgrade.localDescription(): String = text("${id}_desc", description)

@Composable
fun PermanentUpgrade.localEffect(): String = text("${id}_effect", effectPerLevel)

@Composable
fun rarityText(rarity: UpgradeRarity): String = text(
    "rarity_${rarity.name.lowercase()}",
    rarity.name
)

/** "_es" when the screens are in Spanish, "" otherwise: images with words baked in come in one file per language. */
@Composable
fun languageSuffix(): String =
    if (AppLanguage.of(LocalContext.current) == AppLanguage.SPANISH) "_es" else ""
