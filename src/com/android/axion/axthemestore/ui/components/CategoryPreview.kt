/*
 * Copyright (C) 2025-2026 AxionOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.axion.axthemestore.ui.components

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.util.PathParser
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.android.axion.axthemestore.R
import kotlin.math.min

private data class BatteryShapeSpec(
    val pathData: String,
    val viewportWidth: Float,
    val viewportHeight: Float,
    val fillPathData: String? = null,
    val accentPathData: String? = null,
)

private val BATTERY_SHAPES = mapOf(
    "oneui7" to BatteryShapeSpec(
        pathData = "M6,0 C2.69,0 0,2.69 0,6 C0,9.31 2.69,12 6,12 L18,12 C21.31,12 24,9.31 24,6 C24,2.69 21.31,0 18,0 L6,0 Z",
        viewportWidth = 24f,
        viewportHeight = 12f,
    ),
    "ios15" to BatteryShapeSpec(
        pathData = "M3.79,0.78L18.21,0.78A2.82 2.82 0 0 1 21.03,3.60L21.03,8.40A2.82 2.82 0 0 1 18.21,11.22L3.79,11.22A2.82 2.82 0 0 1 0.97,8.40L0.97,3.60A2.82 2.82 0 0 1 3.79,0.78zM21.82,7.59C23.43,7.11,23.43,4.89,21.82,4.41L21.82,7.59z",
        viewportWidth = 24f,
        viewportHeight = 12f,
        fillPathData = "M2.00,3.75L2.00,8.25A2.00 2.00 0 0 0 4.00,10.25L18.00,10.25A2.00 2.00 0 0 0 20.00,8.25L20.00,3.75A2.00 2.00 0 0 0 18.00,1.75L4.00,1.75A2.00 2.00 0 0 0 2.00,3.75z",
    ),
    "ios16" to BatteryShapeSpec(
        pathData = "M3.13,0.36L18.71,0.36A3.04 3.04 0 0 1 21.76,3.40L21.76,8.60A3.04 3.04 0 0 1 18.71,11.64L3.13,11.64A3.04 3.04 0 0 1 0.08,8.60L0.08,3.40A3.04 3.04 0 0 1 3.13,0.36zM22.61,7.72C24.35,7.19,24.35,4.81,22.61,4.28L22.61,7.72z",
        viewportWidth = 24f,
        viewportHeight = 12f,
    ),
    "origami" to BatteryShapeSpec(
        pathData = "M15.50,0.50C13.50,0.33,11.00,0.33,8.50,0.50C8.00,-0.21,6.00,-0.21,5.63,1.00C5.17,2.50,4.33,2.33,4.00,4.33C3.75,5.50,3.75,7.50,4.00,8.43C4.25,9.50,5.50,12.00,8.95,12.00L15.00,12.00C18.45,12.00,19.70,9.50,19.95,8.43C20.20,7.50,20.20,5.50,19.95,4.33C19.62,2.33,18.79,2.50,18.33,1.00C18.00,-0.21,16.00,-0.21,15.50,0.50z",
        viewportWidth = 20f,
        viewportHeight = 12f,
        fillPathData = "M15.25,10.50C12.50,10.57,11.50,10.57,8.75,10.50C7.50,10.40,6.00,9.75,5.50,7.75Q5.12,6.00,5.50,4.50C6.00,3.00,7.50,2.10,8.75,2.00C11.00,1.88,13.00,1.88,15.25,2.00C16.50,2.10,18.00,3.00,18.50,4.50Q18.88,6.00,18.50,7.75C18.00,9.75,16.50,10.40,15.25,10.50z",
    ),
    "smiley" to BatteryShapeSpec(
        pathData = "M3.76,0.62L18.03,0.62A2.74 2.74 0 0 1 20.78,3.36L20.78,8.64A2.74 2.74 0 0 1 18.03,11.38L3.76,11.38A2.74 2.74 0 0 1 1.02,8.64L1.02,3.36A2.74 2.74 0 0 1 3.76,0.62zM21.66,7.79C23.42,7.68,23.42,4.32,21.66,4.21L21.66,7.79z",
        viewportWidth = 22f,
        viewportHeight = 12f,
        fillPathData = "M1.93,3.37L1.93,8.66A1.85 1.85 0 0 0 3.78,10.51L18.04,10.51A1.85 1.85 0 0 0 19.89,8.66L19.89,3.37A1.85 1.85 0 0 0 18.04,1.52L3.78,1.52A1.85 1.85 0 0 0 1.93,3.37z",
        accentPathData = "M12.21,6.01C12.05,7.95,9.45,7.95,9.29,6.01C9.34,5.36,10.21,5.36,10.27,6.01C10.22,6.66,11.28,6.66,11.23,6.01C11.29,5.36,12.16,5.36,12.21,6.01zM15.62,5.03C16.16,5.03,16.59,5.47,16.59,6.01C16.59,6.54,16.16,6.98,15.62,6.98C15.08,6.98,14.65,6.54,14.65,6.01C14.65,5.47,15.08,5.03,15.62,5.03zM5.88,5.03C6.42,5.03,6.86,5.47,6.86,6.01C6.86,6.54,6.42,6.98,5.88,6.98C5.34,6.98,4.91,6.54,4.91,6.01C4.91,5.47,5.34,5.03,5.88,5.03z",
    ),
)

@Composable
fun BatteryStylePreview(packageName: String, modifier: Modifier = Modifier) {
    val shapeKey = packageName.substringAfterLast('.')
    val spec = BATTERY_SHAPES[shapeKey] ?: return

    val strokeColorArgb = MaterialTheme.colorScheme.onSurface.toArgb()
    val fillColorArgb = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f).toArgb()
    val accentColorArgb = MaterialTheme.colorScheme.onSurface.toArgb()

    val basePath: Path = remember(spec) { PathParser.createPathFromPathData(spec.pathData) }
    val baseFillPath: Path? = remember(spec) {
        spec.fillPathData?.let { PathParser.createPathFromPathData(it) }
    }
    val baseAccentPath: Path? = remember(spec) {
        spec.accentPathData?.let { PathParser.createPathFromPathData(it) }
    }

    Box(
        modifier = modifier.drawWithContent {
            val vw = spec.viewportWidth
            val vh = spec.viewportHeight
            val scale = min(size.width / vw, size.height / vh) * 0.75f
            val dx = (size.width - vw * scale) / 2f
            val dy = (size.height - vh * scale) / 2f
            val matrix = Matrix().apply { setScale(scale, scale); postTranslate(dx, dy) }

            val outlinePath = Path(basePath).apply { transform(matrix) }
            val fillPath: Path? = baseFillPath?.let { Path(it).apply { transform(matrix) } }
            val accentPath: Path? = baseAccentPath?.let { Path(it).apply { transform(matrix) } }

            drawIntoCanvas { canvas ->
                fillPath?.let {
                    canvas.nativeCanvas.drawPath(it, Paint().apply {
                        color = fillColorArgb
                        style = Paint.Style.FILL
                        isAntiAlias = true
                    })
                }
                canvas.nativeCanvas.drawPath(outlinePath, Paint().apply {
                    color = strokeColorArgb
                    strokeWidth = scale * 0.7f
                    style = Paint.Style.STROKE
                    isAntiAlias = true
                })
                accentPath?.let {
                    canvas.nativeCanvas.drawPath(it, Paint().apply {
                        color = accentColorArgb
                        style = Paint.Style.FILL
                        isAntiAlias = true
                    })
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {}
}

@Composable
fun BackGesturePreview(modifier: Modifier = Modifier) {
    val dotColor = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier.drawWithContent {
            drawDotTrail(dotColor)
        },
        contentAlignment = Alignment.Center,
    ) {}
}

private fun DrawScope.drawDotTrail(color: Color) {
    val d = density
    val lengthPx = 12f * d
    val heightPx = 13.5f * d
    val strokePx = 1.5f * d
    val scaleFactor = min(size.width, size.height) * 0.4f / (heightPx * 2f)

    val r = strokePx * scaleFactor
    val scaledX = lengthPx * scaleFactor
    val scaledY = heightPx * scaleFactor
    val originX = size.width / 2f - scaledX / 2f
    val cy = size.height / 2f

    drawCircle(color, radius = r, center = Offset(originX, cy))
    for (i in 1..3) {
        val ratio = i.toFloat() / 3f
        val xPos = originX + scaledX * ratio
        val yPos = scaledY * ratio
        drawCircle(color, radius = r, center = Offset(xPos, cy + yPos))
        drawCircle(color, radius = r, center = Offset(xPos, cy - yPos))
    }
}

@Composable
private fun MotoChargingPreview(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "moto_progress")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 100f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2500, easing = LinearEasing)
        ),
        label = "progress"
    )

    val arcColor = Color(0xFF1C6AFF)
    val thinRingColor = Color(0xFF1C6AFF).copy(alpha = 0.35f)
    val textColor = Color.White

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.preview_charging_moto_bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val squareSide = min(size.width, size.height) * 0.62f
            val left = (size.width - squareSide) / 2f
            val top = (size.height - squareSide) / 2f

            val thinInset = squareSide * 0.06f
            val arcLeft = left + thinInset
            val arcTop = top + thinInset
            val arcSize = Size(squareSide - thinInset * 2f, squareSide - thinInset * 2f)

            drawArc(
                color = thinRingColor,
                startAngle = 97.5f,
                sweepAngle = 345f,
                useCenter = false,
                topLeft = Offset(arcLeft, arcTop),
                size = arcSize,
                style = Stroke(width = 3f, cap = StrokeCap.Round)
            )

            val sweepAngle = progress / 100f * 345f
            drawArc(
                color = arcColor,
                startAngle = 97.5f,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset(arcLeft, arcTop),
                size = arcSize,
                style = Stroke(width = squareSide * 0.09f, cap = StrokeCap.Round)
            )

            drawIntoCanvas { canvas ->
                val textPaint = Paint().apply {
                    color = textColor.toArgb()
                    textSize = squareSide * 0.28f
                    isFakeBoldText = true
                    isAntiAlias = true
                    textAlign = Paint.Align.CENTER
                }
                val percentPaint = Paint().apply {
                    color = textColor.copy(alpha = 0.7f).toArgb()
                    textSize = squareSide * 0.11f
                    isAntiAlias = true
                    textAlign = Paint.Align.CENTER
                }
                val cx = size.width / 2f
                val cy = size.height / 2f
                val level = progress.toInt()
                canvas.nativeCanvas.drawText("$level", cx, cy + textPaint.textSize * 0.35f, textPaint)
                canvas.nativeCanvas.drawText("%", cx + squareSide * 0.17f, cy - textPaint.textSize * 0.1f, percentPaint)
            }
        }
    }
}

private val NOTHING_FRAMES = listOf(
    R.drawable.preview_charging_nothing_ripple_01,
    R.drawable.preview_charging_nothing_ripple_05,
    R.drawable.preview_charging_nothing_ripple_09,
    R.drawable.preview_charging_nothing_ripple_13,
    R.drawable.preview_charging_nothing_ripple_17,
    R.drawable.preview_charging_nothing_ripple_20,
    R.drawable.preview_charging_nothing_ripple_25,
    R.drawable.preview_charging_nothing_ripple_29,
    R.drawable.preview_charging_nothing_ripple_33,
    R.drawable.preview_charging_nothing_ripple_37,
    R.drawable.preview_charging_nothing_ripple_41,
)


@Composable
fun ChargingAnimationBannerPreview(packageName: String, modifier: Modifier = Modifier) {
    val style = packageName.substringAfterLast('.')
    if (style == "moto") {
        MotoChargingPreview(modifier = modifier)
        return
    }
    if (style != "nothing") return

    val frameCount = NOTHING_FRAMES.size
    val infiniteTransition = rememberInfiniteTransition(label = "charging_anim")
    val animatedIndex by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = frameCount.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = frameCount * 80, easing = LinearEasing)
        ),
        label = "frame_index"
    )
    val frameIndex = animatedIndex.toInt().coerceIn(0, frameCount - 1)

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(NOTHING_FRAMES[frameIndex]),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }
}
