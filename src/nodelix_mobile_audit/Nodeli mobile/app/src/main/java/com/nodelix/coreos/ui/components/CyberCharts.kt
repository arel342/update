package com.nodelix.coreos.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nodelix.coreos.ui.theme.NodelixBorder
import com.nodelix.coreos.ui.theme.NodelixCardSecondary
import com.nodelix.coreos.ui.theme.NodelixMonoStyles
import com.nodelix.coreos.ui.theme.NodelixPrimary
import com.nodelix.coreos.ui.theme.NodelixSecondary
import com.nodelix.coreos.ui.theme.NodelixTextSecondary

/**
 * Dual-ring futuristic cyber gauge inspired by Nodelix visual identity.
 * Renders instantaneous telemetry honestly without fabricating data.
 */
@Composable
fun CyberDualRingGauge(
    outerFraction: Float,
    innerFraction: Float?,
    centerValue: String,
    centerLabel: String,
    outerColor: Color = NodelixPrimary,
    innerColor: Color = NodelixSecondary,
    size: Dp = 156.dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeOuter = 11.dp.toPx()
            val strokeInner = 8.dp.toPx()
            val padOuter = strokeOuter / 2f + 4.dp.toPx()
            val sweepTotal = 250f
            val startAngle = 145f

            val outerTopLeft = Offset(padOuter, padOuter)
            val outerSize = Size(this.size.width - padOuter * 2, this.size.height - padOuter * 2)

            drawArc(
                color = NodelixCardSecondary,
                startAngle = startAngle,
                sweepAngle = sweepTotal,
                useCenter = false,
                topLeft = outerTopLeft,
                size = outerSize,
                style = Stroke(width = strokeOuter, cap = StrokeCap.Round)
            )
            drawArc(
                color = outerColor,
                startAngle = startAngle,
                sweepAngle = sweepTotal * outerFraction.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = outerTopLeft,
                size = outerSize,
                style = Stroke(width = strokeOuter, cap = StrokeCap.Round)
            )

            if (innerFraction != null) {
                val padInner = padOuter + strokeOuter + 6.dp.toPx()
                val innerTopLeft = Offset(padInner, padInner)
                val innerSize = Size(this.size.width - padInner * 2, this.size.height - padInner * 2)

                drawArc(
                    color = NodelixCardSecondary,
                    startAngle = startAngle,
                    sweepAngle = sweepTotal,
                    useCenter = false,
                    topLeft = innerTopLeft,
                    size = innerSize,
                    style = Stroke(width = strokeInner, cap = StrokeCap.Round)
                )
                drawArc(
                    color = innerColor,
                    startAngle = startAngle,
                    sweepAngle = sweepTotal * innerFraction.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = innerTopLeft,
                    size = innerSize,
                    style = Stroke(width = strokeInner, cap = StrokeCap.Round)
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = centerValue,
                style = NodelixMonoStyles.metricValue
            )
            Text(
                text = centerLabel.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = NodelixTextSecondary
            )
        }
    }
}

/**
 * Renders a real historical line chart ONLY when >= 2 genuine backend samples exist (Part 8).
 * Never synthesizes fake historical points.
 */
@Composable
fun RealTelemetryLineChart(
    points: List<Double>,
    minY: Double = 0.0,
    maxY: Double = 100.0,
    lineColor: Color = NodelixPrimary,
    height: Dp = 110.dp,
    emptyMessage: String = "Collecting live telemetry samples...",
    modifier: Modifier = Modifier
) {
    if (points.size < 2) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(height),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emptyMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = NodelixTextSecondary
            )
        }
        return
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val w = size.width
        val h = size.height
        val range = (maxY - minY).takeIf { it > 0.0001 } ?: 1.0

        // Subtle horizontal grid lines
        for (i in 1..3) {
            val y = h * (i / 4f)
            drawLine(
                color = NodelixBorder.copy(alpha = 0.55f),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1.dp.toPx()
            )
        }

        val stepX = w / (points.size - 1).toFloat()
        val linePath = Path()
        val fillPath = Path()

        points.forEachIndexed { idx, value ->
            val x = idx * stepX
            val normalized = ((value - minY) / range).coerceIn(0.0, 1.0).toFloat()
            val y = h - (normalized * (h - 8.dp.toPx())) - 4.dp.toPx()
            if (idx == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(w, h)
        fillPath.close()

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.26f), Color.Transparent)
            )
        )
        drawPath(
            path = linePath,
            color = lineColor,
            style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}
