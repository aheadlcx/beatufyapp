# live 模块：WebRTC 直播（Android）

基于 libwebrtc（`io.getstream:stream-webrtc-android`，核心是 C++ 实现的 WebRTC 栈）
的直播推流/拉流模块。服务端见仓库 `server/` 目录。

## 结构与解耦

```
live/src/main/java/com/example/live/
├── signal/
│   ├── SignalClient.java   信令抽象接口（引擎只依赖它）
│   └── WsSignalClient.java WebSocket 实现（OkHttp；可另写 MqttSignalClient 等替换）
├── engine/
│   ├── SessionBase.java        会话骨架：ICE 分发、状态转发、生命周期
│   ├── BroadcasterSession.java 主播：采集 + 每观众一条 PC + C++ 统计/码率控制接入
│   ├── ViewerSession.java      观众：收 offer→answer→渲染远端轨
│   ├── LiveEngine.java         门面：UI 只与它交互
│   └── PeerFactoryProvider.java 进程级 PeerConnectionFactory/EGL 单例
├── core/
│   └── LiveStats.java          C++(JNI) 统计与 AIMD 码率控制的 Java 包装
└── ui/LiveActivity.java        界面（开播/观看/弹幕/统计展示）
live/src/main/cpp/livecore.cpp  C++ 核心：帧级滑动窗口统计 + AIMD 自适应码率
```

分层规则：`ui → engine → signal/core`，反向不依赖；`engine` 不 import okhttp；
`signal` 不 import org.webrtc 以外的引擎类。

## 角色

- **主播**：申请相机/麦克风权限 → `LiveEngine.startAsBroadcaster(url, room, localRenderer)`。
  每个观众进入时自动新建一条 PeerConnection 推流（mesh，适合几十人内规模）。
- **观众**：`LiveEngine.startAsViewer(url, room, remoteRenderer)`，收到 offer 自动应答并渲染。

## C++ 核心（livecore.cpp）

- `LiveStats.onFrame`：每帧回调（30fps 即每秒 30 次 JNI），C++ 侧 5 秒滑动窗口
  统计帧率/分辨率/帧间隔抖动，避免 Java 高频对象分配；
- `LiveStats.onLossSample/onRttSample`：每 2s 从 `getStats` 采集丢包/RTT，
  AIMD 算法输出建议码率，Java 侧应用到 `RtpSender.getParameters().encodings[0].maxBitrateBps`；
- `snapshotJson()`：一行 JSON 快照供 UI 展示。

## 调试

1. 启动信令：`node server/server.js 8080`
2. 模拟器访问宿主机：`ws://10.0.2.2:8080`；USB 真机可 `adb reverse tcp:8080 tcp:8080`
   后使用 `ws://127.0.0.1:8080`
3. 两台设备分别以 主播/观众 进入同一房间号即可。

## 已知问题与验证状态（模拟器调试结论）

| 环节 | 状态 |
|---|---|
| 信令服务器（join/房间/计数/转发） | ✅ 已验证（Node 测试客户端 + 双端实测） |
| Android 主播：图片轮播源 → 编码推流启动 | ✅ 已验证（本地预览渲染轮播帧） |
| 信令自动重连 | ✅ 已验证（首次失败后自动重连成功） |
| Android 观众：offer→answer→渲染 | ⚠️ 代码就绪，软渲染模拟器上未验证成功 |
| Web 观众：offer→answer→video 渲染 | ⚠️ 同上（信令/计数已通，媒体协商未完成） |

**遗留问题**：模拟器（SwiftShader 软渲染 + 2GB 内存）上，美颜管线与 libwebrtc
并发运行导致资源耗尽，一次 SIGABRT 发生在 libwebrtc 信令线程（2026-09-17 16:12，
见崩溃缓冲）。已按 libwebrtc 最佳实践将全部 PC 操作收敛到专用线程
（SessionBase.webrtcThread），但**完整媒体协商验证必须在真机上进行**
（硬件编解码 + 正常性能），软渲染模拟器不满足调试条件。

## 真机联调步骤

1. `node server/server.js 8080`（本机或公网 VPS）
2. 主播机：`adb reverse tcp:8080 tcp:8080` 后信令填 `ws://127.0.0.1:8080`，
   或直接填 VPS 地址；app 首页 → 直播 → 开播(轮播图片)/开播(主播)
3. 观众机：同一信令地址 → 观看；或浏览器打开 `http://<服务器>:8080/` 点观看
