package com.example.beautycamera;

/**
 * 引导滤波（Guided Filter）磨皮，全部在半分辨率下完成。
 *
 * <p><b>为什么是引导滤波</b>：普通高斯模糊会把眼睛、发丝一起糊掉。引导滤波把画面
 * 分成 I（亮度引导图）和 P（原图），为每个局部拟合一条线性关系 q = a·I + b——
 * 平坦皮肤区域 a≈0（输出几乎恒定的 b，即"抹平"），边缘区域 a≈1（输出≈原图，
 * 即"保留"）。效果类似双边滤波，但只用到若干次可分离的高斯模糊，GPU 友好。</p>
 *
 * <p><b>pass 序列</b>（q = a·I + b，均值用高斯模糊近似盒滤波）：
 * <pre>
 *   A = P(降采样)   C = luma(A)          → 引导图 I
 *   D = blur(C)     → meanI
 *   B = blur(A)     → meanP
 *   F = blur(C·A)   → meanIP
 *   G = blur(C·C)   → meanII
 *   a = (meanIP − meanI·meanP) / (meanII − meanI² + eps)   （eps 防除零，控制平滑强度）
 *   b = meanP − a·meanI
 *   blur(a), blur(b)                                       （滤波器自身也要平滑）
 *   A = a·I + b                                            → 输出 q
 * </pre>
 * 背景虚化开启时，对 q 再做两轮强模糊得到 I 槽位的背景图。</p>
 */
public class GuidedFilter {

    // half-res target slots
    private static final int A = 0;  // P -> q
    private static final int B = 1;  // meanP
    private static final int C = 2;  // I (luma)
    private static final int D = 3;  // meanI
    private static final int E = 4;  // scratch (blur ping-pong)
    private static final int F = 5;  // I*P -> meanIP
    private static final int G = 6;  // I*I -> meanII -> b -> meanB
    private static final int H = 7;  // a -> meanA
    private static final int I = 8;  // bg blur
    private static final int SLOTS = 9;

    private static final String[] T1 = {"sTexture"};
    private static final String[] T_MUL = {"sTex1", "sTex2"};
    private static final String[] T_COMBINE_A = {"sI", "sII", "sIP", "sP"};
    private static final String[] T_COMBINE_B = {"sA", "sI", "sP"};
    private static final String[] T_APPLY_Q = {"sA", "sI", "sB"};

    private final QuadDrawer quad;
    private GlProgram luma;
    private GlProgram mul;
    private GlProgram combineA;
    private GlProgram combineB;
    private GlProgram applyQ;
    private GlProgram blur;
    private final Fbo[] fbo = new Fbo[SLOTS];
    private int w = 0;
    private int h = 0;
    private boolean programsReady = false;

    public GuidedFilter(QuadDrawer quad) {
        this.quad = quad;
    }

    /** Programs need a current GL context, so they are built lazily on the GL thread. */
    private void ensurePrograms() {
        if (programsReady) return;
        luma = new GlProgram(Shaders.VS, Shaders.FS_LUMA);
        mul = new GlProgram(Shaders.VS, Shaders.FS_MUL);
        combineA = new GlProgram(Shaders.VS, Shaders.FS_COMBINE_A);
        combineB = new GlProgram(Shaders.VS, Shaders.FS_COMBINE_B);
        applyQ = new GlProgram(Shaders.VS, Shaders.FS_APPLY_Q);
        blur = new GlProgram(Shaders.VS, Shaders.FS_BLUR);
        programsReady = true;
    }

    public void ensure(int halfW, int halfH) {
        if (w == halfW && h == halfH) return;
        release();
        w = halfW;
        h = halfH;
        for (int i = 0; i < SLOTS; i++) fbo[i] = new Fbo(w, h);
    }

    /** Full reset when the GL context is recreated (programs become invalid). */
    public void reset() {
        release();
        programsReady = false;
    }

    public void release() {
        for (int i = 0; i < SLOTS; i++) {
            if (fbo[i] != null) fbo[i].release();
            fbo[i] = null;
        }
        w = 0;
        h = 0;
    }

    /** Run the chain; returns {qTex, bgTex}. When bgBlur is off, qTex is
     *  returned for both (the final pass guards bg mixing by uBgMix anyway). */
    public int[] run(int fullTex, float eps, boolean bgBlur) {
        ensurePrograms();
        ensure(w, h);
        blur(fullTex, fbo[A]);                                  // A = P
        one(luma, fbo[A].tex, fbo[C]);                          // C = I
        multi(mul, T_MUL, fbo[C].tex, fbo[A].tex, fbo[F], null);    // F = I*P
        blur(fbo[C].tex, fbo[D]);                               // D = meanI
        blur(fbo[F].tex, fbo[F]);                               // F = meanIP
        blur(fbo[A].tex, fbo[B]);                               // B = meanP
        multi(mul, T_MUL, fbo[C].tex, fbo[C].tex, fbo[G], null);    // G = I*I
        blur(fbo[G].tex, fbo[G]);                               // G = meanII
        multi(combineA, T_COMBINE_A, fbo[D].tex, fbo[G].tex, fbo[F].tex, fbo[B].tex,
                fbo[H], setEps(eps));                           // H = a
        multi(combineB, T_COMBINE_B, fbo[H].tex, fbo[D].tex, fbo[B].tex, fbo[G], null); // G = b
        blur(fbo[H].tex, fbo[H]);                               // H = meanA
        blur(fbo[G].tex, fbo[G]);                               // G = meanB
        multi(applyQ, T_APPLY_Q, fbo[H].tex, fbo[C].tex, fbo[G].tex, fbo[A], null);     // A = q
        if (bgBlur) {
            blur(fbo[A].tex, fbo[I]);
            blur(fbo[I].tex, fbo[I]);
            return new int[]{fbo[A].tex, fbo[I].tex};
        }
        return new int[]{fbo[A].tex, fbo[A].tex};
    }

    // ---- draw helpers (thin wrappers over QuadDrawer) ----

    private void one(GlProgram p, int tex, Fbo dst) {
        quad.draw(p, dst, T1, new int[]{tex}, null);
    }

    private void multi(GlProgram p, String[] names, int t1, int t2, Fbo dst,
                       QuadDrawer.Extra extra) {
        quad.draw(p, dst, names, new int[]{t1, t2}, extra);
    }

    private void multi(GlProgram p, String[] names, int t1, int t2, int t3, Fbo dst,
                       QuadDrawer.Extra extra) {
        quad.draw(p, dst, names, new int[]{t1, t2, t3}, extra);
    }

    private void multi(GlProgram p, String[] names, int t1, int t2, int t3, int t4, Fbo dst,
                       QuadDrawer.Extra extra) {
        quad.draw(p, dst, names, new int[]{t1, t2, t3, t4}, extra);
    }

    private static QuadDrawer.Extra setEps(final float eps) {
        return new QuadDrawer.Extra() {
            @Override
            public void onProgram(GlProgram p) {
                p.setFloat("uEps", eps);
            }
        };
    }

    /** Separable gaussian: horizontal into the scratch target, vertical into dst. */
    private void blur(final int src, final Fbo dst) {
        quad.draw(blur, fbo[E], T1, new int[]{src}, new QuadDrawer.Extra() {
            @Override
            public void onProgram(GlProgram p) {
                p.setVec2("uTexel", 1f / dst.w, 0f);
            }
        });
        quad.draw(blur, dst, T1, new int[]{fbo[E].tex}, new QuadDrawer.Extra() {
            @Override
            public void onProgram(GlProgram p) {
                p.setVec2("uTexel", 0f, 1f / dst.h);
            }
        });
    }
}
