package com.example.beautycamera;

import java.util.Arrays;

/**
 * 一帧人脸关键点数据的类型化封装。
 *
 * <p>为什么用 float[] 而不是一堆字段：这份数据由 FaceTracker（相机分析线程）每帧
 * 产生、跨线程交给 CameraRenderer（GL 线程）消费，用单个数组可以一次
 * {@link System#arraycopy} 完成零拆装的线程交接。本类在数组之上提供命名访问器，
 * 避免魔法下标扩散到业务代码。</p>
 *
 * <p>坐标系约定：所有坐标都是 <b>显示空间的 uv 值</b>（0..1，u 向右、v 向上，
 * 与 GL 纹理坐标一致）；前置摄像头时 u 已做镜像，因此数值与用户看到的预览直接对应。</p>
 *
 * <p>新增一个关键点的步骤：在下面加一个槽位常量 + 一对 set/命名访问器，
 * 然后在 FaceTracker.buildData 里填充。</p>
 */
public final class FaceData {

    public static final int SIZE = 24;

    // ---- 槽位索引（数组内偏移，x 在前、y 在后）----
    public static final int EYE_L = 0;      // 左眼中心
    public static final int EYE_R = 2;      // 右眼中心
    public static final int CENTER = 4;     // 脸部中心
    public static final int FACE_W = 6;     // 脸宽（按帧高归一化）
    public static final int CHEEK_L = 7;    // 左脸颊（脸轮廓最左点）
    public static final int CHEEK_R = 9;    // 右脸颊（脸轮廓最右点）
    public static final int CHIN = 11;      // 下巴最低点
    public static final int NOSE = 13;      // 鼻底（无数据时为 -1）
    public static final int MOUTH_L = 15;   // 左嘴角
    public static final int MOUTH_R = 17;   // 右嘴角
    public static final int BROW = 19;      // 眉心（左右眉质心的中点）
    public static final int FOREHEAD = 21;  // 发际顶点（脸轮廓最高点）

    private final float[] v = new float[SIZE];

    /** 供跨线程整块拷贝使用（FaceTracker → CameraRenderer）。 */
    public float[] raw() {
        return v;
    }

    /** 标记为"无人脸"。 */
    public void clear() {
        Arrays.fill(v, -1f);
    }

    public boolean hasFace() {
        return v[EYE_L] >= 0;
    }

    /** 某个槽位是否有效（optional 关键点如鼻底、嘴角可能缺失）。 */
    public boolean has(int slot) {
        return v[slot] >= 0;
    }

    public float x(int slot) {
        return v[slot];
    }

    public float y(int slot) {
        return v[slot + 1];
    }

    public void set(int slot, float x, float y) {
        v[slot] = x;
        v[slot + 1] = y;
    }

    public float faceWidth() {
        return v[FACE_W];
    }

    /** 两眼中心距离（衡量脸在画面中的大小，用于推导作用半径）。 */
    public float eyeDist() {
        return dist(EYE_L, EYE_R);
    }

    private float dist(int a, int b) {
        float dx = v[b] - v[a];
        float dy = v[b + 1] - v[a + 1];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
