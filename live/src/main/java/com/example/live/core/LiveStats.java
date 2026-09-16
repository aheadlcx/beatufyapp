package com.example.live.core;

/**
 * 直播推流核心统计与自适应码率控制（C++ 实现，见 cpp/livecore.cpp）。
 *
 * <p>用法（仅主播会话使用）：
 * <pre>
 *   LiveStats stats = new LiveStats(200, 2500, 1200);
 *   videoTrack.addSink(frame -> stats.onFrame(...));  // 每帧
 *   stats.onLossSample(loss);                          // 每次 getStats（约 2s 一次）
 *   int kbps = stats.suggestedBitrateKbps();           // 应用到编码器 maxBitrate
 *   String json = stats.snapshotJson();                // UI 展示
 *   stats.release();                                   // 会话结束
 * </pre></p>
 */
public class LiveStats {

    static {
        System.loadLibrary("livecore");
    }

    private volatile long handle;

    public LiveStats(int minKbps, int maxKbps, int startKbps) {
        handle = nativeCreate(minKbps, maxKbps, startKbps);
    }

    /** 每一帧推流回调（高频，C++ 滑动窗口统计）。 */
    public void onFrame(long nowMs, int width, int height) {
        long h = handle;
        if (h != 0) nativeOnFrame(h, nowMs, width, height);
    }

    /** RTT 采样（ms）。返回当前建议码率 kbps。 */
    public double onRttSample(double rttMs) {
        long h = handle;
        return h != 0 ? nativeOnRttSample(h, rttMs) : 0;
    }

    /** 丢包率采样（0..1）。AIMD 调整码率，返回建议码率 kbps。 */
    public double onLossSample(double lossFraction) {
        long h = handle;
        return h != 0 ? nativeOnLossSample(h, lossFraction) : 0;
    }

    public int suggestedBitrateKbps() {
        long h = handle;
        return h != 0 ? (int) nativeSuggested(h) : 0;
    }

    /** 统计快照 JSON：{fps,w,h,bitrateKbps,loss,rttMs,jitterMs}。 */
    public String snapshotJson() {
        long h = handle;
        return h != 0 ? nativeSnapshot(h) : "{}";
    }

    public void release() {
        long h = handle;
        if (h != 0) {
            handle = 0;
            nativeRelease(h);
        }
    }

    private native long nativeCreate(int minKbps, int maxKbps, int startKbps);
    private native void nativeRelease(long handle);
    private native void nativeOnFrame(long handle, long nowMs, int width, int height);
    private native double nativeOnRttSample(long handle, double rttMs);
    private native double nativeOnLossSample(long handle, double lossFraction);
    private native long nativeSuggested(long handle);
    private native String nativeSnapshot(long handle);
}
