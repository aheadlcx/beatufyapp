/**
 * WebRTC 直播信令服务器（零依赖：Node 内置 http + crypto，手写 RFC6455 WebSocket）。
 *
 * 职责（只做信令，不碰媒体——音视频数据在客户端之间 P2P 直连）：
 *   1. 房间管理：每个房间一个主播（broadcaster），多个观众（viewer）
 *   2. 消息转发：offer / answer / ICE candidate 在主播与观众之间按 viewerId 定向转发
 *   3. 房间事件：观众进出、在线人数广播
 *
 * 协议（JSON 文本帧）：
 *   客户端 → 服务器：
 *     {type:"join",  role:"broadcaster"|"viewer", room:"r1"}
 *     {type:"offer", viewerId:"v1", sdp:{...}}            // 仅主播：给某个观众的 offer
 *     {type:"answer", sdp:{...}}                          // 仅观众：给主播的 answer
 *     {type:"candidate", viewerId?, candidate:{...}}      // 双向，观众侧无需 viewerId
 *     {type:"chat", text:"..."}                           // 弹幕/聊天
 *   服务器 → 客户端：
 *     {type:"viewer-joined", viewerId}                    // → 主播
 *     {type:"joined", viewerId, viewers}                  // → 观众（入房确认）
 *     {type:"offer", viewerId, sdp}                       // → 对应观众
 *     {type:"answer", viewerId, sdp}                      // → 主播
 *     {type:"candidate", viewerId?, candidate}            // 双向转发
 *     {type:"viewer-left", viewerId}                      // → 主播
 *     {type:"viewers", count:n}                           // → 房间所有人
 *     {type:"chat", from, text}                           // → 房间所有人
 *     {type:"error", message}
 *
 * 运行：node server.js [port]   （默认 8080，绑定 0.0.0.0）
 */
'use strict';

const http = require('http');
const crypto = require('crypto');

const PORT = Number(process.argv[2]) || 8080;
const MAGIC = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

/** roomId → room 状态 */
const rooms = new Map();

function getRoom(id) {
  let r = rooms.get(id);
  if (!r) {
    r = { broadcaster: null, viewers: new Map() }; // viewers: connId → conn
    rooms.set(id, r);
  }
  return r;
}

function roomSnapshot(room) {
  return {
    broadcaster: room.broadcaster ? 1 : 0,
    viewers: room.viewers.size,
  };
}

// ---------------- WebSocket 帧（RFC6455 服务端所需的最小实现） ----------------

/** 解析一个客户端掩码帧。返回 {opcode, payload, rest}；数据不足返回 null。 */
function decodeFrame(buf) {
  if (buf.length < 2) return null;
  const fin = (buf[0] & 0x80) !== 0;
  const opcode = buf[0] & 0x0f;
  const masked = (buf[1] & 0x80) !== 0;
  let len = buf[1] & 0x7f;
  let off = 2;
  if (len === 126) {
    if (buf.length < 4) return null;
    len = buf.readUInt16BE(2);
    off = 4;
  } else if (len === 127) {
    if (buf.length < 10) return null;
    len = Number(buf.readBigUInt64BE(2));
    off = 10;
  }
  if (!masked) return { error: 'client frames must be masked' };
  if (buf.length < off + 4 + len) return null;
  const mask = buf.slice(off, off + 4);
  const payload = Buffer.alloc(len);
  const data = buf.slice(off + 4, off + 4 + len);
  for (let i = 0; i < len; i++) payload[i] = data[i] ^ mask[i & 3];
  return { fin, opcode, payload, rest: buf.slice(off + 4 + len) };
}

function encodeFrame(opcode, payload) {
  const len = payload.length;
  let header;
  if (len < 126) {
    header = Buffer.from([0x80 | opcode, len]);
  } else if (len < 65536) {
    header = Buffer.alloc(4);
    header[0] = 0x80 | opcode;
    header[1] = 126;
    header.writeUInt16BE(len, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x80 | opcode;
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(len), 2);
  }
  return Buffer.concat([header, payload]);
}

function wsSend(conn, obj) {
  if (conn.socket.destroyed) return;
  conn.socket.write(encodeFrame(0x1, Buffer.from(JSON.stringify(obj))));
}

function wsClose(conn, code) {
  if (conn.socket.destroyed) return;
  const body = Buffer.alloc(2);
  body.writeUInt16BE(code || 1000, 0);
  conn.socket.write(encodeFrame(0x8, body));
  conn.socket.end();
}

// ---------------- 房间广播 ----------------

function sendTo(conn, obj) {
  wsSend(conn, obj);
}

/** 广播给房间内所有人（可选排除某一个连接）。 */
function broadcast(room, obj, except) {
  if (room.broadcaster && room.broadcaster !== except) sendTo(room.broadcaster, obj);
  for (const v of room.viewers.values()) {
    if (v !== except) sendTo(v, obj);
  }
}

function notifyViewers(room) {
  broadcast(room, { type: 'viewers', count: room.viewers.size });
}

function leaveRoom(conn) {
  const room = rooms.get(conn.room);
  if (!room) return;
  if (conn.role === 'broadcaster' && room.broadcaster === conn) {
    room.broadcaster = null;
    // 主播离开：房间解散，通知所有观众
    for (const v of room.viewers.values()) sendTo(v, { type: 'broadcaster-left' });
    for (const v of room.viewers.values()) v.isViewerOf = null;
    console.log(`[room ${conn.room}] broadcaster left, ${room.viewers.size} viewers dropped`);
    if (room.viewers.size === 0) rooms.delete(conn.room);
  } else if (conn.connId && room.viewers.has(conn.connId)) {
    room.viewers.delete(conn.connId);
    if (room.broadcaster) sendTo(room.broadcaster, { type: 'viewer-left', viewerId: conn.connId });
    notifyViewers(room);
    console.log(`[room ${conn.room}] viewer ${conn.connId} left, ${room.viewers.size} remain`);
    if (!room.broadcaster && room.viewers.size === 0) rooms.delete(conn.room);
  }
}

function handleSignal(conn, msg) {
  const room = rooms.get(conn.room);
  if (!room) return;

  switch (msg.type) {
    case 'offer': {
      // 主播 → 指定观众
      if (conn.role !== 'broadcaster') return;
      const v = room.viewers.get(msg.viewerId);
      console.log(`[room ${conn.room}] offer from broadcaster → ${msg.viewerId}: ${v ? 'forwarded' : 'VIEWER NOT FOUND'}`);
      if (v) sendTo(v, { type: 'offer', sdp: msg.sdp });
      break;
    }
    case 'answer': {
      // 观众 → 主播
      if (conn.role !== 'viewer' || !conn.isViewerOf) return;
      const b = rooms.get(conn.isViewerOf) && rooms.get(conn.isViewerOf).broadcaster;
      if (b) sendTo(b, { type: 'answer', viewerId: conn.connId, sdp: msg.sdp });
      break;
    }
    case 'candidate': {
      // ICE 候选双向转发
      console.log(`[room ${conn.room}] candidate from ${conn.role} (${conn.connId || 'broadcaster'})`);
      if (conn.role === 'broadcaster') {
        const v = room.viewers.get(msg.viewerId);
        if (v) sendTo(v, { type: 'candidate', candidate: msg.candidate });
      } else if (conn.isViewerOf) {
        const br = rooms.get(conn.isViewerOf);
        if (br && br.broadcaster) {
          sendTo(br.broadcaster, { type: 'candidate', viewerId: conn.connId, candidate: msg.candidate });
        }
      }
      break;
    }
    case 'chat': {
      broadcast(room, { type: 'chat', from: conn.role === 'broadcaster' ? '主播' : '观众', text: String(msg.text).slice(0, 200) });
      break;
    }
    default:
      sendTo(conn, { type: 'error', message: 'unknown type ' + msg.type });
  }
}

function handleJoin(conn, msg) {
  if (conn.room) return; // 一个连接只进一个房间
  const roomId = String(msg.room || 'default').slice(0, 64);
  const room = getRoom(roomId);
  conn.room = roomId;

  if (msg.role === 'broadcaster') {
    if (room.broadcaster) {
      sendTo(conn, { type: 'error', message: 'room already has a broadcaster' });
      wsClose(conn, 4000);
      return;
    }
    room.broadcaster = conn;
    console.log(`[room ${roomId}] broadcaster joined`);
    sendTo(conn, { type: 'joined', viewers: room.viewers.size });
    notifyViewers(room);
  } else {
    if (!room.broadcaster) {
      sendTo(conn, { type: 'error', message: 'no broadcaster in this room yet' });
      wsClose(conn, 4001);
      return;
    }
    conn.connId = 'v' + crypto.randomBytes(4).toString('hex');
    conn.isViewerOf = roomId;
    room.viewers.set(conn.connId, conn);
    console.log(`[room ${roomId}] viewer ${conn.connId} joined (${room.viewers.size} total)`);
    sendTo(conn, { type: 'joined', viewerId: conn.connId, viewers: room.viewers.size });
    sendTo(room.broadcaster, { type: 'viewer-joined', viewerId: conn.connId });
    notifyViewers(room);
  }
}

// ---------------- HTTP + WS 升级 ----------------

const server = http.createServer((req, res) => {
  // 提供一个健康检查/状态页，方便确认服务器公网可达
  const list = [...rooms.entries()].map(([id, r]) => ({ id, ...roomSnapshot(r) }));
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ ok: true, rooms: list }));
});

server.on('upgrade', (req, socket) => {
  const key = req.headers['sec-websocket-key'];
  if (!key || (req.headers.upgrade || '').toLowerCase() !== 'websocket') {
    socket.destroy();
    return;
  }
  const accept = crypto.createHash('sha1').update(key + MAGIC).digest('base64');
  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\n' +
    'Upgrade: websocket\r\n' +
    'Connection: Upgrade\r\n' +
    `Sec-WebSocket-Accept: ${accept}\r\n\r\n`
  );

  const conn = { socket, room: null, role: null, connId: null, buf: Buffer.alloc(0) };
  socket.on('data', (chunk) => {
    conn.buf = Buffer.concat([conn.buf, chunk]);
    while (true) {
      const frame = decodeFrame(conn.buf);
      if (!frame) break;
      if (frame.error) { socket.destroy(); return; }
      conn.buf = frame.rest;
      if (frame.opcode === 0x8) { wsClose(conn, 1000); socket.destroy(); return; }
      if (frame.opcode === 0x9) { socket.write(encodeFrame(0xA, frame.payload)); continue; } // ping → pong
      if (frame.opcode !== 0x1) continue; // 只处理文本帧
      let msg;
      try {
        msg = JSON.parse(frame.payload.toString('utf8'));
      } catch (e) {
        sendTo(conn, { type: 'error', message: 'bad json' });
        continue;
      }
      try {
        if (msg.type === 'join') handleJoin(conn, msg);
        else if (conn.room) handleSignal(conn, msg);
      } catch (e) {
        console.error('handle error:', e.message);
      }
    }
  });
  socket.on('close', () => leaveRoom(conn));
  socket.on('error', () => leaveRoom(conn));
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`signaling server listening on 0.0.0.0:${PORT}`);
  console.log(`status page: http://<this-host>:${PORT}/  (JSON)`);
});
