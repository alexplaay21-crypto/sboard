package com.sboard.app.ui.components

import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.sboard.app.core.i18n.tr
import com.sboard.app.ui.theme.SBoardShapes
import com.sboard.app.ui.theme.palette

/** Swatches offered under the picker: night-sky lavender/indigo/rose plus neutrals and brights. */
val ColorPresets: List<Long> = listOf(
    0xFF0B0D24, 0xFF1C1F4A, 0xFF2A2C66, 0xFF4B3C99, 0xFF7357E0, 0xFFB18CFF, 0xFFD9C8FF, 0xFFE45FA2,
    0xFFB8326E, 0xFF7FD6FF, 0xFF3D5AFE, 0xFF5277B8, 0xFF4DB6AC, 0xFFD5B94B, 0xFFFFC857, 0xFFFF7A70,
    0xFF000000, 0xFF1A1B1E, 0xFF3A3B40, 0xFF8E9096, 0xFFD9D9DE, 0xFFFFFFFF,
)

private fun hsv(color: Long): FloatArray {
    val out = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toInt(), out)
    return out
}

private fun fromHsv(h: Float, s: Float, v: Float): Long = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)).toLong() and 0xFFFFFFFFL

/** HSV colour picker: saturation/value square, hue strip, HEX field and swatches. */
@Composable
fun ColorPicker(color: Long, onChange: (Long) -> Unit, modifier: Modifier = Modifier) {
    // Picker state lives across recompositions; it only follows [color] when it changes from outside
    // (another element selected, preset, HEX). Dragging updates the colour continuously.
    var hue by remember { mutableFloatStateOf(hsv(color)[0]) }
    var sat by remember { mutableFloatStateOf(hsv(color)[1]) }
    var value by remember { mutableFloatStateOf(hsv(color)[2]) }
    var lastEmitted by remember { mutableStateOf(color) }
    LaunchedEffect(color) {
        if ((color and 0xFFFFFF) != (lastEmitted and 0xFFFFFF)) {
            val h = hsv(color); hue = h[0]; sat = h[1]; value = h[2]; lastEmitted = color
        }
    }
    val change by rememberUpdatedState(onChange)
    fun emit() { val c = fromHsv(hue, sat, value); lastEmitted = c; change(c) }
    var hexText by remember(color) { mutableStateOf("%06X".format(color and 0xFFFFFF)) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(40.dp).clip(SBoardShapes.small).background(Color(color.toInt())).border(1.dp, palette.outline, SBoardShapes.small))
            SBTextField(hexText, { v ->
                val clean = v.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                hexText = clean
                if (clean.length == 6) onChange(0xFF000000L or clean.toLong(16))
            }, "HEX", Modifier.weight(1f), monospace = true)
        }
        // Saturation (x) × brightness (y) square for the current hue: touch and slide.
        val hueColor = Color(fromHsv(hue, 1f, 1f).toInt())
        Box(Modifier.fillMaxWidth().height(170.dp).clip(SBoardShapes.medium)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun update(o: Offset) { sat = (o.x / size.width).coerceIn(0f, 1f); value = 1f - (o.y / size.height).coerceIn(0f, 1f); emit() }
                    update(down.position); down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val p = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!p.pressed) break
                        update(p.position); p.consume()
                    }
                }
            }) {
            Canvas(Modifier.matchParentSize()) {
                drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                val c = Offset(sat * size.width, (1f - value) * size.height)
                drawCircle(Color.White, 11.dp.toPx(), c, style = Stroke(3.dp.toPx()))
                drawCircle(Color.Black.copy(alpha = 0.4f), 13.dp.toPx(), c, style = Stroke(1.dp.toPx()))
            }
        }
        Text(tr("Оттенок"), style = MaterialTheme.typography.labelMedium, color = palette.textMuted)
        Box(Modifier.fillMaxWidth().height(30.dp).clip(SBoardShapes.pill)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    hue = (down.position.x / size.width * 360f).coerceIn(0f, 359.9f); emit(); down.consume()
                    while (true) {
                        val p = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!p.pressed) break
                        hue = (p.position.x / size.width * 360f).coerceIn(0f, 359.9f); emit(); p.consume()
                    }
                }
            }) {
            Canvas(Modifier.matchParentSize()) {
                drawRect(Brush.horizontalGradient((0..6).map { Color(fromHsv(it * 60f % 360f, 1f, 1f).toInt()) }))
                val x = hue / 360f * size.width
                drawCircle(Color.White, size.height / 2.2f, Offset(x, size.height / 2), style = Stroke(3.dp.toPx()))
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ColorPresets) { preset ->
                val selected = (preset and 0xFFFFFF) == (color and 0xFFFFFF)
                Box(Modifier.size(36.dp).clip(CircleShape).background(Color(preset.toInt()))
                    .border(if (selected) 3.dp else 1.dp, if (selected) palette.accent else palette.outline, CircleShape)
                    .clickable { onChange(preset) })
            }
        }
    }
}

@Suppress("unused") private fun Color.argb(): Long = toArgb().toLong() and 0xFFFFFFFFL
