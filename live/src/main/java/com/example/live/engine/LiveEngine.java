package com.example.live.engine;

import android.content.Context;

import androidx.annotation.Nullable;
import com.example.live.signal.SignalClient;
import com.example.live.signal.WsSignalClient;

/**
 * 直播引擎门面：UI 只和它打交道。
 *
 * <p>角色二选一：
 * <ul>
 *   <li>{@link #startAsBroadcaster}：开摄像头/麦克风推流，支持多观众观看（mesh）</li>
 *   <li>{@link #startAsViewer}：拉取房间内主播的音视频</li>
 * </ul>
 * 传输/编解码细节都被封装：SignalClient 可替换（接口注入），
 * 编解码由 libwebrtc(C++) 完成，推流统计与码率控制在 livecore(C++) 中。</p>
 */
public class LiveEngine {

    private final Context context;
    private final Listener listener;
    private final SignalClient signal;
    private SessionBase session;

    public interface Listener extends SessionBase.Listener {
    }

    public LiveEngine(Context context, Listener listener, @Nullable SignalClient customSignal) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.signal = customSignal != null ? customSignal : new WsSignalClient();
    }

    /** 主播开播：localRenderer 为本地预览（可为 null，仅推流不预览）。 */
    public void startAsBroadcaster(String wsUrl, String room,
                                   @Nullable org.webrtc.SurfaceViewRenderer localRenderer) {
        ensureStopped();
        BroadcasterSession s = new BroadcasterSession(context, signal, wrap());
        s.start(wsUrl, room, localRenderer);
        session = s;
    }

    /** 主播开播（图片轮播模式，无摄像头也能跑，便于联调）。 */
    public void startSlideshowBroadcaster(String wsUrl, String room,
                                          @Nullable org.webrtc.SurfaceViewRenderer localRenderer) {
        ensureStopped();
        BroadcasterSession s = new BroadcasterSession(context, signal, wrap());
        s.start(wsUrl, room, localRenderer, true);
        session = s;
    }

    /** 观众观看：remoteRenderer 为远端画面。 */
    public void startAsViewer(String wsUrl, String room,
                              @Nullable org.webrtc.SurfaceViewRenderer remoteRenderer) {
        ensureStopped();
        session = new ViewerSession(context, signal, wrap());
        session.start(wsUrl, room);
        if (remoteRenderer != null) {
            remoteRendererCallback = remoteRenderer;
        }
    }

    /** 发弹幕（经信令通道广播给房间所有人）。 */
    public void sendChat(String text) {
        if (session != null) signal.sendChat(text);
    }

    public void stop() {
        ensureStopped();
    }

    private volatile org.webrtc.SurfaceViewRenderer remoteRendererCallback;

    private SessionBase.Listener wrap() {
        return new SessionBase.Listener() {
            @Override
            public void onStatus(String message) {
                listener.onStatus(message);
            }

            @Override
            public void onStats(String json) {
                listener.onStats(json);
            }

            @Override
            public void onChat(String from, String text) {
                listener.onChat(from, text);
            }

            @Override
            public void onViewerCount(int count) {
                listener.onViewerCount(count);
            }

            @Override
            public void onRemoteVideo(org.webrtc.VideoTrack track) {
                org.webrtc.SurfaceViewRenderer r = remoteRendererCallback;
                if (r != null) {
                    track.addSink(r);
                }
                listener.onRemoteVideo(track);
            }

            @Override
            public void onEnded(String reason) {
                listener.onEnded(reason);
            }
        };
    }

    private void ensureStopped() {
        if (session != null) {
            session.stop();
            session = null;
        }
    }
}
