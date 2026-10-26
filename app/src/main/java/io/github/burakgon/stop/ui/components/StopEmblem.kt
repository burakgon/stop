package io.github.burakgon.stop.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Stop's octagon mark. While [active], soft rings pulse outward from it; otherwise it is drawn
 * as an outline so a paused state reads differently at a glance.
 */
@Composable
fun StopEmblem(
    color: Color,
    barColor: Color,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "emblem")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 2_800, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse",
    )
    Canvas(modifier) {
        val radius = size.minDimension / 2f
        val body = radius * 0.64f
        if (active) {
            for (offset in listOf(0f, 0.5f)) {
                val t = (pulse + offset) % 1f
                drawPath(
                    path = octagon(center, body + (radius - body) * t, body * 0.16f),
                    color = color.copy(alpha = 0.35f * (1f - t)),
                    style = Stroke(width = radius * 0.035f),
                )
            }
            drawPath(octagon(center, body, body * 0.16f), color)
        } else {
            drawPath(octagon(center, body - radius * 0.03f, body * 0.16f), color, style = Stroke(width = radius * 0.06f))
        }
        val barWidth = body * 1.12f
        val barHeight = body * 0.26f
        drawRoundRect(
            color = if (active) barColor else color,
            topLeft = Offset(center.x - barWidth / 2f, center.y - barHeight / 2f),
            size = Size(barWidth, barHeight),
            cornerRadius = CornerRadius(barHeight / 2f),
        )
    }
}

/** A regular octagon with a flat top edge and rounded corners. */
private fun octagon(center: Offset, radius: Float, corner: Float): Path {
    val points = (0 until 8).map { i ->
        val angle = (PI / 8 + i * PI / 4).toFloat()
        Offset(center.x + radius * cos(angle), center.y + radius * sin(angle))
    }
    fun toward(from: Offset, to: Offset, distance: Float): Offset {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val length = hypot(dx, dy)
        return Offset(from.x + dx / length * distance, from.y + dy / length * distance)
    }
    return Path().apply {
        points.forEachIndexed { i, point ->
            val start = toward(point, points[(i + 7) % 8], corner)
            val end = toward(point, points[(i + 1) % 8], corner)
            if (i == 0) moveTo(start.x, start.y) else lineTo(start.x, start.y)
            quadraticTo(point.x, point.y, end.x, end.y)
        }
        close()
    }
}
