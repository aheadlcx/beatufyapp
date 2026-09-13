package com.example.beautycamera;

/**
 * 预览方向补偿的决策中心，把三路信号合成一个旋转角：
 * <ol>
 *   <li><b>人脸 roll 角</b>（首选）：有人脸时按人脸倾斜方向补偿——即使手机平放
 *       在桌面俯视，也能让人脸朝上；带 60° 滞回防止在档位边界来回跳动</li>
 *   <li><b>重力传感器</b>（无人脸时的回退）：竖持/倒持/横持四档</li>
 *   <li><b>手动翻转</b>：以上都失效时（如平放且检测不到脸），用户点"翻转"转 180°</li>
 * </ol>
 */
public class OrientationBlender {

    private static final int SWITCH_THRESHOLD = 60; // deg away from current quadrant

    private volatile int gravityDeg = 0;
    private volatile int faceDeg = 0;
    private volatile boolean flip = false;
    private volatile boolean lastFaceSeen = false;

    /** From the gravity sensor: portrait / upside-down / landscape. */
    public void onGravity(int deg) {
        gravityDeg = deg;
    }

    /** Whether a face is currently tracked (switches the primary signal). */
    public void onFacePresence(boolean present) {
        lastFaceSeen = present;
    }

    /** From face tracking: roll-compensate so the head stays upright. */
    public void onFaceRoll(float eulerX, boolean hasFace) {
        if (!hasFace) return;
        float displayRoll = -eulerX; // front camera preview is mirrored
        if (Math.abs(delta(displayRoll, faceDeg)) > SWITCH_THRESHOLD) {
            int q = (int) Math.round(displayRoll / 90f);
            faceDeg = (((q % 4) + 4) % 4) * 90;
        }
    }

    public void toggleFlip() {
        flip = !flip;
    }

    public boolean isFlipped() {
        return flip;
    }

    /** Total rotation to apply to the preview (0/90/180/270). */
    public int compute() {
        int base = lastFaceSeen ? faceDeg : gravityDeg;
        return (base + (flip ? 180 : 0)) % 360;
    }

    private static int delta(float target, int current) {
        int d = (((int) target - current) % 360 + 360) % 360;
        return d > 180 ? d - 360 : d;
    }
}
