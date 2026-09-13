package com.example.beautycamera;

/**
 * 美型变形（liquify）参数生成器：人脸关键点 + 用户滑杆 → shader 可用的变形槽位。
 *
 * <p><b>变形原理（一句话版）</b>：在相机画面上以关键点为中心挖一个"影响圈"，
 * 圈内的采样坐标被缩放（大眼）或平移（瘦脸/瘦鼻/微笑…），画面内容随之变形。
 * 详见 fsOES 着色器与 docs/目录文档。</p>
 *
 * <p>每个 warp 槽位由三元组描述（对应 uWarpCR / uWarpD / uWarpType 三个数组）：
 * <ul>
 *   <li>center(x,y) + radius：圆形作用域，边缘用 t² 平滑衰减避免撕裂</li>
 *   <li>type=0 径向缩放：采样坐标向中心收缩 → 内容外扩（大眼）</li>
 *   <li>type=1 方向推移：采样坐标反向偏移 → 内容朝 delta 方向移动（瘦脸等）</li>
 * </ul></p>
 *
 * <p>所有关键点先按朝向补偿角旋转（与顶点着色器 uRot 同方向、同公式），
 * 保证横持/倒持/平放时变形仍落在正确的五官位置。</p>
 */
final class FaceWarpBuilder {

    static final int WARP_COUNT = 12;

    // ---- warp 槽位 ----
    private static final int W_EYE_L = 0;
    private static final int W_EYE_R = 1;
    private static final int W_CHEEK_L = 2;
    private static final int W_CHEEK_R = 3;
    private static final int W_CHIN = 4;
    private static final int W_NOSE_L = 5;
    private static final int W_NOSE_R = 6;
    private static final int W_MOUTH_L = 7;
    private static final int W_MOUTH_R = 8;
    private static final int W_FOREHEAD = 9;

    private final BeautyParams params;

    FaceWarpBuilder(BeautyParams params) {
        this.params = params;
    }

    /**
     * @param face     人脸关键点（显示空间 uv）
     * @param presence 人脸置信度 0..1（刚出现/刚消失时渐入渐出，防跳变）
     * @param deg      朝向补偿旋转角（0/90/180/270）
     * @param cr       输出：center(xy)+radius 三元组，长度 WARP_COUNT*3
     * @param dd       输出：推移方向 delta，长度 WARP_COUNT*2
     * @param tp       输出：类型（0=径向缩放，1=方向推移）
     */
    void build(FaceData f, float presence, int deg,
               float[] cr, float[] dd, float[] tp) {
        // 先全部置为单位圆（无效果），下面逐槽位覆盖
        for (int i = 0; i < WARP_COUNT; i++) {
            put(cr, dd, tp, i, 0.5f, 0.5f, 0.01f, 0f, 0f, true);
        }
        float s = presence;
        if (s <= 0.01f || !f.hasFace()) return;

        // fw = 有效的脸宽（乘 presence 让变形随人脸出现渐入）
        float fw = f.faceWidth() * s;
        float[] le = rotUv(f.x(FaceData.EYE_L), f.y(FaceData.EYE_L), deg);
        float[] re = rotUv(f.x(FaceData.EYE_R), f.y(FaceData.EYE_R), deg);
        float[] c = rotUv(f.x(FaceData.CENTER), f.y(FaceData.CENTER), deg);
        float[] cl = rotUv(f.x(FaceData.CHEEK_L), f.y(FaceData.CHEEK_L), deg);
        float[] crR = rotUv(f.x(FaceData.CHEEK_R), f.y(FaceData.CHEEK_R), deg);
        float[] ch = rotUv(f.x(FaceData.CHIN), f.y(FaceData.CHIN), deg);
        float[] up = rotVec(0f, 1f, deg); // 旋转后的"画面上方"单位向量

        // 大眼：以双眼为中心的径向放大
        float eyeR = fw * 0.20f;
        put(cr, dd, tp, W_EYE_L, le[0], le[1], eyeR, params.eyeEnlarge * s, 0f, true);
        put(cr, dd, tp, W_EYE_R, re[0], re[1], eyeR, params.eyeEnlarge * s, 0f, true);

        // 瘦脸：把两侧脸颊轮廓朝脸中心方向推
        float cheekR = fw * 0.55f;
        float pull = params.faceSlim * fw * 0.32f * s;
        float[] dl = toward(c, cl, pull);
        float[] dr = toward(c, crR, pull);
        put(cr, dd, tp, W_CHEEK_L, cl[0], cl[1], cheekR, dl[0], dl[1], false);
        put(cr, dd, tp, W_CHEEK_R, crR[0], crR[1], cheekR, dr[0], dr[1], false);

        // 小下巴：下巴区域朝上推
        put(cr, dd, tp, W_CHIN, ch[0], ch[1], fw * 0.35f,
                up[0] * lift(params.chinSlim, fw * 0.18f, s),
                up[1] * lift(params.chinSlim, fw * 0.18f, s), false);

        // 瘦鼻：在鼻底两侧各取一个点，把内容朝鼻梁中线方向推
        if (f.has(FaceData.NOSE) && params.noseSlim > 0.001f) {
            float[] nb = rotUv(f.x(FaceData.NOSE), f.y(FaceData.NOSE), deg);
            float[] perp = perpendicular(le, re, nb); // 垂直于"眼中点→鼻底"连线
            float npull = lift(params.noseSlim, fw * 0.16f, s);
            float nw = fw * 0.13f;
            put(cr, dd, tp, W_NOSE_L, nb[0] - perp[0] * nw, nb[1] - perp[1] * nw, fw * 0.20f,
                    perp[0] * npull, perp[1] * npull, false);
            put(cr, dd, tp, W_NOSE_R, nb[0] + perp[0] * nw, nb[1] + perp[1] * nw, fw * 0.20f,
                    -perp[0] * npull, -perp[1] * npull, false);
        }

        // 微笑：两个嘴角微微上提
        if (f.has(FaceData.MOUTH_L) && f.has(FaceData.MOUTH_R) && params.smileLift > 0.001f) {
            float[] ml = rotUv(f.x(FaceData.MOUTH_L), f.y(FaceData.MOUTH_L), deg);
            float[] mr = rotUv(f.x(FaceData.MOUTH_R), f.y(FaceData.MOUTH_R), deg);
            float lift = lift(params.smileLift, fw * 0.12f, s);
            put(cr, dd, tp, W_MOUTH_L, ml[0], ml[1], fw * 0.18f, up[0] * lift, up[1] * lift, false);
            put(cr, dd, tp, W_MOUTH_R, mr[0], mr[1], fw * 0.18f, up[0] * lift, up[1] * lift, false);
        }

        // 额头：眉心与发际之间整片区域朝上推（显脸小）
        if (f.has(FaceData.BROW) && params.forehead > 0.001f) {
            float[] bm = rotUv(f.x(FaceData.BROW), f.y(FaceData.BROW), deg);
            float[] fh = rotUv(f.x(FaceData.FOREHEAD), f.y(FaceData.FOREHEAD), deg);
            float lift = lift(params.forehead, fw * 0.10f, s);
            put(cr, dd, tp, W_FOREHEAD, (bm[0] + fh[0]) * 0.5f, (bm[1] + fh[1]) * 0.5f,
                    Math.max(dist(bm, fh) * 0.9f, 0.01f), up[0] * lift, up[1] * lift, false);
        }
    }

    // ---- 小工具 ----

    /** 位移幅度 = 强度 × 基准尺寸 × 人脸渐入系数。 */
    private static float lift(float strength, float base, float presence) {
        return strength * base * presence;
    }

    /** 从 from 指向 to 的单位向量，缩放 len 倍。 */
    private static float[] toward(float[] from, float[] to, float len) {
        float x = to[0] - from[0];
        float y = to[1] - from[1];
        float l = Math.max(1e-4f, (float) Math.sqrt(x * x + y * y));
        return new float[]{x / l * len, y / l * len};
    }

    /** "眼中点→鼻底"连线的垂直方向（鼻梁横向的法线，用于定位鼻翼两侧）。 */
    private static float[] perpendicular(float[] le, float[] re, float[] nose) {
        float ex = (le[0] + re[0]) * 0.5f;
        float ey = (le[1] + re[1]) * 0.5f;
        float ax = nose[0] - ex;
        float ay = nose[1] - ey;
        float al = Math.max(1e-4f, (float) Math.sqrt(ax * ax + ay * ay));
        return new float[]{ay / al, -ax / al};
    }

    private static float dist(float[] a, float[] b) {
        float dx = a[0] - b[0];
        float dy = a[1] - b[1];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static void put(float[] cr, float[] dd, float[] tp, int i,
                            float cx, float cy, float r, float dx, float dy, boolean radial) {
        cr[i * 3] = cx;
        cr[i * 3 + 1] = cy;
        cr[i * 3 + 2] = r;
        dd[i * 2] = dx;
        dd[i * 2 + 1] = dy;
        tp[i] = radial ? 0f : 1f;
    }

    /** 绕 (0.5,0.5) 逆时针旋转 deg 度——必须与顶点着色器 uRot 的公式一致。 */
    private static float[] rotUv(float u, float v, int deg) {
        double a = Math.toRadians(deg);
        float c = (float) Math.cos(a);
        float s = (float) Math.sin(a);
        return new float[]{c * (u - 0.5f) - s * (v - 0.5f) + 0.5f,
                s * (u - 0.5f) + c * (v - 0.5f) + 0.5f};
    }

    private static float[] rotVec(float x, float y, int deg) {
        double a = Math.toRadians(deg);
        float c = (float) Math.cos(a);
        float s = (float) Math.sin(a);
        return new float[]{c * x - s * y, s * x + c * y};
    }
}
