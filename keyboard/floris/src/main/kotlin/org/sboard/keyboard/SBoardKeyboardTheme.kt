package org.sboard.keyboard

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File

/** Key shapes offered in the theme editor (Samsung Theme Park style). */
@Serializable
enum class KeyStyle { ROUNDED, SQUARE, CAPSULE, OUTLINE, UNDERLINE, FLAT }

/**
 * User-defined keyboard theme. Colours are ARGB ints. Every element of the keyboard preview in the
 * editor maps to one field here; [toStylesheet] turns it into a Snygg stylesheet for the engine.
 */
@Serializable
data class KeyboardThemeSpec(
    val background: Long,
    val toolbarBackground: Long,
    val toolbarIcons: Long,
    val keyBackground: Long,
    val keyText: Long,
    val hintText: Long,
    val functionKeyBackground: Long,
    val functionKeyText: Long,
    val accent: Long,
    val keyBorder: Long,
    val keyStyle: KeyStyle = KeyStyle.ROUNDED,
    val cornerRadius: Int = 10,
    val fontScale: Int = 100,
    /** Toolbar icon size in sp. */
    val toolbarIconSize: Int = 26,
    /** Size of icons/labels on Shift, Delete, Enter, ?123 relative to letters (percent). */
    val functionKeyScale: Int = 112,
    /** "normal", "medium" or "bold" letters. */
    val fontWeight: String = "normal",
    /** Show the small long-press hints on letter keys. */
    val showHints: Boolean = true,
) {
    companion object {
        /** Night default — palette inspired by a moonlit night sky (indigo, lavender, rose). */
        val NIGHT = KeyboardThemeSpec(
            background = 0xFF0B0D24, toolbarBackground = 0xFF0B0D24, toolbarIcons = 0xFFC9C3FF,
            keyBackground = 0xFF1C1F4A, keyText = 0xFFF1EEFF, hintText = 0xFFA79BE0,
            functionKeyBackground = 0xFF2A2C66, functionKeyText = 0xFFE7E1FF,
            accent = 0xFFB18CFF, keyBorder = 0xFF6F5BE8, keyStyle = KeyStyle.ROUNDED,
        )
        val DAY = KeyboardThemeSpec(
            background = 0xFFEEEBFB, toolbarBackground = 0xFFEEEBFB, toolbarIcons = 0xFF4B3C99,
            keyBackground = 0xFFFFFFFF, keyText = 0xFF1C1838, hintText = 0xFF8A7FC2,
            functionKeyBackground = 0xFFDCD6F7, functionKeyText = 0xFF2B2557,
            accent = 0xFF7357E0, keyBorder = 0xFFB9ACF2, keyStyle = KeyStyle.ROUNDED,
        )
    }
}

object SBoardThemeStore {
    const val EXTENSION = "org.sboard.themes"
    const val CUSTOM_DAY = "sboard_custom_day"
    const val CUSTOM_NIGHT = "sboard_custom_night"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    private val _revision = MutableStateFlow(0)
    /** Incremented on every save so the keyboard reloads the custom theme immediately. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun isCustom(componentId: String) = componentId == CUSTOM_DAY || componentId == CUSTOM_NIGHT

    private fun specFile(context: Context, night: Boolean) = File(context.filesDir, "sboard_kb_theme_${if (night) "night" else "day"}.json")
    // Versioned name: a new app version regenerates stylesheets built from older templates.
    private fun stylesheetFile(context: Context, componentId: String) = File(context.filesDir, "sboard_kb_stylesheet_v${STYLE_VERSION}_$componentId.json")
    private const val STYLE_VERSION = 2

    fun load(context: Context, night: Boolean): KeyboardThemeSpec = runCatching {
        json.decodeFromString(KeyboardThemeSpec.serializer(), specFile(context, night).readText())
    }.getOrElse { if (night) KeyboardThemeSpec.NIGHT else KeyboardThemeSpec.DAY }

    fun save(context: Context, night: Boolean, spec: KeyboardThemeSpec) {
        specFile(context, night).writeText(json.encodeToString(KeyboardThemeSpec.serializer(), spec))
        stylesheetFile(context, if (night) CUSTOM_NIGHT else CUSTOM_DAY).writeText(toStylesheet(context, spec, night))
        _revision.value++
    }

    /** Stylesheet JSON for a custom theme, generated on demand if it was never saved. */
    fun stylesheetJson(context: Context, componentId: String): String {
        val file = stylesheetFile(context, componentId)
        if (file.exists()) return file.readText()
        val night = componentId == CUSTOM_NIGHT
        return toStylesheet(context, load(context, night), night).also { file.writeText(it) }
    }

    private fun hex(c: Long): String = "#%06X".format(c and 0xFFFFFFL)

    private fun shade(c: Long, amount: Float): Long {
        val a = (c ushr 24) and 0xFF
        fun ch(v: Long) = (v.toFloat() + (if (amount > 0) (255 - v) else v) * amount).toInt().coerceIn(0, 255).toLong()
        val r = ch((c ushr 16) and 0xFF); val g = ch((c ushr 8) and 0xFF); val b = ch(c and 0xFF)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun luminance(c: Long): Double {
        val r = ((c ushr 16) and 0xFF) / 255.0; val g = ((c ushr 8) and 0xFF) / 255.0; val b = (c and 0xFF) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    fun onColor(c: Long): Long = if (luminance(c) > 0.55) 0xFF15132B else 0xFFFFFFFF

    fun toStylesheet(context: Context, spec: KeyboardThemeSpec, night: Boolean): String {
        val templateName = if (night) "sboard_dark" else "sboard_light"
        val template = context.assets.open("ime/theme/org.sboard.themes/stylesheets/$templateName.json").bufferedReader().use { it.readText() }
        val root = Json.parseToJsonElement(template).jsonObject.toMutableMap()
        val pressed = shade(spec.keyBackground, if (luminance(spec.keyBackground) > 0.5) -0.12f else 0.18f)
        val defines = root["@defines"]!!.jsonObject.toMutableMap().apply {
            put("--background", p(hex(spec.background)))
            put("--background-variant", p(hex(shade(spec.background, if (night) 0.06f else -0.04f))))
            put("--surface", p(hex(spec.keyBackground)))
            put("--surface-variant", p(hex(pressed)))
            put("--on-surface", p(hex(spec.keyText)))
            put("--on-surface-variant", p(hex(spec.hintText)))
            put("--on-background", p(hex(spec.toolbarIcons)))
            put("--primary", p(hex(spec.accent)))
            put("--primary-variant", p(hex(shade(spec.accent, -0.15f))))
            put("--secondary", p(hex(spec.accent)))
            put("--on-primary", p(hex(onColor(spec.accent))))
            put("--popup-surface", p(hex(shade(spec.keyBackground, if (night) 0.12f else -0.05f))))
            put("--focused-popup-surface", p(hex(spec.accent)))
            put("--one-hand-background", p(hex(spec.background)))
            put("--one-hand-foreground", p(hex(spec.toolbarIcons)))
            put("--shape", p(shape(spec)))
        }
        root["@defines"] = JsonObject(defines)
        fun rule(selector: String, vararg props: Pair<String, String>) {
            val existing = root[selector]?.jsonObject?.toMutableMap() ?: mutableMapOf()
            props.forEach { (k, v) -> existing[k] = p(v) }
            root[selector] = JsonObject(existing)
        }
        rule("smartbar", "background" to hex(spec.toolbarBackground))
        rule("smartbar-action-key", "font-size" to "${spec.toolbarIconSize.coerceIn(16, 34)}sp")
        rule("smartbar-shared-actions-toggle", "font-size" to "${(spec.toolbarIconSize - 2).coerceIn(16, 32)}sp")
        rule("smartbar-extended-actions-toggle", "font-size" to "${(spec.toolbarIconSize - 2).coerceIn(16, 32)}sp")
        rule("key", "font-size" to "${(22 * spec.fontScale / 100)}sp", "font-weight" to spec.fontWeight.takeIf { it in setOf("normal", "medium", "bold") }.orEmpty().ifEmpty { "normal" })
        if (!spec.showHints) rule("key-hint", "foreground" to "transparent")
        val fnKeys = "key[code=-11,-7,-202,-201,-203,-227,-212,-213,-205,-233]"
        rule(fnKeys, "background" to hex(spec.functionKeyBackground), "foreground" to hex(spec.functionKeyText))
        rule("key[code=-11,-7,10,-233]", "font-size" to "${(22 * spec.fontScale / 100) * spec.functionKeyScale / 100}sp")
        rule("key[code=-201,-202,-203]", "font-size" to "${(18 * spec.fontScale / 100) * spec.functionKeyScale / 100}sp")
        rule("key[code=10]", "background" to hex(spec.accent), "foreground" to hex(onColor(spec.accent)))
        rule("key[code=10]:pressed", "background" to hex(shade(spec.accent, -0.15f)), "foreground" to hex(onColor(spec.accent)))
        when (spec.keyStyle) {
            KeyStyle.OUTLINE -> {
                rule("key", "border-color" to hex(spec.keyBorder), "border-width" to "1dp", "shadow-elevation" to "0dp")
                rule(fnKeys, "border-color" to hex(spec.keyBorder), "border-width" to "1dp")
            }
            KeyStyle.UNDERLINE -> {
                // Coloured glow under each key (the look of the user's Samsung theme).
                rule("key", "shadow-elevation" to "3dp", "shadow-color" to hex(spec.keyBorder), "border-width" to "0dp")
            }
            KeyStyle.FLAT -> {
                rule("key", "background" to "transparent", "shadow-elevation" to "0dp", "border-width" to "0dp")
                rule(fnKeys, "background" to "transparent")
                rule("key[code=32]", "background" to hex(spec.keyBackground))
            }
            else -> rule("key", "shadow-elevation" to "1dp", "border-width" to "0dp")
        }
        return JsonObject(root).toString()
    }

    private fun shape(spec: KeyboardThemeSpec): String = when (spec.keyStyle) {
        KeyStyle.SQUARE -> "rounded-corner(3dp, 3dp, 3dp, 3dp)"
        KeyStyle.CAPSULE -> "rounded-corner(50%, 50%, 50%, 50%)"
        else -> "rounded-corner(${spec.cornerRadius}dp, ${spec.cornerRadius}dp, ${spec.cornerRadius}dp, ${spec.cornerRadius}dp)"
    }

    private fun p(value: String): JsonElement = JsonPrimitive(value)
}
