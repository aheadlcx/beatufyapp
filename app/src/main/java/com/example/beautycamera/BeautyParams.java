package com.example.beautycamera;

/** User-adjustable beauty parameters. All in 0..1 unless noted. */
public class BeautyParams {
    public volatile float smooth = 0.75f;      // 磨皮（引导滤波强度）
    public volatile float whiten = 0.35f;      // 美白
    public volatile float ruddy = 0.25f;       // 红润
    public volatile float sharpen = 0.30f;     // 锐化
    public volatile float saturate = 0.15f;    // 饱和度增强
    public volatile float skinTone = 0.20f;    // 肤色（偏冷白）
    public volatile float eyeCircle = 0.35f;   // 祛黑眼圈
    public volatile float contouring = 0.30f;  // 立体修容（鼻影/颧骨高光）
    public volatile float eyeEnlarge = 0.35f;  // 大眼
    public volatile float faceSlim = 0.40f;    // 瘦脸
    public volatile float chinSlim = 0.30f;    // 小下巴
    public volatile float noseSlim = 0.30f;    // 瘦鼻
    public volatile float smileLift = 0.30f;   // 微笑嘴角
    public volatile float forehead = 0.20f;    // 额头调整
    public volatile float bgBlur = 0.0f;       // 背景虚化强度
    public volatile int filterIndex = 0;       // 滤镜
    public volatile float filterStrength = 1f;

    public static final String[] FILTER_NAMES = {
            "原图", "白皙", "暖阳", "冷调", "胶片", "初恋",
            "黑白", "复古", "粉黛", "蜜桃", "冷蓝", "森系"};

    /** 一键预设：{smooth,whiten,ruddy,sharpen,saturate,skinTone,eyeCircle,contouring,
     *            eyeEnlarge,faceSlim,chinSlim,noseSlim,smileLift,forehead} */
    public static final String[] PRESET_NAMES = {"自然", "精修", "冷白", "素颜"};
    public static final float[][] PRESETS = {
            {0.55f, 0.25f, 0.20f, 0.30f, 0.15f, 0.15f, 0.30f, 0.25f, 0.30f, 0.35f, 0.25f, 0.25f, 0.25f, 0.15f},
            {0.80f, 0.40f, 0.25f, 0.25f, 0.20f, 0.25f, 0.45f, 0.40f, 0.40f, 0.45f, 0.30f, 0.35f, 0.30f, 0.25f},
            {0.65f, 0.50f, 0.10f, 0.30f, 0.10f, 0.55f, 0.40f, 0.30f, 0.35f, 0.40f, 0.25f, 0.30f, 0.25f, 0.20f},
            {0.30f, 0.12f, 0.10f, 0.35f, 0.08f, 0.00f, 0.15f, 0.10f, 0.15f, 0.20f, 0.10f, 0.10f, 0.10f, 0.05f},
    };

    /** 将预设第 idx 套应用到当前参数。 */
    public void applyPreset(int idx) {
        float[] p = PRESETS[idx];
        smooth = p[0]; whiten = p[1]; ruddy = p[2]; sharpen = p[3]; saturate = p[4];
        skinTone = p[5]; eyeCircle = p[6]; contouring = p[7];
        eyeEnlarge = p[8]; faceSlim = p[9]; chinSlim = p[10]; noseSlim = p[11];
        smileLift = p[12]; forehead = p[13];
    }
}
