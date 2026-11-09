package com.blitz.inklings.ui

import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.ink.authoring.compose.InProgressStrokes
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.compose.createWithComposeColor
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.blitz.inklings.net.RemoteStroke
import com.blitz.inklings.net.StrokeRelay
import com.blitz.inklings.net.sizeLevelFor
import com.blitz.inklings.net.toHex
import com.blitz.inklings.net.toInkStroke
import com.blitz.inklings.net.toProtocolPoint
import com.blitz.inklings.net.toProtocolPoints
import it.inkorporate.protocol.ServerMessage

@Composable
fun DrawingScreen() {
    var finishedStrokes by remember { mutableStateOf<List<Stroke>>(emptyList()) }
    var selectedColor by remember { mutableStateOf(Color.Black) }
    var brushSize by remember { mutableFloatStateOf(5f) }
    var brushFamily by remember { mutableStateOf(StockBrushes.pressurePen()) }

    var currentBrush by remember(brushFamily, selectedColor, brushSize) {
        mutableStateOf(
            Brush.createWithComposeColor(
                family = brushFamily,
                color = selectedColor,
                size = brushSize,
                epsilon = 0.1f
            )
        )
    }

    val canvasStrokeRenderer = remember { CanvasStrokeRenderer.create() }

    // Size of the drawing area in pixels. Protocol points are 0..1, so this is what turns
    // them back into positions on this phone's screen.
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Strokes from other phones, oldest first, kept as protocol data (0..1 points) so they
    // can be redrawn correctly whatever this canvas's size is. A stroke still being drawn
    // has done = false and grows as batches arrive.
    var remoteStrokes by remember { mutableStateOf<List<RemoteStroke>>(emptyList()) }
    // Ink strokes built from remoteStrokes, reused until a stroke gets new points or the
    // canvas size changes, so each frame doesn't rebuild every stroke.
    val remoteInkCache = remember { HashMap<Int, RemoteInk>() }

    // Stroke relay (protocol v0): live streaming in both directions, catch-up on connect.
    val relay = remember {
        StrokeRelay(listener = object : StrokeRelay.RelayListener {
            override fun onSnapshot(strokes: List<ServerMessage.Stroke>) {
                // Replace remote strokes with the server's copy of the canvas.
                remoteStrokes = strokes.map { it.toRemoteStroke() }
                remoteInkCache.clear()
            }

            override fun onRemoteStroke(batch: ServerMessage.Stroke) {
                val i = remoteStrokes.indexOfFirst { it.strokeId == batch.strokeId }
                remoteStrokes = if (i < 0) {
                    remoteStrokes + batch.toRemoteStroke()
                } else {
                    remoteStrokes.toMutableList().also {
                        val old = it[i]
                        it[i] = old.copy(points = old.points + batch.points, done = batch.done)
                    }
                }
            }

            override fun onUndo() {
                val last = remoteStrokes.lastOrNull() ?: return
                remoteStrokes = remoteStrokes.dropLast(1)
                remoteInkCache.remove(last.strokeId)
            }

            override fun onClear() {
                finishedStrokes = emptyList()
                remoteStrokes = emptyList()
                remoteInkCache.clear()
            }
        })
    }
    DisposableEffect(relay) {
        relay.connect()
        onDispose { relay.close() }
    }

    // Loupe: mirrors the finger stroke being drawn so it can be shown magnified beside the finger
    val latestBrush by rememberUpdatedState(currentBrush)
    var liveStroke by remember { mutableStateOf<InProgressStroke?>(null) }
    // Id of the local stroke currently being streamed to the relay (finger strokes only,
    // since the loupe/manual path that gives us live points is finger-only). Null when no
    // finger stroke is in flight.
    val liveRelayId = remember { arrayOfNulls<Int>(1) }
    var loupeFocus by remember { mutableStateOf(Offset.Zero) }
    var loupeFrame by remember { mutableIntStateOf(0) }
    val loupeZonePx = with(LocalDensity.current) { (LOUPE_SIZE + LOUPE_MARGIN * 2).toPx() }

    Scaffold(
        topBar = {
            DrawingToolbar(
                selectedColor = selectedColor,
                selectedSize = brushSize,
                selectedBrushFamily = brushFamily,
                onColorSelected = {
                    selectedColor = it
                },
                onSizeSelected = {
                    brushSize = it
                },
                onBrushFamilySelected = {
                    brushFamily = it
                },
                onClear = {
                    finishedStrokes = emptyList()
                    remoteStrokes = emptyList()
                    remoteInkCache.clear()
                    relay.sendClear()
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .onSizeChanged { canvasSize = it }
                // Observe touches on the Initial pass without consuming, so InProgressStrokes
                // still receives every event
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        var trackedId: PointerId? = null
                        var downTime = 0L
                        var lastPos = Offset.Unspecified

                        fun addInput(stroke: InProgressStroke, pos: Offset, uptime: Long) {
                            // Ink rejects consecutive duplicate inputs
                            if (pos == lastPos) return
                            val elapsed = uptime - downTime
                            val batch = MutableStrokeInputBatch()
                                .add(InputToolType.TOUCH, pos.x, pos.y, elapsed)
                            runCatching {
                                stroke.enqueueInputs(batch, MutableStrokeInputBatch())
                                stroke.updateShape(elapsed)
                            }
                            // Queue this point for the next live batch (0..1 coordinates).
                            liveRelayId[0]?.let { id ->
                                relay.streamPoints(id, listOf(toProtocolPoint(pos.x, pos.y, elapsed, size)))
                            }
                            lastPos = pos
                        }

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            for (change in event.changes) {
                                if (trackedId == null) {
                                    val isFinger = change.type != PointerType.Stylus &&
                                        change.type != PointerType.Eraser
                                    if (change.changedToDown() && isFinger) {
                                        trackedId = change.id
                                        downTime = change.uptimeMillis
                                        lastPos = Offset.Unspecified
                                        val stroke = InProgressStroke().apply { start(latestBrush) }
                                        // Start streaming this finger stroke live. Begin
                                        // before the first point so points have a target id.
                                        liveRelayId[0] = relay.beginStroke(
                                            color = Color(latestBrush.colorIntArgb).toHex(),
                                            size = sizeLevelFor(latestBrush.size),
                                        )
                                        addInput(stroke, change.position, change.uptimeMillis)
                                        liveStroke = stroke
                                        loupeFocus = change.position
                                        loupeFrame++
                                    }
                                } else if (change.id == trackedId) {
                                    val stroke = liveStroke ?: continue
                                    if (change.changedToUp() || !change.pressed) {
                                        stroke.finishInput()
                                        // Finish the live stream for this finger stroke.
                                        liveRelayId[0]?.let { relay.endStroke(it) }
                                        liveRelayId[0] = null
                                        liveStroke = null
                                        trackedId = null
                                    } else {
                                        change.historical.forEach {
                                            addInput(stroke, it.position, it.uptimeMillis)
                                        }
                                        addInput(stroke, change.position, change.uptimeMillis)
                                        loupeFocus = change.position
                                        loupeFrame++
                                    }
                                }
                            }
                        }
                    }
                }
        ) {
            // Dry layer: render completed strokes AND remote strokes still streaming in,
            // onto the native Android Canvas. Reading remoteStrokes here makes the canvas
            // redraw as each remote point arrives, so remote drawings appear live.
            Canvas(modifier = Modifier.fillMaxSize()) {
                val identityMatrix = Matrix()
                drawIntoCanvas { composeCanvas ->
                    finishedStrokes.forEach { stroke ->
                        canvasStrokeRenderer.draw(
                            stroke = stroke,
                            canvas = composeCanvas.nativeCanvas,
                            strokeToScreenTransform = identityMatrix
                        )
                    }
                    remoteStrokes.forEach { remote ->
                        val cached = remoteInkCache[remote.strokeId]
                        val ink = if (cached != null && cached.pointCount == remote.points.size &&
                            cached.canvas == canvasSize
                        ) {
                            cached.stroke
                        } else {
                            remote.toInkStroke(canvasSize).also {
                                remoteInkCache[remote.strokeId] =
                                    RemoteInk(remote.points.size, canvasSize, it)
                            }
                        }
                        ink?.let { stroke ->
                            canvasStrokeRenderer.draw(
                                stroke = stroke,
                                canvas = composeCanvas.nativeCanvas,
                                strokeToScreenTransform = identityMatrix
                            )
                        }
                    }
                }
            }

            // Wet layer: capture touch input and render the active stroke
            InProgressStrokes(
                defaultBrush = currentBrush,
                nextBrush = { Brush.createWithComposeColor(
                    family = brushFamily,
                    color = selectedColor,
                    size = brushSize,
                    epsilon = 0.1f
                )},
                onStrokesFinished = { newStrokes ->
                    finishedStrokes = finishedStrokes + newStrokes
                    // Finger strokes were already streamed live from the manual path above,
                    // so re-sending them here would duplicate them on other screens (and in
                    // the server snapshot). The finger path uses TOUCH input; stylus/eraser
                    // strokes are NOT streamed live, so send only those, as one finished batch.
                    newStrokes.forEach { stroke ->
                        if (!wasStreamedLive(stroke)) {
                            relay.sendWholeStroke(
                                color = Color(stroke.brush.colorIntArgb).toHex(),
                                size = sizeLevelFor(stroke.brush.size),
                                points = stroke.toProtocolPoints(canvasSize),
                            )
                        }
                    }
                }
            )

            liveStroke?.let { stroke ->
                // Jump to the other corner when the finger nears the loupe
                val fingerInCorner = loupeFocus.x < loupeZonePx && loupeFocus.y < loupeZonePx
                DrawingLoupe(
                    finishedStrokes = finishedStrokes,
                    liveStroke = stroke,
                    focus = loupeFocus,
                    frame = loupeFrame,
                    renderer = canvasStrokeRenderer,
                    modifier = Modifier
                        .align(if (fingerInCorner) Alignment.TopEnd else Alignment.TopStart)
                        .padding(LOUPE_MARGIN)
                )
            }
        }
    }
}

/**
 * True if a finished stroke was already streamed live by the finger path. That path
 * records input as [InputToolType.TOUCH]; stylus and eraser strokes use other tool types
 * and are not streamed, so they still need a one-shot legacy send.
 */
private fun wasStreamedLive(stroke: Stroke): Boolean {
    val inputs = stroke.inputs
    if (inputs.size == 0) return false
    return inputs.get(0).toolType == InputToolType.TOUCH
}

/** A remote stroke's ink, plus what it was built from, so it is only rebuilt on change. */
private class RemoteInk(val pointCount: Int, val canvas: IntSize, val stroke: Stroke?)

private fun ServerMessage.Stroke.toRemoteStroke() =
    RemoteStroke(strokeId, color, size, points, done)
