# inklings-server — stroke relay

A minimal Ktor server that relays drawing strokes between Inklings apps.
Phones and the server speak **protocol v0** (`../protocol/src/it/inkorporate/protocol/Protocol.kt`,
the only copy, compiled by both the app and this server). Strokes stream live in ~50 ms
batches, and the server keeps the current canvas so a phone that joins late is caught up
with a `canvas_snapshot`. See "Drawing messages" below.

This folder is its **own Gradle build** (own wrapper, own versions: Ktor 3.6.1, Kotlin from
`gradle/libs.versions.toml`). It is *not* part of the Android app's build, so the two can use different versions.

## Quick start
1. **Server:** open this `server/` folder in IntelliJ IDEA (File → Open → `server`), let Gradle sync,
   then run `main` in `src/main/kotlin/main.kt`. It listens on **port 8080**.
   Check: http://localhost:8080 shows "Hello, World!".
2. **App:** open the repo root in Android Studio, run the app on an emulator or a USB-connected phone.
3. **For each device**, after it connects and before opening the app, tunnel its port 8080 to your PC:
   ```
   adb reverse tcp:8080 tcp:8080
   ```
   With several devices: `adb devices` for the ids, then `adb -s <id> reverse tcp:8080 tcp:8080`.
4. Draw on one device → it appears on the other(s).

## ⚠️ The app only reaches the server through adb
- The app connects to `ws://localhost:8080/relay` (`app/.../net/StrokeRelay.kt`).
  `localhost` on a device means *that device*, so the `adb reverse` tunnel above is required —
  on emulators and USB phones alike.
- The tunnel disappears when the device disconnects, the emulator restarts, or adb restarts → run it again.
  Symptom when missing: Logcat `StrokeRelay: connection to ws://localhost:8080/relay failed`.
- The app connects once, when the screen opens. Started the server late? Restart the app.
- Depends on the server being on port **8080** (`src/main/resources/application.yaml`).
  Different port → change the URL in `StrokeRelay.kt`, or map it: `adb reverse tcp:8080 tcp:<port>`.
- Tools on your PC (browser, Relay Pad, scripts) use `localhost:8080` directly — no adb needed.

## Testing without a second phone
- **Relay Pad** — open `tools/relay-pad.html` in a browser while the server runs. It acts as a second
  phone (1080×2170 canvas, the same shape as the emulator's drawing area, so nothing stretches): strokes drawn in the app show up there,
  and strokes drawn there show up in the app. It draws plain lines, not ink, so brushes look simpler.
- **Browser console** — at http://localhost:8080, press F12 → Console:
  ```js
  const ws = new WebSocket("ws://localhost:8080/relay"); ws.onmessage = e => console.log(e.data)
  ```
  Draw in the app → its `stroke` batches print. Send a red line back into the app:
  ```js
  ws.send(JSON.stringify({type:"stroke",strokeId:1,points:[[0.2,0.2,0],[0.8,0.8,200]],color:"#E53935",size:3,done:true}))
  ```
- **Automated check** — `node tools/relay-test.js` (server running) checks batches, renumbering,
  BAD_MESSAGE and the late-joiner snapshot. `node tools/relay-listen.js` prints what the app sends.

## How it works
- `src/main/kotlin/Websockets.kt` — installs the WebSockets plugin (pings every 15 s, 15 s timeout).
- `src/main/kotlin/Routing.kt`
  - `GET /` — "Hello, World!" health check.
  - `/ws` — echo: replies to the sender only (learning step).
  - `/relay` — broadcast. `sessions` is everyone connected (one shared canvas until rooms exist).
    On connect: send a `canvas_snapshot`, then add self. Each message is read with the protocol's
    `decodeClient`; a broken one gets `error` / `BAD_MESSAGE` back. `stroke`, `undo` and `clear`
    update the canvas copy and go to every session except the sender. Other messages
    (rooms, chat…) are ignored for now. One `Mutex` covers update + send and snapshot + join,
    so a joiner never gets a batch twice or misses one. `finally`: remove self.
- `src/main/kotlin/CanvasState.kt` — the server's copy of the drawing, for late joiners.
  Gives every stroke a **server strokeId**: phones count their strokes from 1, which would
  collide between phones (or after an app restart), so the server renumbers before relaying.

### Drawing messages (protocol v0)
- phone → server `stroke` — `{"type":"stroke","strokeId":1,"points":[[x,y,ms],...],"color":"#RRGGBB","size":1|2|3,"done":false}`
  sent about every 50 ms while drawing; the last batch of a stroke has `"done":true`.
  x and y are **0..1** (fraction of the canvas width / height), ms is time since the stroke started.
- server → other phones `stroke` — the same batch, with the server's strokeId.
- `undo` / `clear` — `{"type":"undo"}`, `{"type":"clear"}`, relayed to everyone else.
- server → a phone that just connected `canvas_snapshot` — `{"type":"canvas_snapshot","strokes":[<stroke>,...]}`,
  oldest first, each with all its points so far (`done:false` = still being drawn, more batches follow).

Not in the protocol, so not sent: brush type (other phones draw everything with the Pressure Pen),
pen pressure, and transparency.

## Tested (2026-11-04)
- Two Node clients on `/relay`: messages reach the other client, not the sender.
- Emulator ↔ Node client: app's stroke arrives as JSON; JSON stroke sent in appears in the app.
- Emulator ↔ Relay Pad: both directions.
- Galaxy S24 over USB ↔ emulator: strokes cross both ways, rebuilt by ink, no dropped or failed strokes.

## Known limits
- Points are a fraction of width and height separately, so a drawing **stretches** between
  screens with a different shape. Fix: give the canvas a fixed aspect ratio in the app.
- No rooms yet and anyone can draw (no NOT_DRAWER check). Two people drawing at once works,
  but `undo` removes the newest stroke whoever drew it.
- A phone that drops mid-stroke leaves that stroke unfinished (`done:false`) in the snapshot.
- Dense vertical streaks seen on the S24 with Pressure Pen are drawn locally by the phone, not by the server — to investigate.
- Phone in dark mode → dark canvas, black ink hard to see.
- No reconnect; plain `ws://` (cleartext allowed in the app manifest) — switch to `wss://` when deployed.

## Next
- Rooms (`create_room` / `join_room`, one canvas per room), only the drawer may draw.
- Fixed canvas aspect ratio in the app; an undo button.
- Deploy (fat JAR / Docker, then `wss://`).

## Gotchas
- **Terminal Gradle needs a full JDK 21**, not a JRE. If `./gradlew run` complains, point `JAVA_HOME` at a JDK
  (IntelliJ's downloaded JDKs live in `~/.jdks`), or just run from IntelliJ.
- Run on another port: `./gradlew run --args="-port=8081"`.
- Emulator won't boot or freezes on a black screen? Use software graphics:
  Device Manager → Edit → Show Advanced Settings → Graphics: Software,
  or `emulator -avd <name> -gpu swiftshader_indirect`.
