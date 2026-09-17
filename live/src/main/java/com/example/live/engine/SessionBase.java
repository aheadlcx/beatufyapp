package com.example.live.engine;

import android.content.Context;

import org.webrtc.IceCandidate;
import org.webrtc.PeerConnection;
import org.webrtc.VideoTrack;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

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
    /** UI 回调（自动切回主线程）。 */
    protected final Listener listener;
    /** viewerId → PeerConnection（观众会话中 viewerId 固定为 "broadcaster"）。 */
    protected final Map<String, PeerConnection> peers = new HashMap<>();

    /**
     * libwebrtc 要求 PC 的创建/操作/销毁尽量在同一线程完成，否则可能触发
     * 信令线程的 CHECK 崩溃。所有信令回调与 PC 操作都 post 到这个专用线程。
     */
    protected final HandlerThread webrtcThread;
    protected final Handler webrtcHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    protected SessionBase(Context context, SignalClient signal, Listener listener) {
        this.context = context.getApplicationContext();
        this.signal = signal;
        this.listener = new Listener() {
            private final Listener l = listener;

            @Override public void onStatus(final String message) {
                mainHandler.post(() -> l.onStatus(message));
            }
            @Override public void onStats(final String json) {
                mainHandler.post(() -> l.onStats(json));
            }
            @Override public void onChat(final String from, final String text) {
                mainHandler.post(() -> l.onChat(from, text));
            }
            @Override public void onViewerCount(final int count) {
                mainHandler.post(() -> l.onViewerCount(count));
            }
            @Override public void onRemoteVideo(final VideoTrack track) {
                mainHandler.post(() -> l.onRemoteVideo(track));
            }
            @Override public void onEnded(final String reason) {
                mainHandler.post(() -> l.onEnded(reason));
            }
        };
        webrtcThread = new HandlerThread("webrtc-session");
        webrtcThread.start();
        webrtcHandler = new Handler(webrtcThread.getLooper());
    }

    /** 在 webrtc 专用线程上执行一段 PC 操作。 */
    protected void onWebrtc(Runnable r) {
        webrtcHandler.post(r);
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

    @Override
    public void onOffer(final String viewerId, final org.webrtc.SessionDescription sdp) {
        onWebrtc(() -> onOfferInternal(viewerId, sdp));
    }

    protected void onOfferInternal(String viewerId, org.webrtc.SessionDescription sdp) {
    }

    @Override
    public void onAnswer(final String viewerId, final org.webrtc.SessionDescription sdp) {
        onWebrtc(() -> onAnswerInternal(viewerId, sdp));
    }

    protected void onAnswerInternal(String viewerId, org.webrtc.SessionDescription sdp) {
    }

    @Override
    public void onViewerJoined(final String viewerId) {
        onWebrtc(() -> onViewerJoinedInternal(viewerId));
    }

    protected void onViewerJoinedInternal(String viewerId) {
    }

    @Override
    public void onViewerLeft(final String viewerId) {
        onWebrtc(() -> onViewerLeftInternal(viewerId));
    }

    protected void onViewerLeftInternal(String viewerId) {
    }

    @Override
    public void onCandidate(final String viewerId, final IceCandidate candidate) {
        onWebrtc(() -> {
            PeerConnection pc = viewerId == null
                    ? (peers.isEmpty() ? null : peers.values().iterator().next())
                    : peers.get(viewerId);
            if (pc != null) {
                pc.addIceCandidate(candidate);
            }
        });
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

    /** 关闭全部 PeerConnection 与信令（在 webrtc 线程上执行）。 */
    public void stop() {
        webrtcHandler.post(new Runnable() {
            @Override
            public void run() {
                for (PeerConnection pc : peers.values()) {
                    try {
                        pc.close();
                    } catch (Exception ignored) {
                    }
                }
                peers.clear();
                signal.close();
            }
        });
        webrtcThread.quitSafely();
    }
}
