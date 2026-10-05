package com.dosely.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun TrendChart(
    values: List<Double>,
    description: String,
    modifier: Modifier = Modifier,
    fromZero: Boolean = false,
    highlightIndex: Int? = null,
) {
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier
            .fillMaxWidth()
            .height(136.dp)
            .semantics { contentDescription = description },
    ) {
        val insetX = 8.dp.toPx()
        val insetY = 10.dp.toPx()
        val w = size.width - insetX * 2
        val h = size.height - insetY * 2

        // Subtle background grid lines
        repeat(4) { row ->
            val y = insetY + h * row / 3
            drawLine(
                grid.copy(alpha = 0.25f),
                Offset(insetX, y),
                Offset(size.width - insetX, y),
                1.dp.toPx(),
            )
        }

        if (values.size < 2) return@Canvas

        val lo = if (fromZero) 0.0 else (values.minOrNull() ?: 0.0) - 0.25
        val hi = (values.maxOrNull() ?: 1.0).coerceAtLeast(lo + 0.5)

        val points = values.mapIndexed { i, v ->
            Offset(
                insetX + w * i / (values.size - 1).coerceAtLeast(1),
                insetY + h * (1f - ((v - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)),
            )
        }

        // Draw vertical indicator line for "Today" (midpoint for 14-day medication series, or custom highlight)
        val todayIdx = highlightIndex ?: if (values.size > 50) values.size / 2 else null
        if (todayIdx != null && todayIdx in points.indices) {
            val todayPoint = points[todayIdx]
            drawLine(
                color.copy(alpha = 0.4f),
                Offset(todayPoint.x, insetY),
                Offset(todayPoint.x, size.height - insetY),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f),
            )
        }

        // Smooth cubic bezier path
        val line = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (i in 1 until points.size) {
                val prev = points[i - 1]
                val curr = points[i]
                val c1 = Offset(prev.x + (curr.x - prev.x) / 2f, prev.y)
                val c2 = Offset(prev.x + (curr.x - prev.x) / 2f, curr.y)
                cubicTo(c1.x, c1.y, c2.x, c2.y, curr.x, curr.y)
            }
        }

        val area = Path().apply {
            addPath(line)
            lineTo(points.last().x, size.height - insetY)
            lineTo(points.first().x, size.height - insetY)
            close()
        }

        // Draw soft gradient area fill
        drawPath(
            area,
            Brush.verticalGradient(
                listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0.08f), Color.Transparent),
            ),
        )

        // Draw smooth stroke line
        drawPath(
            line,
            color,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // Draw key points
        if (todayIdx != null && todayIdx in points.indices) {
            val pt = points[todayIdx]
            drawCircle(color.copy(alpha = 0.25f), 8.dp.toPx(), pt)
            drawCircle(Color.White, 5.dp.toPx(), pt)
            drawCircle(color, 3.5.dp.toPx(), pt)
        } else {
            val last = points.last()
            drawCircle(color.copy(alpha = 0.25f), 7.dp.toPx(), last)
            drawCircle(color, 4.dp.toPx(), last)
        }
    }
}
