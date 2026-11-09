// Dependency-free WebSocket client to verify the /relay route speaks protocol v0.
// Scenarios:
//   1. Client A streams one stroke as 3 `stroke` batches (last has done = true). Client B
//      (already connected) receives each batch, renumbered by the server, and A gets no echo.
//   2. A late joiner C connects after the stroke and receives a `canvas_snapshot` with the
//      whole stroke (all points, in order, done = true).
//   3. A bad message gets an `error` with code BAD_MESSAGE back.
// Run with the server up:  node server/tools/relay-test.js
const net = require("net");
const crypto = require("crypto");

const HOST = "127.0.0.1";
const PORT = 8080;
const PATH = "/relay";

function connect(name, onText, onOpen) {
  const key = crypto.randomBytes(16).toString("base64");
  const sock = net.connect(PORT, HOST, () => {
    sock.write(
      `GET ${PATH} HTTP/1.1\r\n` +
        `Host: ${HOST}:${PORT}\r\n` +
        `Upgrade: websocket\r\n` +
        `Connection: Upgrade\r\n` +
        `Sec-WebSocket-Key: ${key}\r\n` +
        `Sec-WebSocket-Version: 13\r\n\r\n`
    );
  });

  let handshakeDone = false;
  let buf = Buffer.alloc(0);

  sock.on("data", (chunk) => {
    buf = Buffer.concat([buf, chunk]);
    if (!handshakeDone) {
      const idx = buf.indexOf("\r\n\r\n");
      if (idx === -1) return;
      handshakeDone = true;
      buf = buf.slice(idx + 4);
      onOpen && onOpen();
    }
    while (buf.length >= 2) {
      const opcode = buf[0] & 0x0f;
      let len = buf[1] & 0x7f;
      let offset = 2;
      if (len === 126) {
        if (buf.length < 4) return;
        len = buf.readUInt16BE(2);
        offset = 4;
      } else if (len === 127) {
        if (buf.length < 10) return;
        len = Number(buf.readBigUInt64BE(2));
        offset = 10;
      }
      if (buf.length < offset + len) return;
      const payload = buf.slice(offset, offset + len);
      buf = buf.slice(offset + len);
      if (opcode === 0x1) onText(payload.toString("utf8"));
      else if (opcode === 0x8) sock.end();
    }
  });

  sock.on("error", (e) => console.log(`[${name}] error:`, e.message));

  return {
    sendText(str) {
      const payload = Buffer.from(str, "utf8");
      const mask = crypto.randomBytes(4);
      const header = [0x81];
      if (payload.length < 126) header.push(0x80 | payload.length);
      else header.push(0x80 | 126, (payload.length >> 8) & 0xff, payload.length & 0xff);
      const masked = Buffer.from(payload);
      for (let i = 0; i < masked.length; i++) masked[i] ^= mask[i % 4];
      sock.write(Buffer.concat([Buffer.from(header), mask, masked]));
    },
    close() {
      sock.end();
    },
  };
}

const aGot = [];
const bGot = [];
const cGot = [];
let a;
const parse = (arr) => arr.map((t) => JSON.parse(t));
const stroke = (points, done) =>
  JSON.stringify({ type: "stroke", strokeId: 1, points, color: "#1565C0", size: 2, done });

const b = connect("B", (t) => bGot.push(t), () => {
  // B is connected (and will have received its own empty snapshot). Now connect A.
  a = connect("A", (t) => aGot.push(t), () => {
    setTimeout(() => a.sendText(stroke([[0.1, 0.2, 0], [0.15, 0.25, 16]], false)), 100);
    setTimeout(() => a.sendText(stroke([[0.2, 0.3, 50]], false)), 200);
    setTimeout(() => a.sendText(stroke([[0.25, 0.35, 100]], true)), 300);
    setTimeout(() => a.sendText('{"type":"stroke","oops":true}'), 400);
  });
});

// Late joiner C connects after the stroke is done.
let c;
setTimeout(() => {
  c = connect("C", (t) => cGot.push(t));
}, 700);

setTimeout(() => {
  const bMsgs = parse(bGot);
  const aMsgs = parse(aGot);
  const cMsgs = parse(cGot);
  const bStrokes = bMsgs.filter((m) => m.type === "stroke");

  console.log("B received:", JSON.stringify(bMsgs));
  console.log("A received:", JSON.stringify(aMsgs));
  console.log("C received:", JSON.stringify(cMsgs));

  const snap = cMsgs.find((m) => m.type === "canvas_snapshot");
  const snapStroke = snap && snap.strokes.length === 1 ? snap.strokes[0] : null;
  const checks = [
    ["B got an empty canvas_snapshot on connect", bMsgs[0]?.type === "canvas_snapshot" && bMsgs[0].strokes.length === 0],
    ["B saw 3 stroke batches", bStrokes.length === 3],
    ["all 3 batches share one strokeId", new Set(bStrokes.map((m) => m.strokeId)).size === 1],
    ["only the last batch has done = true", bStrokes.map((m) => m.done).join() === "false,false,true"],
    ["color and size came through", bStrokes.every((m) => m.color === "#1565C0" && m.size === 2)],
    ["A got no echo of its own strokes", !aMsgs.some((m) => m.type === "stroke")],
    ["A got BAD_MESSAGE for the broken message", aMsgs.some((m) => m.type === "error" && m.code === "BAD_MESSAGE")],
    ["C snapshot has the whole stroke (4 points, done)", snapStroke?.points.length === 4 && snapStroke.done === true],
    ["C snapshot points are in order", snapStroke?.points.map((p) => p[2]).join() === "0,16,50,100"],
  ];
  checks.forEach(([label, ok]) => console.log(`${ok ? "PASS" : "FAIL"}: ${label}`));
  const allPass = checks.every(([, ok]) => ok);
  console.log(allPass ? "\nALL PASS" : "\nSOME FAILED");

  a && a.close();
  b.close();
  c && c.close();
  process.exit(allPass ? 0 : 1);
}, 1200);
