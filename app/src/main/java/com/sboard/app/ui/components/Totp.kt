package com.sboard.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sboard.app.ui.theme.palette
import kotlinx.coroutines.delay

/** Current time in milliseconds, updated every second (aligned to the wall clock). */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000 - (System.currentTimeMillis() % 1000))
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun CountdownRing(secondsLeft: Int, period: Int, size: Dp = 26.dp) {
    val progress by animateFloatAsState(secondsLeft / period.toFloat(), tween(900), label = "ring")
    val color = if (secondsLeft <= 5) palette.danger else palette.accent
    val track = palette.surfaceHighest
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round)
            drawArc(track, 0f, 360f, false, style = stroke)
            drawArc(color, -90f, 360f * progress, false, style = stroke)
        }
        Text("$secondsLeft", style = MaterialTheme.typography.labelSmall, color = color)
    }
}
