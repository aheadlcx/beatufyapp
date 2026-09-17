package com.example.live.signal;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * 基于 WebSocket 的信令客户端实现（协议见 server/server.js 顶部注释）。
 * OkHttp 的 WebSocket 回调在后台线程，这里统一切到主线程再回调引擎。
 */
public class WsSignalClient implements SignalClient {

    private static final String TAG = "WsSignalClient";
    private static final long CLOSE_TIMEOUT_MS = 3000;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .build();
    private final Handler main = new Handler(Looper.getMainLooper());

    private WebSocket ws;
    private Listener listener;
    private String url;
    private String room;
    private String role;
    private volatile boolean closedByUs = false;
    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());
    private static final long RECONNECT_DELAY_MS = 3000;

    @Override
    public void connect(String wsUrl, final String room, final String role, final Listener l) {
        this.url = wsUrl;
        this.room = room;
        this.role = role;
        this.listener = l;
        this.closedByUs = false;
        openSocket(l);
    }

    private void openSocket(final Listener l) {
        Request request = new Request.Builder().url(url).build();
        ws = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                sendJson("join", room, role);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (listener != null) listener.onJoined();
                    }
                });
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                try {
                    handle(new JSONObject(text));
                } catch (JSONException e) {
                    Log.w(TAG, "bad json: " + text);
                }
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, Response response) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (listener != null) listener.onError("连接失败，重连中: " + t.getMessage());
                        scheduleReconnect(l);
                    }
                });
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (listener != null) listener.onError("连接已关闭(" + code + ")");
                    }
                });
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull ByteString bytes) {
                // 二进制帧不使用
            }
        });
    }

    // JSONObject 没有稳定的 String 构造器约定，统一 new JSONObject(text)

    private void handle(JSONObject msg) throws JSONException {
        String type = msg.optString("type");
        final Listener l = listener;
        if (l == null) return;
        if ("joined".equals(type)) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    l.onJoined();
                }
            });
        } else if ("viewer-joined".equals(type)) {
            final String id = msg.optString("viewerId");
            main.post(new Runnable() {
                @Override
                public void run() {
                    l.onViewerJoined(id);
                }
            });
        } else if ("viewer-left".equals(type)) {
            final String id = msg.optString("viewerId");
            main.post(new Runnable() {
                @Override
                public void run() {
                    l.onViewerLeft(id);
                }
            });
        } else if ("broadcaster-left".equals(type)) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    l.onBroadcasterLeft();
                }
            });
        } else if ("offer".equals(type)) {
            Log.d(TAG, "recv offer");
            final String viewerId = msg.has("viewerId") ? msg.optString("viewerId") : null;
            final SessionDescription sdp = toSdp(msg.getJSONObject("sdp"));
            main.post(new Runnable() {
                @Override
                public void run() {
                    l.onOffer(viewerId, sdp);
                }
            });
        } else if ("answer".equals(type)) {
            final String viewerId = msg.has("viewerId") ? msg.optString("viewerId") : null;
            final SessionDescription sdp = toSdp(msg.getJSONObject("sdp"));
            main.post(new Runnable() {
                @Override
                public void run() {
                    l.onAnswer(viewerId, sdp);
                }
            });
        } else if ("candidate".equals(type)) {
            JSONObject c = msg.getJSONObject("candidate");
            final IceCandidate candidate = new IceCandidate(
                    c.optString("sdpMid", "0"), c.optInt("sdpMLineIndex", 0),
                    c.optString("candidate"));
            final String viewerId = msg.has("viewerId") ? msg.optString("viewerId") : null;
            main.post(new Runnable() {
                @Override
                public void run() {
                    l.onCandidate(viewerId, candidate);
                }
            });
        } else if ("viewers".equals(type)) {
            final int count = msg.optInt("count", 0);
            main.post(new Runnable() {
                @Override
                public void run() {
                    l.onViewerCount(count);
                }
            });
        } else if ("chat".equals(type)) {
            final String from = msg.optString("from", "?");
            final String text = msg.optString("text");
            main.post(new Runnable() {
                @Override
                public void run() {
                    l.onChat(from, text);
                }
            });
        } else if ("error".equals(type)) {
            final String message = msg.optString("message", "server error");
            main.post(new Runnable() {
                @Override
                public void run() {
                    l.onError(message);
                }
            });
        }
    }

    private static SessionDescription toSdp(JSONObject sdp) throws JSONException {
        String typeStr = sdp.optString("type", "offer");
        SessionDescription.Type type = "answer".equals(typeStr)
                ? SessionDescription.Type.ANSWER : SessionDescription.Type.OFFER;
        return new SessionDescription(type, sdp.optString("sdp"));
    }

    private static JSONObject fromSdp(SessionDescription sdp) throws JSONException {
        JSONObject o = new JSONObject();
        // 注意：部分 libwebrtc 版本 canonicalForm() 返回 null，显式判断
        o.put("type", sdp.type == SessionDescription.Type.ANSWER ? "answer" : "offer");
        o.put("sdp", sdp.description);
        return o;
    }

    private void sendJson(String type, Object... kv) {
        WebSocket w = ws;
        if (w == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            if (room != null) o.put("room", room);
            if (role != null) o.put("role", role);
            for (int i = 0; i + 1 < kv.length; i += 2) {
                if (kv[i + 1] != null) o.put((String) kv[i], kv[i + 1]);
            }
            w.send(o.toString());
        } catch (JSONException e) {
            Log.w(TAG, "encode failed", e);
        }
    }

    @Override
    public void sendOffer(String viewerId, SessionDescription sdp) {
        Log.d(TAG, "sendOffer to " + viewerId);
        try {
            WebSocket w = ws;
            if (w == null) return;
            JSONObject o = new JSONObject();
            o.put("type", "offer");
            o.put("viewerId", viewerId);
            o.put("sdp", fromSdp(sdp));
            w.send(o.toString());
        } catch (JSONException e) {
            Log.w(TAG, "sendOffer failed", e);
        }
    }

    @Override
    public void sendAnswer(SessionDescription sdp) {
        try {
            WebSocket w = ws;
            if (w == null) return;
            JSONObject o = new JSONObject();
            o.put("type", "answer");
            o.put("sdp", fromSdp(sdp));
            w.send(o.toString());
        } catch (JSONException e) {
            Log.w(TAG, "sendAnswer failed", e);
        }
    }

    @Override
    public void sendCandidate(IceCandidate candidate, @Nullable String viewerId) {
        try {
            WebSocket w = ws;
            if (w == null) return;
            // IceCandidate.sdp 是完整的 "candidate:..." 行，服务器原样转发
            JSONObject c = new JSONObject();
            c.put("sdpMid", candidate.sdpMid);
            c.put("sdpMLineIndex", candidate.sdpMLineIndex);
            c.put("candidate", candidate.sdp);
            JSONObject o = new JSONObject();
            o.put("type", "candidate");
            if (viewerId != null) o.put("viewerId", viewerId);
            o.put("candidate", c);
            w.send(o.toString());
        } catch (JSONException e) {
            Log.w(TAG, "sendCandidate failed", e);
        }
    }

    @Override
    public void sendChat(String text) {
        sendJson("chat", "text", text);
    }

    private void scheduleReconnect(final Listener l) {
        if (closedByUs) return;
        reconnectHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!closedByUs) openSocket(l);
            }
        }, RECONNECT_DELAY_MS);
    }

    @Override
    public void close() {
        closedByUs = true;
        reconnectHandler.removeCallbacksAndMessages(null);
        listener = null;
        WebSocket w = ws;
        ws = null;
        if (w != null) w.close(1000, "bye");
        client.dispatcher().executorService().shutdown();
    }
}
