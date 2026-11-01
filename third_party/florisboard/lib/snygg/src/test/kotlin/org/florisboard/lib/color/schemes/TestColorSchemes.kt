/*
 * sBoard integration test fixture for the FlorisBoard v0.5.2 sources.
 * The upstream Snygg tests refer to these schemes, but the release tag does
 * not include the fixture that defines them.
 */
package org.florisboard.lib.color.schemes

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

val yellowLightScheme = lightColorScheme(
    primary = Color(0xFF795900),
    secondary = Color(0xFF685D3F),
)

val yellowDarkScheme = darkColorScheme(
    primary = Color(0xFFE9C349),
    secondary = Color(0xFFD2C6A5),
)
