package com.example.live.engine;

import android.content.Context;

import org.webrtc.IceCandidate;
import org.webrtc.PeerConnection;
import org.webrtc.VideoTrack;

import java.util.HashMap;
import java.util.Map;

import com.example.live.signal.SignalClient;

/**
 * 会话公共基类：持有 context / 信令 / 回调 / 全部 PeerConnection，
 * 实现 SignalClient.Listener 的公共部分（状态转发、ICE 分发、生命周期）。
 * 子类只关注角色差异：主播处理观众进出与 offer，观众处理 offer/answer。
 */
public abstract class SessionBase implements SignalClient.Listener {

    /** 引擎对 UI 的回调（均在主线程）。 */
    public interface Listener {
        void onStatus(String message);        // 状态行（连接中/观众进出/错误提示）
        void onStats(String json);            // C++ 统计快照（仅主播会话）
        void onChat(String from, String text);
        void onViewerCount(int count);
        void onRemoteVideo(VideoTrack track); // 观众：收到主播视频轨
        void onEnded(String reason);          // 会话结束（主播离开/断线）
    }

    protected final Context context;
    protected final SignalClient signal;
    protected final Listener listener;
    /** viewerId → PeerConnection（观众会话中 viewerId 固定为 "broadcaster"）。 */
    protected final Map<String, PeerConnection> peers = new HashMap<>();

    protected SessionBase(Context context, SignalClient signal, Listener listener) {
        this.context = context.getApplicationContext();
        this.signal = signal;
        this.listener = listener;
    }

    /** 工厂方法：为对端创建一条 PeerConnection（含 STUN 配置）。 */
    protected abstract PeerConnection createPeerConnection(String peerId);

    /** 连接信令房间（子类实现各自角色参数）。 */
    public abstract void start(String wsUrl, String room);

    // ---- SignalClient.Listener 公共部分 ----

    @Override
    public void onJoined() {
        listener.onStatus("已连接房间");
    }

    /** 默认空实现：观众会话不会收到 offer。 */
    @Override
    public void onOffer(String viewerId, org.webrtc.SessionDescription sdp) {
    }

    /** 默认空实现：主播会话不会收到 answer。 */
    @Override
    public void onAnswer(String viewerId, org.webrtc.SessionDescription sdp) {
    }

    /** 默认空实现：观众会话不会收到 viewer-joined。 */
    @Override
    public void onViewerJoined(String viewerId) {
    }

    /** 默认空实现：观众会话不会收到 viewer-left。 */
    @Override
    public void onViewerLeft(String viewerId) {
    }

    @Override
    public void onCandidate(String viewerId, IceCandidate candidate) {
        PeerConnection pc = viewerId == null
                ? (peers.isEmpty() ? null : peers.values().iterator().next())
                : peers.get(viewerId);
        if (pc != null) {
            pc.addIceCandidate(candidate);
        }
    }

    @Override
    public void onViewerCount(int count) {
        listener.onViewerCount(count);
    }

    @Override
    public void onChat(String from, String text) {
        listener.onChat(from, text);
    }

    @Override
    public void onError(String message) {
        listener.onStatus("错误: " + message);
        listener.onEnded(message);
    }

    @Override
    public void onBroadcasterLeft() {
        listener.onStatus("主播已下播");
        listener.onEnded("broadcaster left");
    }

    /** 关闭全部 PeerConnection 与信令。 */
    public void stop() {
        for (PeerConnection pc : peers.values()) {
            try {
                pc.close();
            } catch (Exception ignored) {
            }
        }
        peers.clear();
        signal.close();
    }
}
