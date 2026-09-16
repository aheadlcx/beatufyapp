# WebRTC 直播信令服务器（零依赖）

纯 Node.js 实现（内置 `http` + `crypto`，手写 RFC6455 WebSocket），**无需 `npm install`**。
只做信令转发与房间管理，音视频数据在客户端之间 P2P 直连（STUN 打洞），服务器零媒体转发负担。

## 运行

```bash
node server.js [port]    # 默认 8080，绑定 0.0.0.0
```

状态页：`http://<host>:<port>/` 返回 JSON（房间列表与人数），用于确认公网可达性。

## 部署到公网（直播场景必需）

1. 任意有公网 IP 的 VPS：上传 `server.js` + `package.json`，`node server.js 8080` 即可；
2. 安全组/防火墙放行 TCP 8080（信令）；
3. 客户端"信令地址"填 `ws://<公网IP>:8080`。生产环境建议套 Nginx/TLS 升级为 `wss://`
   （Android 9+ 默认禁止明文，当前 demo 在 Manifest 中已放行 `usesCleartextTraffic`）；
4. 媒体走 P2P：客户端内置 Google 公共 STUN。若双方都在严格 NAT 后（如公司网络），
   需要自建 TURN（coturn），并在 `BroadcasterSession/ViewerSession` 的 iceServers 里追加。

## 协议

见 `server.js` 顶部注释。消息为 JSON 文本帧，核心流程：

```
主播                         服务器                        观众
  │ join(role=broadcaster)      │                            │
  │ ←── joined ──────────────── │                            │
  │                             │ ←──── join(role=viewer) ───│
  │ ←─ viewer-joined(v1) ────── │──── joined(v1) ──────────→ │
  │ ── offer(v1) ─────────────→ │──── offer ───────────────→ │
  │ ←─ answer(v1) ───────────── │←──── answer ────────────── │
  │ ←─ candidate(v1) ─────────→ │──── candidate ──────────→  │
  │ ── candidate(v1) ─────────→ │──── candidate ──────────→  │
  │        （此后音视频 RTP/RTCP 在主播与观众之间 P2P 直连）      │
```

## 已验证

- 房间创建/主播占位/观众入房计数（状态页与客户端 onViewerCount 回调）
- 主播端 ICE 候选产出并转发至观众方向
- Windows/Node v24 实测运行
