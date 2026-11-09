package com.blitz.inklings.net

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.compose.createWithComposeColor
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.blitz.inklings.ui.brushSizes

/*
 * Converts between what the canvas uses (ink strokes, pixels, Compose colors, brush widths)
 * and what protocol v0 sends (points in 0..1, "#RRGGBB", size 1/2/3).
 */

/** A pixel position on a canvas of [canvas] size as a protocol point [x, y, ms], x/y in 0..1. */
fun toProtocolPoint(x: Float, y: Float, elapsedMs: Long, canvas: IntSize): List<Float> = listOf(
    (x / canvas.width).coerceIn(0f, 1f),
    (y / canvas.height).coerceIn(0f, 1f),
    elapsedMs.toFloat(),
)

/** All the points of a finished ink stroke as protocol points. */
fun Stroke.toProtocolPoints(canvas: IntSize): List<List<Float>> =
    (0 until inputs.size).map { i ->
        val input = inputs.get(i)
        toProtocolPoint(input.x, input.y, input.elapsedTimeMillis, canvas)
    }

/** Compose color as "#RRGGBB". Transparency is dropped; the protocol has no alpha. */
fun Color.toHex(): String = String.format("#%06X", toArgb() and 0xFFFFFF)

/** "#RRGGBB" back to a color. Anything unreadable becomes black. */
fun colorFromHex(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Black)

/** Brush width in pixels to protocol size 1, 2 or 3 (the closest toolbar size). */
fun sizeLevelFor(width: Float): Int =
    brushSizes.minBy { kotlin.math.abs(it.size - width) }.level

/** Protocol size 1, 2 or 3 back to a brush width in pixels. */
fun widthForLevel(level: Int): Float =
    (brushSizes.firstOrNull { it.level == level } ?: brushSizes[1]).size

/**
 * A stroke from another phone, as protocol data. Points stay in 0..1 so the stroke can be
 * redrawn at the right place for any canvas size (late join, rotation).
 */
data class RemoteStroke(
    val strokeId: Int,
    val color: String,
    val size: Int,
    val points: List<List<Float>>,
    val done: Boolean,
)

/**
 * Builds an ink stroke from a remote stroke for a canvas of [canvas] size, or null if there
 * is nothing drawable yet. The protocol has no brush type, so remote strokes all use the
 * Pressure Pen (the app's default) without pressure data.
 */
fun RemoteStroke.toInkStroke(canvas: IntSize): Stroke? {
    if (canvas.width == 0 || canvas.height == 0) return null
    val batch = MutableStrokeInputBatch()
    var lastX = Float.NaN
    var lastY = Float.NaN
    var lastT = -1L
    for (p in points) {
        if (p.size < 3) continue
        val x = p[0] * canvas.width
        val y = p[1] * canvas.height
        val t = p[2].toLong()
        // Ink rejects repeated positions and time going backwards.
        if ((x == lastX && y == lastY) || t < lastT) continue
        runCatching { batch.add(InputToolType.TOUCH, x, y, t) }.onSuccess {
            lastX = x
            lastY = y
            lastT = t
        }
    }
    if (batch.size == 0) return null
    val brush = Brush.createWithComposeColor(
        family = StockBrushes.pressurePen(),
        color = colorFromHex(color),
        size = widthForLevel(size),
        epsilon = 0.1f,
    )
    return runCatching { Stroke(brush, batch) }.getOrNull()
}
