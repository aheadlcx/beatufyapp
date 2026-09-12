package com.example.beautycamera;

/** User-adjustable beauty parameters. All in 0..1 unless noted. */
public class BeautyParams {
    public volatile float smooth = 0.75f;      // 磨皮
    public volatile float whiten = 0.35f;      // 美白
    public volatile float ruddy = 0.25f;       // 红润
    public volatile float sharpen = 0.30f;     // 锐化
    public volatile float saturate = 0.15f;    // 饱和度增强
    public volatile float eyeEnlarge = 0.35f;  // 大眼
    public volatile float faceSlim = 0.40f;    // 瘦脸
    public volatile float chinSlim = 0.30f;    // 小下巴
    public volatile int filterIndex = 0;       // 滤镜
    public volatile float filterStrength = 1f;

    public static final String[] FILTER_NAMES = {"原图", "白皙", "暖阳", "冷调", "胶片", "初恋"};
}
