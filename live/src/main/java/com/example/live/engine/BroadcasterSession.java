package com.example.live.engine;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpParameters;
import org.webrtc.RtpSender;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.StatsReport;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import androidx.annotation.Nullable;
import com.example.live.core.LiveStats;
import com.example.live.signal.SignalClient;

/**
 * 主播会话：采集摄像头/麦克风，并对每个观众建立一条 PeerConnection（mesh 拓扑）。
 *
 * <p>核心逻辑：
 * <ul>
 *   <li>信令 onViewerJoined → 为该观众新建 PC，把本地 A/V track 加入，createOffer 发出</li>
 *   <li>信令 onAnswer/onCandidate → 完成 ICE/DTLS 握手，媒体开始 P2P 直连</li>
 *   <li>{@link LiveStats}（C++）：本地帧 sink 统计帧率；每 2s getStats 把丢包/RTT
 *       喂给 AIMD 控制器，并把建议码率应用到视频编码器（RtpParameters.maxBitrateBps）</li>
 * </ul></p>
 */
public class BroadcasterSession extends SessionBase {

    private static final String TAG = "BroadcasterSession";

    private VideoSource videoSource;
    private VideoTrack videoTrack;
    private AudioSource audioSource;
    private AudioTrack audioTrack;
    private CameraVideoCapturer capturer;
    private SurfaceTextureHelper textureHelper;
    private LiveStats stats;

    private final android.os.Handler handler = new android.os.Handler(
            android.os.Looper.getMainLooper());
    private Runnable statsTask;

    public BroadcasterSession(Context context, SignalClient signal, Listener listener) {
        super(context, signal, listener);
    }

    /** 开始采集并连上信令（无本地预览）。 */
    @Override
    public void start(String wsUrl, String room) {
        start(wsUrl, room, null);
    }

    /** 带本地预览的开播。 */
    public void start(String wsUrl, String room,
                      org.webrtc.SurfaceViewRenderer localRenderer) {
        PeerConnectionFactory factory = PeerFactoryProvider.factory(context);

        // ---- 采集 ----
        capturer = createCameraCapturer();
        EglBase.Context egl = PeerFactoryProvider.eglContext(context);
        videoSource = factory.createVideoSource(capturer != null && capturer.isScreencast());
        textureHelper = SurfaceTextureHelper.create("capture", egl);
        if (capturer != null) {
            capturer.initialize(textureHelper, context, videoSource.getCapturerObserver());
            capturer.startCapture(1280, 720, 30);
        } else {
            Log.w(TAG, "no camera available");
        }
        videoTrack = factory.createVideoTrack("video0", videoSource);
        if (localRenderer != null) {
            videoTrack.addSink(localRenderer);
        }

        audioSource = factory.createAudioSource(new MediaConstraints());
        audioTrack = factory.createAudioTrack("audio0", audioSource);

        // ---- C++ 统计：本地帧率/分辨率（每帧回调进 C++ 滑动窗口）----
        stats = new LiveStats(200, 2500, 1200);
        videoTrack.addSink(new org.webrtc.VideoSink() {
            @Override
            public void onFrame(org.webrtc.VideoFrame frame) {
                // 注意：sink 拿到的 frame 由框架管理引用计数，这里不能 release()
                stats.onFrame(System.currentTimeMillis(),
                        frame.getRotatedWidth(), frame.getRotatedHeight());
            }
        });

        // ---- 信令 ----
        signal.connect(wsUrl, room, "broadcaster", this);
        scheduleStatsLoop();
    }

    @Override
    protected PeerConnection createPeerConnection(final String viewerId) {
        PeerConnectionFactory factory = PeerFactoryProvider.factory(context);
        List<PeerConnection.IceServer> servers = new ArrayList<>();
        servers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        servers.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());

        PeerConnection.RTCConfiguration config =
                new PeerConnection.RTCConfiguration(servers);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        PeerConnection pc = factory.createPeerConnection(config, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(IceCandidate candidate) {
                signal.sendCandidate(candidate, viewerId);
            }

            @Override
            public void onConnectionChange(PeerConnection.PeerConnectionState state) {
                Log.d(TAG, "viewer " + viewerId + " state=" + state);
            }

            // ---- 以下为不关心的回调，空实现 ----
            @Override public void onSignalingChange(PeerConnection.SignalingState s) { }
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) { }
            @Override public void onIceConnectionReceivingChange(boolean b) { }
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) { }
            @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) { }
            @Override public void onAddStream(org.webrtc.MediaStream stream) { }
            @Override public void onRemoveStream(org.webrtc.MediaStream stream) { }
            @Override public void onDataChannel(org.webrtc.DataChannel dc) { }
            @Override public void onRenegotiationNeeded() { }
            @Override public void onTrack(org.webrtc.RtpTransceiver t) { }
        });

        if (pc != null) {
            if (videoTrack != null) pc.addTrack(videoTrack, java.util.Collections.singletonList("stream0"));
            if (audioTrack != null) pc.addTrack(audioTrack, java.util.Collections.singletonList("stream0"));
        }
        return pc;
    }

    @Override
    public void onViewerJoined(final String viewerId) {
        Log.d(TAG, "onViewerJoined " + viewerId);
        PeerConnection pc = createPeerConnection(viewerId);
        Log.d(TAG, "pc=" + pc);
        if (pc == null) return;
        peers.put(viewerId, pc);
        // 主播是 offerer：拿到观众列表事件后主动推 offer
        pc.createOffer(new SdpObserver() {
            @Override public void onCreateSuccess(final SessionDescription sdp) {
                Log.d(TAG, "offer created, sending to " + viewerId);
                pc.setLocalDescription(noop(), sdp);
                signal.sendOffer(viewerId, sdp);
            }
            @Override public void onSetSuccess() { }
            @Override public void onCreateFailure(String error) {
                Log.e(TAG, "createOffer failed: " + error);
            }
            @Override public void onSetFailure(String error) {
                Log.e(TAG, "setLocal failed: " + error);
            }
        }, new MediaConstraints());
    }

    @Override
    public void onAnswer(final String viewerId, SessionDescription sdp) {
        PeerConnection pc = peers.get(viewerId);
        if (pc == null) return;
        pc.setRemoteDescription(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription sdp2) { }
            @Override public void onSetSuccess() { }
            @Override public void onCreateFailure(String error) { }
            @Override public void onSetFailure(String error) {
                Log.e(TAG, "setRemote failed: " + error);
            }
        }, sdp);
    }

    @Override
    public void onViewerLeft(String viewerId) {
        PeerConnection pc = peers.remove(viewerId);
        if (pc != null) pc.close();
        listener.onStatus("观众离开: " + viewerId);
    }

    /** 每 2s 一次 getStats：丢包/RTT 喂 C++ AIMD，建议码率应用到编码器。 */
    private void scheduleStatsLoop() {
        statsTask = new Runnable() {
            @Override
            public void run() {
                for (PeerConnection pc : peers.values()) {
                    pc.getStats(new org.webrtc.StatsObserver() {
                        @Override
                        public void onComplete(StatsReport[] reports) {
                            applyStats(reports);
                        }
                    }, null);
                }
                handler.postDelayed(this, 2000);
            }
        };
        handler.postDelayed(statsTask, 2000);
    }

    private void applyStats(StatsReport[] reports) {
        double loss = -1;
        double rtt = -1;
        long bytes = -1;
        // 注意：libwebrtc 的 StatsReport 用 public final 字段（非 getter）
        for (StatsReport r : reports) {
            if ("outbound-rtp".equals(r.type)) {
                JSONObject v = values(r);
                bytes = v.optLong("bytesSent", -1);
            } else if ("remote-inbound-rtp".equals(r.type) || "inbound-rtp".equals(r.type)) {
                JSONObject v = values(r);
                if (v.has("fractionLost")) loss = v.optDouble("fractionLost", -1);
                if (v.has("roundTripTime")) rtt = v.optDouble("roundTripTime", -1);
            } else if ("candidate-pair".equals(r.type)) {
                JSONObject v = values(r);
                if (v.has("currentRoundTripTime")) rtt = v.optDouble("currentRoundTripTime", rtt);
            }
        }
        if (loss >= 0) {
            double suggest = stats.onLossSample(loss);
            if (rtt >= 0) stats.onRttSample(rtt);
            applyBitrate(suggest);
            listener.onStats(stats.snapshotJson());
        } else if (bytes >= 0) {
            listener.onStats(stats.snapshotJson());
        }
    }

    private void applyBitrate(double kbps) {
        for (PeerConnection pc : peers.values()) {
            for (RtpSender sender : pc.getSenders()) {
                if (sender.track() == null
                        || !MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO.equals(sender.track().kind())) {
                    continue;
                }
                RtpParameters rp = sender.getParameters();
                if (rp.encodings.size() > 0) {
                    rp.encodings.get(0).maxBitrateBps = (int) (kbps * 1000);
                    rp.encodings.get(0).minBitrateBps = 150_000;
                    try {
                        sender.setParameters(rp);
                    } catch (Exception e) {
                        Log.w(TAG, "setParameters failed", e);
                    }
                }
            }
        }
    }

    private static JSONObject values(StatsReport r) {
        JSONObject o = new JSONObject();
        if (r.values == null) return o;
        for (StatsReport.Value v : r.values) {
            try {
                o.put(v.name, v.value);
            } catch (Exception ignored) {
            }
        }
        return o;
    }

    private CameraVideoCapturer createCameraCapturer() {
        Camera2Enumerator enumerator = new Camera2Enumerator(context);
        String front = null;
        for (String name : enumerator.getDeviceNames()) {
            if (enumerator.isFrontFacing(name)) {
                front = name;
                break;
            }
        }
        if (front == null && enumerator.getDeviceNames().length > 0) {
            front = enumerator.getDeviceNames()[0];
        }
        if (front == null) return null;
        try {
            return enumerator.createCapturer(front, null);
        } catch (Exception e) {
            return null;
        }
    }

    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

    private static SdpObserver noop() {
        return new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription sdp) { }
            @Override public void onSetSuccess() { }
            @Override public void onCreateFailure(String error) { }
            @Override public void onSetFailure(String error) { }
        };
    }

    @Override
    public void stop() {
        handler.removeCallbacksAndMessages(null);
        if (stats != null) {
            stats.release();
            stats = null;
        }
        if (capturer != null) {
            try {
                capturer.stopCapture();
            } catch (InterruptedException ignored) {
            }
            capturer.dispose();
            capturer = null;
        }
        if (textureHelper != null) {
            textureHelper.dispose();
            textureHelper = null;
        }
        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
        }
        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
        }
        super.stop();
    }
}
