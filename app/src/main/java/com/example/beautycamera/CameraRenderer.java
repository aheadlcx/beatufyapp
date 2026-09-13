package com.example.beautycamera;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Camera preview pipeline (GLES2):
 *   camera OES --(face warps)--> full FBO
 *   full --> GuidedFilter (half res) --> q (+ bg blur)
 *   screen/capture: final pass = smoothing mix + dark circles + contouring +
 *   skin tone + sharpen/saturate/filter + background blur mix
 */
public class CameraRenderer implements GLSurfaceView.Renderer {

    // face data slots, see FaceTracker
    private static final int FD_EYE_L = 0;
    private static final int FD_EYE_R = 2;
    private static final int FD_CENTER = 4;
    private static final int FD_FACEW = 6;
    private static final int FD_CHEEK_L = 7;
    private static final int FD_CHEEK_R = 9;
    private static final int FD_CHIN = 11;
    private static final int FD_NOSE = 13;
    private static final int FD_MOUTH_L = 15;
    private static final int FD_MOUTH_R = 17;
    private static final int FD_BROW = 19;
    private static final int FD_FOREHEAD = 21;

    // warp slots
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

    public interface BitmapCallback {
        void onResult(Bitmap bmp);
    }

    public interface SurfaceTextureReadyListener {
        void onReady(SurfaceTexture st);
    }

    /** Runs a task on the GL thread (before the next frame). */
    public interface GLTaskRunner {
        void post(Runnable r);
    }

    private final BeautyParams params;
    private final SurfaceTextureReadyListener onSurfaceTextureReady;

    private final QuadDrawer quad = new QuadDrawer();
    private final GuidedFilter guided = new GuidedFilter(quad);
    private GlProgram oesProgram;
    private GlProgram finalProgram;

    private int oesTex = 0;
    private int maskTex = 0;
    private int maskW = 0;
    private int maskH = 0;
    private boolean maskReady = false;

    private Fbo fboFull;
    private Fbo fboRaw;
    private Fbo fboCapture;
    private int lastQTex = 0;
    private int lastBgTex = 0;

    private final float[] stMatrix = new float[16];
    private SurfaceTexture surfaceTexture;
    private GLTaskRunner glThreadTask;

    private int frameWidth = 0;
    private int frameHeight = 0;
    private int viewWidth = 0;
    private int viewHeight = 0;
    private int upWidth = 0;
    private int upHeight = 0;
    private boolean frontCamera = true;

    private final float[] face = new float[FaceTracker.FACE_DATA_SIZE];
    private final float[] faceTarget = new float[FaceTracker.FACE_DATA_SIZE];
    private float facePresence = 0f;

    public volatile boolean splitMode = false;
    /** orientation-compensation rotation (0/90/180/270), see OrientationBlender */
    public volatile int rotationOverride = 0;
    private float shaderRot = 0f;
    private volatile int bufferWidth = 0;
    private volatile int bufferHeight = 0;

    public CameraRenderer(BeautyParams params, SurfaceTextureReadyListener onSurfaceTextureReady) {
        this.params = params;
        this.onSurfaceTextureReady = onSurfaceTextureReady;
        Arrays.fill(faceTarget, -1f);
        Arrays.fill(face, -1f);
    }

    public void setFaceData(float[] data) {
        synchronized (faceTarget) {
            System.arraycopy(data, 0, faceTarget, 0, FaceTracker.FACE_DATA_SIZE);
        }
    }

    public void setFrontCamera(boolean front) {
        frontCamera = front;
    }

    public void attachGlView(final GLSurfaceView view) {
        glThreadTask = new GLTaskRunner() {
            @Override
            public void post(Runnable r) {
                view.queueEvent(r);
            }
        };
    }

    /** Create a Surface for CameraX to render into. Blocking, runs on camera executor. */
    public Surface createInputSurface(final int width, final int height) {
        final SurfaceTexture st = surfaceTexture;
        if (st == null) throw new IllegalStateException("GL surface not ready");
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Surface> out = new AtomicReference<Surface>();
        Runnable task = new Runnable() {
            @Override
            public void run() {
                st.setDefaultBufferSize(width, height);
                out.set(new Surface(st));
                latch.countDown();
            }
        };
        if (glThreadTask != null) glThreadTask.post(task);
        else task.run();
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        bufferWidth = width;
        bufferHeight = height;
        return out.get();
    }

    /** Upload a segmentation mask (8-bit grayscale) on the GL thread. */
    public void setMask(final int w, final int h, final byte[] data) {
        if (glThreadTask == null) return;
        glThreadTask.post(new Runnable() {
            @Override
            public void run() {
                uploadMask(w, h, data);
            }
        });
    }

    /** Capture the current beauty frame; result delivered off the GL thread. */
    public void capture(final BitmapCallback callback) {
        final Fbo cap = fboCapture;
        final Fbo full = fboFull;
        if (cap == null || full == null || glThreadTask == null) {
            callback.onResult(null);
            return;
        }
        glThreadTask.post(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = readBeautyFrame(cap, full);
                callback.onResult(bmp);
            }
        });
    }

    // ---- GLSurfaceView.Renderer ----

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        oesProgram = GlProgram.createExternal(Shaders.VS, Shaders.FS_OES);
        finalProgram = new GlProgram(Shaders.VS, Shaders.FS_FINAL);
        oesTex = GlProgram.genOesTexture();

        // GL objects from the previous context are gone
        fboFull = null;
        fboRaw = null;
        fboCapture = null;
        guided.reset();
        maskTex = 0;
        maskReady = false;

        surfaceTexture = new SurfaceTexture(oesTex);
        if (onSurfaceTextureReady != null) onSurfaceTextureReady.onReady(surfaceTexture);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        viewWidth = width;
        viewHeight = height;
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (surfaceTexture == null) return;
        try {
            surfaceTexture.updateTexImage();
        } catch (Exception e) {
            return;
        }
        surfaceTexture.getTransformMatrix(stMatrix);
        frameWidth = bufferWidth;
        frameHeight = bufferHeight;
        if (frameWidth <= 0) return;

        // stMatrix already contains the rotation needed to display the buffer
        // upright; rotationOverride adds an extra user/orientation rotation.
        int halRot = normDeg((int) Math.round(
                Math.toDegrees(Math.atan2(stMatrix[1], stMatrix[0]))));
        int total = normDeg(halRot + rotationOverride);
        shaderRot = rotationOverride;
        boolean rotated = (total % 180) != 0;
        upWidth = rotated ? frameHeight : frameWidth;
        upHeight = rotated ? frameWidth : frameHeight;

        ensureTargets();
        updateFacePresence();

        drawOesPass(fboFull, true);
        if (splitMode) {
            drawOesPass(fboRaw, false);
        }

        guided.ensure(upWidth / 2, upHeight / 2);
        int[] qt = guided.run(fboFull.tex, 0.008f, params.bgBlur > 0.001f);
        lastQTex = qt[0];
        lastBgTex = qt[1];

        if (splitMode) {
            int half = viewWidth / 2;
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
            GLES20.glScissor(0, 0, half, viewHeight);
            drawFinal(null, viewWidth, viewHeight, fboRaw.tex, false);
            GLES20.glScissor(half, 0, viewWidth - half, viewHeight);
            drawFinal(null, viewWidth, viewHeight, fboFull.tex, true);
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        } else {
            drawFinal(null, viewWidth, viewHeight, fboFull.tex, true);
        }
    }

    // ---- passes ----

    private void drawOesPass(Fbo target, boolean warps) {
        if (oesProgram == null) return;
        quad.draw(oesProgram, target, new String[0], new int[0], new QuadDrawer.Extra() {
            @Override
            public void onProgram(GlProgram p) {
                p.setMat4("uSTMatrix", stMatrix);
                p.setFloat("uRot", shaderRot);
                p.setFloat("uMirror", frontCamera ? 1f : 0f);
                p.setFloat("uAspect", target.w / (float) target.h);
                p.setInt("sTexture", 0);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
                if (warps) {
                    uploadWarps(p);
                } else {
                    p.setInt("uWarpCount", 0);
                }
            }
        });
    }

    private void uploadWarps(GlProgram p) {
        float[] cr = new float[12 * 3];
        float[] dd = new float[12 * 2];
        float[] tp = new float[12];
        buildWarps(cr, dd, tp);
        p.setVec3Array("uWarpCR", cr, 12);
        p.setVec2Array("uWarpD", dd, 12);
        for (int i = 0; i < 12; i++) {
            GLES20.glUniform1f(p.uniform("uWarpType[" + i + "]"), tp[i]);
        }
        p.setInt("uWarpCount", 12);
    }

    private void drawFinal(Fbo target, int w, int h, int baseTex, final boolean beauty) {
        if (finalProgram == null) return;
        final float cropX = cropScaleX();
        final float cropY = cropScaleY();
        final String[] names = {"sBase", "sQ", "sBg", "sMask"};
        final int[] ids = {baseTex, lastQTex, lastBgTex, maskTex};
        final QuadDrawer.Extra extra = new QuadDrawer.Extra() {
            @Override
            public void onProgram(GlProgram p) {
                p.setVec2("uCrop", cropX, cropY);
                p.setVec2("uTexel", 1f / (float) upWidth, 1f / (float) upHeight);
                p.setFloat("uHasMask", maskReady ? 1f : 0f);
                p.setFloat("uMaskMirror", frontCamera ? 1f : 0f);
                p.setFloat("uDispAspect", dispAspect());
                p.setInt("uFilter", beauty ? params.filterIndex : 0);
                p.setFloat("uFilterStrength", beauty ? params.filterStrength : 0f);
                setBeautyUniforms(p, beauty);
            }
        };
        if (target != null) {
            quad.draw(finalProgram, target, names, ids, extra);
        } else {
            quad.draw(finalProgram, w, h, names, ids, extra);
        }
    }

    private void setBeautyUniforms(GlProgram p, boolean beauty) {
        float k = beauty ? 1f : 0f;
        float s = facePresence * k;
        p.setFloat("uSmooth", params.smooth * k);
        p.setFloat("uWhiten", params.whiten * k);
        p.setFloat("uRuddy", params.ruddy * k);
        p.setFloat("uSharpen", params.sharpen * k);
        p.setFloat("uSaturate", params.saturate * k);
        p.setFloat("uSkinTone", params.skinTone * k);
        p.setFloat("uEyeCircle", params.eyeCircle * s);
        p.setFloat("uContour", params.contouring * s);
        p.setFloat("uBgMix", params.bgBlur * k);

        float[] f = face;
        float edx = (f[FD_EYE_R] - f[FD_EYE_L]) * s;
        float edy = (f[FD_EYE_R + 1] - f[FD_EYE_L + 1]) * s;
        p.setFloat("uEyeDist", (float) Math.sqrt(edx * edx + edy * edy));
        p.setVec2("uEyeL", f[FD_EYE_L], f[FD_EYE_L + 1]);
        p.setVec2("uEyeR", f[FD_EYE_R], f[FD_EYE_R + 1]);
        p.setVec2("uNoseBase", f[FD_NOSE], f[FD_NOSE + 1]);
        p.setVec2("uCheekL", f[FD_CHEEK_L], f[FD_CHEEK_L + 1]);
        p.setVec2("uCheekR", f[FD_CHEEK_R], f[FD_CHEEK_R + 1]);
    }

    // ---- warps ----

    private void buildWarps(float[] cr, float[] dd, float[] tp) {
        for (int i = 0; i < 12; i++) put(cr, dd, tp, i, 0.5f, 0.5f, 0.01f, 0f, 0f, true);
        float s = facePresence;
        if (s <= 0.01f) return;

        // Warp landmarks are tracked in the un-compensated display space; rotate
        // centers and pull directions by the orientation-compensation rotation.
        int deg = rotationOverride;
        float[] f = face;
        float fw = f[FD_FACEW] * s;
        float[] le = rotUv(f[FD_EYE_L], f[FD_EYE_L + 1], deg);
        float[] re = rotUv(f[FD_EYE_R], f[FD_EYE_R + 1], deg);
        float[] c = rotUv(f[FD_CENTER], f[FD_CENTER + 1], deg);
        float[] cl = rotUv(f[FD_CHEEK_L], f[FD_CHEEK_L + 1], deg);
        float[] crp = rotUv(f[FD_CHEEK_R], f[FD_CHEEK_R + 1], deg);
        float[] ch = rotUv(f[FD_CHIN], f[FD_CHIN + 1], deg);
        float[] up = rotVec(0f, 1f, deg);

        float eyeR = fw * 0.20f;
        put(cr, dd, tp, W_EYE_L, le[0], le[1], eyeR, params.eyeEnlarge * s, 0f, true);
        put(cr, dd, tp, W_EYE_R, re[0], re[1], eyeR, params.eyeEnlarge * s, 0f, true);

        float cheekR = fw * 0.55f;
        float pull = params.faceSlim * fw * 0.32f * s;
        float[] dl = toward(c, cl, pull);
        put(cr, dd, tp, W_CHEEK_L, cl[0], cl[1], cheekR, dl[0], dl[1], false);
        float[] dr = toward(c, crp, pull);
        put(cr, dd, tp, W_CHEEK_R, crp[0], crp[1], cheekR, dr[0], dr[1], false);

        put(cr, dd, tp, W_CHIN, ch[0], ch[1], fw * 0.35f,
                up[0] * lift(params.chinSlim, fw * 0.18f, s),
                up[1] * lift(params.chinSlim, fw * 0.18f, s), false);

        if (f[FD_NOSE] >= 0 && params.noseSlim > 0.001f) {
            float[] nb = rotUv(f[FD_NOSE], f[FD_NOSE + 1], deg);
            float[] perp = perpendicular(le, re, nb);
            float npull = lift(params.noseSlim, fw * 0.16f, s);
            float nw = fw * 0.13f;
            put(cr, dd, tp, W_NOSE_L, nb[0] - perp[0] * nw, nb[1] - perp[1] * nw, fw * 0.20f,
                    perp[0] * npull, perp[1] * npull, false);
            put(cr, dd, tp, W_NOSE_R, nb[0] + perp[0] * nw, nb[1] + perp[1] * nw, fw * 0.20f,
                    -perp[0] * npull, -perp[1] * npull, false);
        }

        if (f[FD_MOUTH_L] >= 0 && f[FD_MOUTH_R] >= 0 && params.smileLift > 0.001f) {
            float[] ml = rotUv(f[FD_MOUTH_L], f[FD_MOUTH_L + 1], deg);
            float[] mr = rotUv(f[FD_MOUTH_R], f[FD_MOUTH_R + 1], deg);
            float lift = lift(params.smileLift, fw * 0.12f, s);
            put(cr, dd, tp, W_MOUTH_L, ml[0], ml[1], fw * 0.18f, up[0] * lift, up[1] * lift, false);
            put(cr, dd, tp, W_MOUTH_R, mr[0], mr[1], fw * 0.18f, up[0] * lift, up[1] * lift, false);
        }

        if (f[FD_BROW] >= 0 && params.forehead > 0.001f) {
            float[] bm = rotUv(f[FD_BROW], f[FD_BROW + 1], deg);
            float[] fh = rotUv(f[FD_FOREHEAD], f[FD_FOREHEAD + 1], deg);
            float lift = lift(params.forehead, fw * 0.10f, s);
            put(cr, dd, tp, W_FOREHEAD, (bm[0] + fh[0]) * 0.5f, (bm[1] + fh[1]) * 0.5f,
                    Math.max(dist(bm, fh) * 0.9f, 0.01f), up[0] * lift, up[1] * lift, false);
        }
    }

    // ---- small utilities ----

    private static float lift(float strength, float base, float presence) {
        return strength * base * presence;
    }

    /** Unit direction from `from` toward `to`, scaled by len. */
    private static float[] toward(float[] from, float[] to, float len) {
        float x = to[0] - from[0];
        float y = to[1] - from[1];
        float l = Math.max(1e-4f, (float) Math.sqrt(x * x + y * y));
        return new float[]{x / l * len, y / l * len};
    }

    /** Unit perpendicular of the eye line, oriented away from the nose axis. */
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

    /** Rotate a uv point around (0.5,0.5) CCW by deg - matches the vertex shader. */
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

    private static int normDeg(int deg) {
        int d = deg % 360;
        return d < 0 ? d + 360 : d;
    }

    // ---- frame resources ----

    private void ensureTargets() {
        if (fboFull != null && fboFull.w == upWidth && fboFull.h == upHeight) return;
        releaseTargets();
        fboFull = new Fbo(upWidth, upHeight);
        fboRaw = new Fbo(upWidth, upHeight);
        fboCapture = new Fbo(upWidth, upHeight);
    }

    private void releaseTargets() {
        if (fboFull != null) fboFull.release();
        if (fboRaw != null) fboRaw.release();
        if (fboCapture != null) fboCapture.release();
    }

    private void updateFacePresence() {
        synchronized (faceTarget) {
            boolean hasFace = faceTarget[0] >= 0;
            float goal = hasFace ? 1f : 0f;
            facePresence += (goal - facePresence) * 0.25f;
            if (hasFace) System.arraycopy(faceTarget, 0, face, 0, FaceTracker.FACE_DATA_SIZE);
            if (facePresence < 0.02f && !hasFace) facePresence = 0f;
        }
    }

    private float cropScaleX() {
        float texA = upWidth / (float) upHeight;
        float viewA = viewHeight == 0 ? texA : viewWidth / (float) viewHeight;
        return texA > viewA ? viewA / texA : 1f;
    }

    private float cropScaleY() {
        float texA = upWidth / (float) upHeight;
        float viewA = viewHeight == 0 ? texA : viewWidth / (float) viewHeight;
        return viewA > texA ? texA / viewA : 1f;
    }

    private float dispAspect() {
        float texA = upWidth / (float) upHeight;
        float viewA = viewHeight == 0 ? texA : viewWidth / (float) viewHeight;
        return texA / viewA;
    }

    private void uploadMask(int w, int h, byte[] data) {
        if (maskTex == 0) {
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            maskTex = t[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex);
        if (w != maskW || h != maskH) {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, w, h, 0,
                    GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(new byte[w * h]));
            maskW = w;
            maskH = h;
        }
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(data));
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        maskReady = true;
    }

    private Bitmap readBeautyFrame(Fbo cap, Fbo full) {
        try {
            drawFinal(cap, cap.w, cap.h, full.tex, true);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, cap.fbo);
            ByteBuffer buf = ByteBuffer.allocateDirect(upWidth * upHeight * 4);
            buf.order(java.nio.ByteOrder.nativeOrder());
            GLES20.glReadPixels(0, 0, upWidth, upHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
            Bitmap bmp = Bitmap.createBitmap(upWidth, upHeight, Bitmap.Config.ARGB_8888);
            buf.rewind();
            bmp.copyPixelsFromBuffer(buf);
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postScale(1f, -1f, upWidth / 2f, upHeight / 2f);
            Bitmap flipped = Bitmap.createBitmap(bmp, 0, 0, upWidth, upHeight, m, true);
            bmp.recycle();
            return flipped;
        } catch (Throwable t) {
            return null;
        }
    }
}
