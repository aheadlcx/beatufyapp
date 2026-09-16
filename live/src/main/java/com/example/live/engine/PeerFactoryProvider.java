package com.example.live.engine;

import android.content.Context;

import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.audio.JavaAudioDeviceModule;

/**
 * PeerConnectionFactory 的进程级单例。libwebrtc 的工厂创建开销大（加载 so、
 * 初始化编解码器），整个进程共享一个实例；{@link #eglContext} 同时供本地预览
 * 与远端渲染的 SurfaceViewRenderer 使用，避免纹理跨上下文拷贝。
 */
public final class PeerFactoryProvider {

    private static PeerConnectionFactory factory;
    private static EglBase eglBase;

    private PeerFactoryProvider() {
    }

    public static synchronized PeerConnectionFactory factory(Context context) {
        if (factory == null) {
            if (context == null) {
                throw new IllegalStateException("factory must be initialized with a context first");
            }
            eglBase = EglBase.create();
            PeerConnectionFactory.InitializationOptions init =
                    PeerConnectionFactory.InitializationOptions.builder(context.getApplicationContext())
                            .setEnableInternalTracer(false)
                            .createInitializationOptions();
            PeerConnectionFactory.initialize(init);

            factory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(new DefaultVideoEncoderFactory(
                            eglBase.getEglBaseContext(), /* enableIntelVp8Encoder */ true,
                            /* enableH264HighProfile */ true))
                    .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                    .setAudioDeviceModule(JavaAudioDeviceModule.builder(context.getApplicationContext())
                            .createAudioDeviceModule())
                    .createPeerConnectionFactory();
        }
        return factory;
    }

    /** 首次调用必须带 context；之后可无参获取（工厂已初始化）。 */
    public static synchronized EglBase.Context eglContext(Context context) {
        factory(context);
        return eglBase.getEglBaseContext();
    }
}
