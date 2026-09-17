package com.example.live.engine;

import android.content.Context;
import android.util.Log;

import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.Nullable;
import com.example.live.signal.SignalClient;

/**
 * 观众会话：连上信令后等主播的 offer，回答 answer，收到视频轨后回调 UI 渲染。
 * 观众不上传摄像头/麦克风（纯拉流）。ICE 候选统一由 SessionBase 分发。
 */
public class ViewerSession extends SessionBase {

    private static final String TAG = "ViewerSession";
    private static final String BROADCASTER = "broadcaster";

    public ViewerSession(Context context, SignalClient signal, Listener listener) {
        super(context, signal, listener);
    }

    /** 连接房间（role=viewer）。 */
    @Override
    public void start(String wsUrl, String room) {
        signal.connect(wsUrl, room, "viewer", this);
    }

    @Override
    protected PeerConnection createPeerConnection(String peerId) {
        PeerConnectionFactory factory = PeerFactoryProvider.factory(context);
        List<PeerConnection.IceServer> servers = new ArrayList<>();
        servers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        servers.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());

        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(servers);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        return factory.createPeerConnection(config, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(IceCandidate candidate) {
                signal.sendCandidate(candidate, null); // 观众侧无需 viewerId
            }

            @Override
            public void onTrack(org.webrtc.RtpTransceiver transceiver) {
                // UNIFIED_PLAN：主播的音视频轨从这里到达
                if (transceiver.getReceiver() != null
                        && transceiver.getReceiver().track() instanceof VideoTrack) {
                    VideoTrack track = (VideoTrack) transceiver.getReceiver().track();
                    listener.onRemoteVideo(track);
                }
            }

            @Override public void onSignalingChange(PeerConnection.SignalingState s) { }
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                Log.d(TAG, "ice=" + s);
            }
            @Override public void onIceConnectionReceivingChange(boolean b) { }
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) { }
            @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) { }
            @Override public void onAddStream(org.webrtc.MediaStream stream) { }
            @Override public void onRemoveStream(org.webrtc.MediaStream stream) { }
            @Override public void onDataChannel(org.webrtc.DataChannel dc) { }
            @Override public void onRenegotiationNeeded() { }
        });
    }

    @Override
    protected void onOfferInternal(String viewerId, SessionDescription sdp) {
        PeerConnection pc = createPeerConnection(BROADCASTER);
        if (pc == null) return;
        peers.put(BROADCASTER, pc);
        pc.setRemoteDescription(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription sdp2) { }
            @Override public void onSetSuccess() {
                // 远端设置成功后再应答
                pc.createAnswer(new SdpObserver() {
                    @Override public void onCreateSuccess(final SessionDescription answer) {
                        pc.setLocalDescription(noop(), answer);
                        signal.sendAnswer(answer);
                        listener.onStatus("已应答，建立连接中…");
                    }
                    @Override public void onSetSuccess() { }
                    @Override public void onCreateFailure(String error) {
                        listener.onStatus("createAnswer 失败: " + error);
                    }
                    @Override public void onSetFailure(String error) { }
                }, new MediaConstraints());
            }
            @Override public void onCreateFailure(String error) { }
            @Override public void onSetFailure(String error) {
                listener.onStatus("setRemote 失败: " + error);
            }
        }, sdp);
    }

    private static SdpObserver noop() {
        return new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription sdp) { }
            @Override public void onSetSuccess() { }
            @Override public void onCreateFailure(String error) { }
            @Override public void onSetFailure(String error) { }
        };
    }
}
