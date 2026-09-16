package com.example.live.signal;

import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

/**
 * 信令通道的抽象接口：直播引擎只依赖此接口，不关心底层是
 * WebSocket（自建服务器）还是别的公网通道，方便替换与测试。
 *
 * <p>协议语义（与 server/server.js 对应）：
 * <ul>
 *   <li>主播：connect 后收 onViewerJoined → 对该观众 sendOffer；
 *       收 onAnswer/onCandidate(viewerId) 完成连接；观众断开收 onViewerLeft</li>
 *   <li>观众：connect 后收 onOffer → sendAnswer；收 onCandidate(null)</li>
 * </ul></p>
 */
public interface SignalClient {

    void connect(String wsUrl, String room, String role, Listener listener);

    /** 主播调用：向指定观众发送 offer。 */
    void sendOffer(String viewerId, SessionDescription sdp);

    /** 观众调用：向主播发送 answer。 */
    void sendAnswer(SessionDescription sdp);

    /**
     * 双向：转发 ICE 候选。主播侧必须带 viewerId；
     * 观众侧传 null（只有一个主播）。
     */
    void sendCandidate(IceCandidate candidate, String viewerId);

    void sendChat(String text);

    void close();

    /** 回调均在主线程（由实现方保证）。 */
    interface Listener {
        void onJoined();                       // 入房确认（主播/观众都会收到）
        void onViewerJoined(String viewerId);  // 仅主播
        void onViewerLeft(String viewerId);    // 仅主播
        void onBroadcasterLeft();              // 仅观众：主播下播
        void onOffer(String viewerId, SessionDescription sdp);  // 仅观众
        void onAnswer(String viewerId, SessionDescription sdp); // 仅主播
        void onCandidate(String viewerId, IceCandidate candidate); // viewerId 可为 null
        void onViewerCount(int count);
        void onChat(String from, String text);
        void onError(String message);
    }
}
