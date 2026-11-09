package com.blitz.inklings.net

import android.os.Handler
import android.os.Looper
import android.util.Log
import it.inkorporate.protocol.ClientMessage
import it.inkorporate.protocol.ServerMessage
import it.inkorporate.protocol.decodeServer
import it.inkorporate.protocol.encodeClient
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Client for the inklings-server `/relay` route, speaking protocol v0
 * (protocol/src/it/inkorporate/protocol/Protocol.kt).
 *
 * Sending: a stroke is streamed live while it is drawn. Points are queued and sent as one
 * `stroke` message every [BATCH_INTERVAL_MS]; the last batch of a stroke has `done = true`.
 * Points must already be normalized to 0..1 ([x, y, msSinceStrokeStart]).
 *
 * Receiving: `stroke`, `undo`, `clear` and `canvas_snapshot` (sent once on connect so a late
 * joiner sees the drawing so far) are delivered through [listener] on the main thread.
 *
 * Not ink-specific on purpose: converting between ink strokes and protocol points lives in
 * StrokeCodec.kt, because it needs the canvas size.
 *
 * NOTE: with localhost, the server is ONLY reachable through adb. `localhost` on a device
 * means the device itself, so each device needs an adb tunnel to the PC:
 *
 *     adb reverse tcp:8080 tcp:8080          (add -s <device-id> if several are connected)
 *
 * The tunnel is lost when the device disconnects, the emulator restarts, or adb restarts —
 * run it again. Without it, Logcat shows "connection to ... failed".
 */
class StrokeRelay(
    private val url: String = "ws://localhost:8080/relay",
    private val listener: RelayListener,
) {
    /** Events from the server, on the main thread. */
    interface RelayListener {
        /** One batch of a remote stroke. Batches with the same strokeId add up to one stroke. */
        fun onRemoteStroke(batch: ServerMessage.Stroke)
        /** Remove the last stroke. */
        fun onUndo()
        /** Everyone's canvas was cleared. */
        fun onClear()
        /** Catch-up on connect: everything already on the canvas, oldest first. */
        fun onSnapshot(strokes: List<ServerMessage.Stroke>)
    }

    private val client = OkHttpClient()
    private val mainThread = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null

    fun connect() {
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "connected to $url")
            }

            // OkHttp calls this on a background thread.
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { decodeServer(text) }.getOrElse {
                    Log.w(TAG, "ignoring message we can't read: $text", it)
                    return
                }
                mainThread.post { dispatch(msg) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // No auto-reconnect in this minimal version: restart the app to retry.
                Log.w(TAG, "connection to $url failed", t)
            }
        })
    }

    private fun dispatch(msg: ServerMessage) {
        when (msg) {
            is ServerMessage.Stroke -> listener.onRemoteStroke(msg)
            is ServerMessage.CanvasSnapshot -> listener.onSnapshot(msg.strokes)
            ServerMessage.Undo -> listener.onUndo()
            ServerMessage.Clear -> listener.onClear()
            is ServerMessage.Error -> Log.w(TAG, "server error ${msg.code}: ${msg.message}")
            // Rooms, turns and guessing aren't wired into this screen yet.
            else -> Log.i(TAG, "not handled yet: $msg")
        }
    }

    // --- sending ----------------------------------------------------------------------

    /** A local stroke being streamed: its look, plus points not sent yet. */
    private class Outgoing(val color: String, val size: Int) {
        val pending = ArrayList<List<Float>>()
    }

    // Only touched on the main thread (touch input and the flush both run there).
    private val active = LinkedHashMap<Int, Outgoing>()
    private var nextStrokeId = 1
    private var flushScheduled = false
    private val flushRunnable = Runnable {
        flushScheduled = false
        for ((id, stroke) in active) {
            if (stroke.pending.isNotEmpty()) send(id, stroke, done = false)
        }
    }

    /** Start a new local stroke. Returns its strokeId for [streamPoints] / [endStroke]. */
    fun beginStroke(color: String, size: Int): Int {
        val id = nextStrokeId++
        active[id] = Outgoing(color, size)
        return id
    }

    /** Queue normalized points; they go out in the next 50 ms batch. Main thread only. */
    fun streamPoints(id: Int, points: List<List<Float>>) {
        val stroke = active[id] ?: return
        if (points.isEmpty()) return
        stroke.pending.addAll(points)
        if (!flushScheduled) {
            flushScheduled = true
            mainThread.postDelayed(flushRunnable, BATCH_INTERVAL_MS)
        }
    }

    /** Finish a local stroke: sends whatever is queued as the last batch, with done = true. */
    fun endStroke(id: Int) {
        val stroke = active.remove(id) ?: return
        send(id, stroke, done = true)
    }

    /** Send a stroke that wasn't streamed live (stylus) as one finished batch. */
    fun sendWholeStroke(color: String, size: Int, points: List<List<Float>>) {
        if (points.isEmpty()) return
        sendMessage(ClientMessage.Stroke(nextStrokeId++, points, color, size, done = true))
    }

    fun sendClear() = sendMessage(ClientMessage.Clear)

    fun sendUndo() = sendMessage(ClientMessage.Undo)

    private fun send(id: Int, stroke: Outgoing, done: Boolean) {
        sendMessage(ClientMessage.Stroke(id, stroke.pending.toList(), stroke.color, stroke.size, done))
        stroke.pending.clear()
    }

    private fun sendMessage(msg: ClientMessage) {
        val text = encodeClient(msg)
        val sent = socket?.send(text) ?: false
        if (!sent) Log.w(TAG, "not sent (not connected): $text")
    }

    fun close() {
        mainThread.removeCallbacks(flushRunnable)
        flushScheduled = false
        // Anything still being drawn is finished off so other screens don't keep a stroke open.
        for (id in active.keys.toList()) endStroke(id)
        socket?.close(1000, "screen closed")
        socket = null
    }

    companion object {
        private const val TAG = "StrokeRelay"

        /** How often queued live points are sent, in ms. Protocol v0 says about every 50 ms. */
        const val BATCH_INTERVAL_MS = 50L
    }
}
