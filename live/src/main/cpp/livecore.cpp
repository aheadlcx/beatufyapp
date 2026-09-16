/**
 * livecore — 直播推流的核心实时逻辑（性能敏感，置于 C++）。
 *
 * 模块一：帧级滑动窗口统计
 *   推流的每一帧都会回调 onFrame()（30fps 下即每秒 30 次 JNI 调用），
 *   用 5 秒滑动窗口在 C++ 侧 O(1) 维护帧率/分辨率/帧间隔抖动，
 *   避免 Java 侧高频对象分配与 GC 压力。
 *
 * 模块二：AIMD 自适应码率控制器
 *   直播推流最核心的自适应逻辑。接收端丢包率/RTT 采样进来后：
 *     丢包率 EWMA > 10%  → 码率 ×0.85（乘性减，M“ultiplicative decrease）
 *     丢包率 EWMA < 2%   → 码率 +50kbps（加性增，A“dditive increase）
 *     之间               → 保持（探测带宽的安全区间）
 *   码率被夹在 [minKbps, maxKbps] 区间内，输出给编码器 setBitrate。
 *
 * 线程模型：所有方法可从不同线程调用，内部互斥锁保护。
 */
#include <jni.h>
#include <deque>
#include <string>
#include <mutex>
#include <cmath>
#include <cinttypes>

namespace {

struct FrameSample {
    int64_t tMs;
    int width;
    int height;
};

class LiveStatsCore {
public:
    LiveStatsCore(int minKbps, int maxKbps, int startKbps)
        : minKbps_(minKbps), maxKbps_(maxKbps), bitrateKbps_(startKbps) {}

    /** 每一帧推流都会调用（高频）。 */
    void onFrame(int64_t nowMs, int width, int height) {
        std::lock_guard<std::mutex> guard(mutex_);
        frames_.push_back(FrameSample{nowMs, width, height});
        while (!frames_.empty() && nowMs - frames_.front().tMs > kWindowMs) {
            frames_.pop_front();
        }
        if (frames_.size() >= 2) {
            int64_t delta = nowMs - prevFrameMs_;
            if (delta > 0 && delta < 1000) {
                // 帧间隔抖动：帧间隔与平均间隔偏差的 EWMA（类似 RFC3550 jitter 的简化）
                double mean = windowMs() / static_cast<double>(frames_.size() - 1);
                double dev = std::fabs(static_cast<double>(delta) - mean);
                jitterMs_ = jitterMs_ < 0 ? dev : (jitterMs_ * 0.9 + dev * 0.1);
            }
        }
        prevFrameMs_ = nowMs;
        lastWidth_ = width;
        lastHeight_ = height;
    }

    /** RTT 采样（来自 getStats，低频）。返回当前建议码率。 */
    double onRttSample(double rttMs) {
        std::lock_guard<std::mutex> guard(mutex_);
        rttMs_ = rttMs_ < 0 ? rttMs : (rttMs_ * 0.8 + rttMs * 0.2);
        return static_cast<double>(bitrateKbps_);
    }

    /** 丢包率采样（0..1，低频）。AIMD 核心在这里。返回建议码率 kbps。 */
    double onLossSample(double lossFraction) {
        std::lock_guard<std::mutex> guard(mutex_);
        if (lossFraction < 0) return static_cast<double>(bitrateKbps_);
        loss_ = loss_ < 0 ? lossFraction : (loss_ * 0.7 + lossFraction * 0.3);

        if (loss_ > 0.10) {
            // 拥塞：乘性减
            bitrateKbps_ = std::max<int64_t>(minKbps_,
                    static_cast<int64_t>(static_cast<double>(bitrateKbps_) * 0.85));
        } else if (loss_ < 0.02) {
            // 信道良好：加性增
            bitrateKbps_ = std::min<int64_t>(maxKbps_, bitrateKbps_ + 50);
        }
        return static_cast<double>(bitrateKbps_);
    }

    /** 当前建议码率 kbps。 */
    int64_t suggestedBitrateKbps() {
        std::lock_guard<std::mutex> guard(mutex_);
        return bitrateKbps_;
    }

    /** 人类可读的统计快照（JSON），UI 直接展示。 */
    std::string snapshotJson() {
        std::lock_guard<std::mutex> guard(mutex_);
        char buf[256];
        double fps = 0.0;
        if (!frames_.empty()) {
            int64_t span = frames_.back().tMs - frames_.front().tMs;
            if (span > 0) {
                fps = static_cast<double>(frames_.size() - 1) * 1000.0 / static_cast<double>(span);
            }
        }
        std::snprintf(buf, sizeof(buf),
                "{\"fps\":%.1f,\"w\":%d,\"h\":%d,"
                "\"bitrateKbps\":%lld,\"loss\":%.3f,\"rttMs\":%.1f,\"jitterMs\":%.1f}",
                fps, lastWidth_, lastHeight_,
                static_cast<long long>(bitrateKbps_),
                loss_ < 0 ? 0.0 : loss_,
                rttMs_ < 0 ? 0.0 : rttMs_,
                jitterMs_ < 0 ? 0.0 : jitterMs_);
        return std::string(buf);
    }

private:
    static const int64_t kWindowMs = 5000; // 统计窗口 5 秒

    double windowMs() const {
        if (frames_.size() < 2) return 0.0;
        return static_cast<double>(frames_.back().tMs - frames_.front().tMs);
    }

    std::mutex mutex_;
    std::deque<FrameSample> frames_;
    int64_t prevFrameMs_ = 0;
    int lastWidth_ = 0;
    int lastHeight_ = 0;

    int minKbps_;
    int maxKbps_;
    int64_t bitrateKbps_;

    double loss_ = -1.0;
    double rttMs_ = -1.0;
    double jitterMs_ = -1.0;
};

inline LiveStatsCore* asCore(jlong handle) {
    return reinterpret_cast<LiveStatsCore*>(static_cast<uintptr_t>(handle));
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_live_core_LiveStats_nativeCreate(JNIEnv* env, jclass,
        jint minKbps, jint maxKbps, jint startKbps) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(
            new LiveStatsCore(minKbps, maxKbps, startKbps)));
}

JNIEXPORT void JNICALL
Java_com_example_live_core_LiveStats_nativeRelease(JNIEnv*, jclass, jlong handle) {
    delete asCore(handle);
}

JNIEXPORT void JNICALL
Java_com_example_live_core_LiveStats_nativeOnFrame(JNIEnv*, jclass, jlong handle,
        jlong nowMs, jint width, jint height) {
    if (LiveStatsCore* core = asCore(handle)) core->onFrame(nowMs, width, height);
}

JNIEXPORT jdouble JNICALL
Java_com_example_live_core_LiveStats_nativeOnRttSample(JNIEnv*, jclass, jlong handle,
        jdouble rttMs) {
    if (LiveStatsCore* core = asCore(handle)) return core->onRttSample(rttMs);
    return 0;
}

JNIEXPORT jdouble JNICALL
Java_com_example_live_core_LiveStats_nativeOnLossSample(JNIEnv*, jclass, jlong handle,
        jdouble lossFraction) {
    if (LiveStatsCore* core = asCore(handle)) return core->onLossSample(lossFraction);
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_example_live_core_LiveStats_nativeSnapshot(JNIEnv* env, jclass, jlong handle) {
    if (LiveStatsCore* core = asCore(handle)) {
        return env->NewStringUTF(core->snapshotJson().c_str());
    }
    return env->NewStringUTF("{}");
}

} // extern "C"
