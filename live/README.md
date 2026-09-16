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
