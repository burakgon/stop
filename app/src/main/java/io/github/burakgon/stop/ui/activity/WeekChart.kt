package io.github.burakgon.stop.ui.activity

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.burakgon.stop.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stops per day for the last seven days: one series, so no legend. Columns are capped at 24dp
 * with a 4dp rounded cap and a square base. Today's value and the busiest day are labeled;
 * tapping a column reveals its value too. Every value is also spelled out for screen readers.
 */
@Composable
fun WeekChart(days: List<ActivityViewModel.ChartDay>, dark: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    // A mid-tone of the accent: the dark scheme's primary is too pale to read as a filled mark.
    val barColor = if (dark) lerp(colors.primary, colors.primaryContainer, 0.4f) else colors.primary
    val stubColor = colors.outlineVariant
    val axisColor = colors.outlineVariant
    val textMuted = colors.onSurfaceVariant
    val textStrong = colors.onSurface
    val labelStyle = MaterialTheme.typography.labelSmall
    val valueStyle = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val dayFormat = remember { SimpleDateFormat("EEE", Locale.getDefault()) }
    var selected by remember(days) { mutableIntStateOf(days.lastIndex) }
    val growth = remember { Animatable(0f) }
    LaunchedEffect(Unit) { growth.animateTo(1f) }

    val description = stringResource(R.string.activity_chart_description) + ": " +
        days.joinToString { "${dayFormat.format(Date(it.start))} ${it.stops}" }
    val max = (days.maxOfOrNull { it.stops } ?: 0).coerceAtLeast(1)
    val busiest = days.indices.maxByOrNull { days[it].stops }?.takeIf { days[it].stops > 0 }

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(148.dp)
                .semantics { contentDescription = description }
                .pointerInput(days) {
                    detectTapGestures { offset ->
                        if (days.isEmpty()) return@detectTapGestures
                        val slot = size.width / days.size
                        selected = (offset.x / slot).toInt().coerceIn(0, days.lastIndex)
                    }
                },
        ) {
            if (days.isEmpty()) return@Canvas
            val labelHeight = 20.dp.toPx()
            val valueHeight = 22.dp.toPx()
            val plotTop = valueHeight
            val baseline = size.height - labelHeight
            val plotHeight = baseline - plotTop
            val slot = size.width / days.size
            val barWidth = minOf(24.dp.toPx(), slot * 0.5f)
            val radius = 4.dp.toPx()

            drawLine(axisColor, Offset(0f, baseline), Offset(size.width, baseline), strokeWidth = 1.dp.toPx())

            days.forEachIndexed { index, day ->
                val centerX = slot * index + slot / 2f
                val left = centerX - barWidth / 2f
                if (day.stops == 0) {
                    drawLine(
                        stubColor,
                        Offset(left, baseline - 1.dp.toPx()),
                        Offset(left + barWidth, baseline - 1.dp.toPx()),
                        strokeWidth = 2.dp.toPx(),
                    )
                } else {
                    val height = (plotHeight * day.stops / max * growth.value).coerceAtLeast(radius)
                    val top = baseline - height
                    val path = Path().apply {
                        addRoundRect(
                            RoundRect(
                                left = left, top = top, right = left + barWidth, bottom = baseline,
                                topLeftCornerRadius = CornerRadius(radius),
                                topRightCornerRadius = CornerRadius(radius),
                            )
                        )
                    }
                    drawPath(path, barColor)
                    if (index == selected || index == busiest) {
                        val text = measurer.measure(
                            day.stops.toString(),
                            valueStyle.merge(TextStyle(color = textStrong, fontWeight = FontWeight.Medium)),
                        )
                        drawText(
                            text,
                            topLeft = Offset(
                                (centerX - text.size.width / 2f).coerceIn(0f, size.width - text.size.width),
                                top - text.size.height - 2.dp.toPx(),
                            ),
                        )
                    }
                }
                val isToday = index == days.lastIndex
                val label = measurer.measure(
                    dayFormat.format(Date(day.start)),
                    labelStyle.merge(
                        TextStyle(
                            color = if (isToday || index == selected) textStrong else textMuted,
                            fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    ),
                )
                drawText(
                    label,
                    topLeft = Offset(centerX - label.size.width / 2f, baseline + (labelHeight - label.size.height) / 2f + 2.dp.toPx()),
                )
            }
        }
    }
}
