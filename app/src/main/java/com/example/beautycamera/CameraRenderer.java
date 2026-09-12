package com.example.beautycamera;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * GL pipeline:
 *   camera OES texture --(warp: big eyes / slim face / chin)--> FBO full
 *   FBO full --(downsample + 2x gaussian blur)--> FBO half
 *   screen/capture = base + edge-aware skin smoothing + whiten/ruddy/sharpen/saturate + filter
 */
public class CameraRenderer implements GLSurfaceView.Renderer {

    private static final int MAX_WARPS = 8;
    private static final int SLOT_EYE_L = 0;
    private static final int SLOT_EYE_R = 1;
    private static final int SLOT_CHEEK_L = 2;
    private static final int SLOT_CHEEK_R = 3;
    private static final int SLOT_CHIN = 4;

    public interface BitmapCallback {
        void onResult(Bitmap bmp);
    }

    private static final String VS =
        "attribute vec4 aPosition;\n" +
        "attribute vec2 aTexCoord;\n" +
        "uniform mat4 uSTMatrix;\n" +
        "uniform vec2 uCrop;\n" +
        "uniform float uMirror;\n" +
        "uniform float uRot;\n" +
        "varying vec2 vUV;\n" +
        "void main() {\n" +
        "    gl_Position = aPosition;\n" +
        "    vec2 uv = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;\n" +
        "    if (uRot != 0.0) {\n" +
        "        vec2 c = uv - 0.5;\n" +
        "        float a = radians(uRot);\n" +
        "        uv = vec2(c.x * cos(a) - c.y * sin(a), c.x * sin(a) + c.y * cos(a)) + 0.5;\n" +
        "    }\n" +
        "    if (uMirror > 0.5) uv.x = 1.0 - uv.x;\n" +
        "    vUV = (uv - 0.5) * uCrop + 0.5;\n" +
        "}\n";

    private static final String FS_OES =
        "uniform samplerExternalOES sTexture;\n" +
        "uniform float uAspect;\n" +
        "uniform int uWarpCount;\n" +
        "uniform vec3 uWarpCR[" + MAX_WARPS + "];\n" +
        "uniform vec2 uWarpD[" + MAX_WARPS + "];\n" +
        "uniform float uWarpType[" + MAX_WARPS + "];\n" +
        "varying vec2 vUV;\n" +
        "void main() {\n" +
        "    vec2 p = (vUV - 0.5) * vec2(uAspect, 1.0);\n" +
        "    for (int i = 0; i < " + MAX_WARPS + "; i++) {\n" +
        "        if (i >= uWarpCount) break;\n" +
        "        vec2 c = (uWarpCR[i].xy - 0.5) * vec2(uAspect, 1.0);\n" +
        "        float r = uWarpCR[i].z;\n" +
        "        vec2 d = p - c;\n" +
        "        float dist = length(d);\n" +
        "        if (dist < r) {\n" +
        "            float t = 1.0 - dist / r;\n" +
        "            float fall = t * t;\n" +
        "            if (uWarpType[i] < 0.5) {\n" +
        "                p = c + d * (1.0 - uWarpD[i].x * fall);\n" +
        "            } else {\n" +
        "                p = p - uWarpD[i] * fall;\n" +
        "            }\n" +
        "        }\n" +
        "    }\n" +
        "    vec2 uv = p / vec2(uAspect, 1.0) + 0.5;\n" +
        "    gl_FragColor = texture2D(sTexture, clamp(uv, 0.001, 0.999));\n" +
        "}\n";

    private static final String FS_COPY =
        "precision mediump float;\n" +
        "uniform sampler2D sTexture;\n" +
        "varying vec2 vUV;\n" +
        "void main() { gl_FragColor = texture2D(sTexture, vUV); }\n";

    private static final String FS_BLUR =
        "precision mediump float;\n" +
        "uniform sampler2D sTexture;\n" +
        "uniform vec2 uTexel;\n" +
        "varying vec2 vUV;\n" +
        "void main() {\n" +
        "    vec3 sum = texture2D(sTexture, vUV).rgb * 0.227027;\n" +
        "    sum += texture2D(sTexture, vUV + uTexel * 1.3846).rgb * 0.316216;\n" +
        "    sum += texture2D(sTexture, vUV - uTexel * 1.3846).rgb * 0.316216;\n" +
        "    sum += texture2D(sTexture, vUV + uTexel * 3.2308).rgb * 0.070270;\n" +
        "    sum += texture2D(sTexture, vUV - uTexel * 3.2308).rgb * 0.070270;\n" +
        "    gl_FragColor = vec4(sum, 1.0);\n" +
        "}\n";

    private static final String FS_FINAL =
        "precision mediump float;\n" +
        "uniform sampler2D sBase;\n" +
        "uniform sampler2D sBlur;\n" +
        "uniform vec2 uTexel;\n" +
        "uniform float uSmooth;\n" +
        "uniform float uWhiten;\n" +
        "uniform float uRuddy;\n" +
        "uniform float uSharpen;\n" +
        "uniform float uSaturate;\n" +
        "uniform int uFilter;\n" +
        "uniform float uFilterStrength;\n" +
        "varying vec2 vUV;\n" +
        "float lum(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }\n" +
        "float skinMask(vec3 c) {\n" +
        "    float y = lum(c);\n" +
        "    float cb = 0.5 - 0.169 * c.r - 0.331 * c.g + 0.5 * c.b;\n" +
        "    float cr = 0.5 + 0.5 * c.r - 0.419 * c.g - 0.081 * c.b;\n" +
        "    float m1 = smoothstep(0.29, 0.34, cb) * (1.0 - smoothstep(0.48, 0.53, cb));\n" +
        "    float m2 = smoothstep(0.51, 0.55, cr) * (1.0 - smoothstep(0.71, 0.75, cr));\n" +
        "    return m1 * m2 * smoothstep(0.05, 0.2, y);\n" +
        "}\n" +
        "vec3 applyFilter(vec3 c) {\n" +
        "    vec3 f = c;\n" +
        "    if (uFilter == 1) {\n" +
        "        f = c * 1.10 + 0.03;\n" +
        "        f = mix(vec3(lum(f)), f, 0.92);\n" +
        "    } else if (uFilter == 2) {\n" +
        "        f = vec3(c.r * 1.09, c.g * 1.03, c.b * 0.92) * 1.05;\n" +
        "    } else if (uFilter == 3) {\n" +
        "        f = vec3(c.r * 0.94, c.g * 1.00, c.b * 1.09);\n" +
        "    } else if (uFilter == 4) {\n" +
        "        f = mix(c, c * c * (3.0 - 2.0 * c), 0.4);\n" +
        "        float d = length(vUV - 0.5);\n" +
        "        f *= 1.0 - 0.30 * d * d;\n" +
        "        f.b += 0.02;\n" +
        "    } else if (uFilter == 5) {\n" +
        "        f = vec3(c.r + 0.05, c.g + 0.02, c.b + 0.04);\n" +
        "        f = mix(vec3(lum(f)), f, 1.08);\n" +
        "    }\n" +
        "    return mix(c, clamp(f, 0.0, 1.0), uFilterStrength);\n" +
        "}\n" +
        "void main() {\n" +
        "    vec3 base = texture2D(sBase, vUV).rgb;\n" +
        "    vec3 blur = texture2D(sBlur, vUV).rgb;\n" +
        "    float detail = length(base - blur);\n" +
        "    float edge = smoothstep(0.02, 0.16, detail);\n" +
        "    float skin = skinMask(base);\n" +
        "    float w = clamp(uSmooth, 0.0, 1.0) * (1.0 - edge) * mix(0.35, 1.0, skin);\n" +
        "    vec3 color = mix(base, blur, w);\n" +
        "    float y = lum(color);\n" +
        "    color *= 1.0 + uWhiten * skin * (1.0 - y) * 0.9;\n" +
        "    color.r += uRuddy * skin * 0.06 * (1.0 - y);\n" +
        "    color.b -= uRuddy * skin * 0.02;\n" +
        "    vec3 nb = texture2D(sBase, vUV + vec2(uTexel.x, 0.0)).rgb\n" +
        "            + texture2D(sBase, vUV - vec2(uTexel.x, 0.0)).rgb\n" +
        "            + texture2D(sBase, vUV + vec2(0.0, uTexel.y)).rgb\n" +
        "            + texture2D(sBase, vUV - vec2(0.0, uTexel.y)).rgb;\n" +
        "    color += (base * 4.0 - nb) * uSharpen * 0.35;\n" +
        "    float l2 = lum(color);\n" +
        "    color = mix(vec3(l2), color, 1.0 + uSaturate);\n" +
        "    color = applyFilter(color);\n" +
        "    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);\n" +
        "}\n";

    // ---- GL objects ----
    private GlProgram oesProgram;
    private GlProgram copyProgram;
    private GlProgram blurProgram;
    private GlProgram finalProgram;
    private FloatBuffer quadPos;
    private FloatBuffer quadUv;
    private int oesTex = 0;
    private Fbo fboFull;
    private Fbo fboRaw;
    private Fbo fboA;
    private Fbo fboB;
    private Fbo fboCapture;

    private final float[] stMatrix = new float[16];
    private final float[] idMatrix = new float[16];

    private SurfaceTexture surfaceTexture;
    private int frameWidth = 0;   // camera buffer size
    private int frameHeight = 0;
    private int upWidth = 0;       // upright (rotation-corrected) size
    private int upHeight = 0;
    private int viewWidth = 0;
    private int viewHeight = 0;
    private boolean frontCamera = true;

    /** face geometry in display-normalized uv, published by FaceTracker (all -1 when absent) */
    private final float[] face = new float[14];
    private final float[] faceTarget = new float[14];
    private float facePresence = 0f;

    public volatile boolean splitMode = false;
    /** additional user rotation (debug long-press gesture): 0/90/180/270 */
    public volatile int rotationOverride = 0;
    private float rotationRad = 0f;
    private volatile int stLegacyWidth = 0;
    private volatile int stLegacyHeight = 0;

    private final BeautyParams params;
    private final SurfaceTextureReadyListener onSurfaceTextureReady;
    private GLTaskRunner glThreadTask;

    public interface SurfaceTextureReadyListener {
        void onReady(SurfaceTexture st);
    }

    public interface GLTaskRunner {
        void post(Runnable r);
    }

    public CameraRenderer(BeautyParams params, SurfaceTextureReadyListener onSurfaceTextureReady) {
        this.params = params;
        this.onSurfaceTextureReady = onSurfaceTextureReady;
        Matrix.setIdentityM(idMatrix, 0);
        Arrays.fill(faceTarget, -1f);
        Arrays.fill(face, -1f);
    }

    public void setFaceData(float[] data) {
        synchronized (faceTarget) {
            System.arraycopy(data, 0, faceTarget, 0, 14);
        }
    }

    public void setFrontCamera(boolean front) {
        frontCamera = front;
    }

    private static int normDeg(int deg) {
        int d = deg % 360;
        return d < 0 ? d + 360 : d;
    }

    public void attachGlView(final GLSurfaceView view) {
        glThreadTask = new GLTaskRunner() {
            @Override
            public void post(Runnable r) {
                view.queueEvent(r);
            }
        };
    }

    /** Create a Surface for CameraX to render into. Blocking, called from camera executor. */
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
        stLegacyWidth = width;
        stLegacyHeight = height;
        return out.get();
    }

    /** Capture the current beauty-processed frame. Result bitmap delivered off the GL thread. */
    public void capture(final BitmapCallback callback) {
        final Fbo cap = fboCapture;
        final Fbo f = fboFull;
        final Fbo a = fboA;
        if (cap == null || f == null || a == null || glThreadTask == null) {
            callback.onResult(null);
            return;
        }
        glThreadTask.post(new Runnable() {
            @Override
            public void run() {
                try {
                    drawFinalPass(cap, upWidth, upHeight, 1f, 1f, f.tex, true);
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, cap.fbo);
                    ByteBuffer buf = ByteBuffer.allocateDirect(upWidth * upHeight * 4)
                            .order(ByteOrder.nativeOrder());
                    GLES20.glReadPixels(0, 0, upWidth, upHeight,
                            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
                    Bitmap bmp = Bitmap.createBitmap(upWidth, upHeight, Bitmap.Config.ARGB_8888);
                    buf.rewind();
                    bmp.copyPixelsFromBuffer(buf);
                    android.graphics.Matrix m = new android.graphics.Matrix();
                    m.postScale(1f, -1f, upWidth / 2f, upHeight / 2f);
                    Bitmap flipped = Bitmap.createBitmap(bmp, 0, 0, upWidth, upHeight, m, true);
                    bmp.recycle();
                    callback.onResult(flipped);
                } catch (Throwable t) {
                    android.util.Log.e("BeautyRenderer", "capture failed", t);
                    callback.onResult(null);
                }
            }
        });
    }

    // ---- Renderer ----

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        oesProgram = GlProgram.createExternal(VS, FS_OES);
        copyProgram = new GlProgram(VS, FS_COPY);
        blurProgram = new GlProgram(VS, FS_BLUR);
        finalProgram = new GlProgram(VS, FS_FINAL);

        quadPos = GlProgram.floatBuffer(new float[]{-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f});
        quadUv = GlProgram.floatBuffer(new float[]{0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f});

        oesTex = GlProgram.genOesTexture();
        SurfaceTexture st = new SurfaceTexture(oesTex);
        surfaceTexture = st;
        if (onSurfaceTextureReady != null) onSurfaceTextureReady.onReady(st);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        viewWidth = width;
        viewHeight = height;
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        SurfaceTexture st = surfaceTexture;
        if (st == null) return;
        try {
            st.updateTexImage();
        } catch (Exception e) {
            return;
        }
        st.getTransformMatrix(stMatrix);
        frameWidth = stLegacyWidth;
        frameHeight = stLegacyHeight;
        if (frameWidth <= 0) return;
        // Orientation handling. The SurfaceTexture transform matrix already
        // contains whatever rotation is needed to display the buffer upright
        // (device-dependent: some HALs rotate, some do not). So we simply follow
        // it. rotationOverride is an ADDITIONAL user rotation (debug gesture).
        int halRot = normDeg((int) Math.round(
                Math.toDegrees(Math.atan2(stMatrix[1], stMatrix[0]))));
        int total = normDeg(halRot + rotationOverride);
        int shaderRot = rotationOverride;
        rotationRad = shaderRot;
        boolean rotated = (total % 180) != 0;
        upWidth = rotated ? frameHeight : frameWidth;
        upHeight = rotated ? frameWidth : frameHeight;

        ensureFbos();

        synchronized (faceTarget) {
            boolean hasFace = faceTarget[0] >= 0;
            float goal = hasFace ? 1f : 0f;
            facePresence += (goal - facePresence) * 0.25f;
            if (hasFace) System.arraycopy(faceTarget, 0, face, 0, 14);
            if (facePresence < 0.02f && !hasFace) facePresence = 0f;
        }

        Fbo full = fboFull;
        if (full == null) return;

        // pass 1a: OES -> full, with face warps
        bindFbo(full);
        GLES20.glViewport(0, 0, full.w, full.h);
        drawOesPass(full, true);

        // pass 1b: OES -> raw, untouched (for split-compare left half)
        Fbo raw = fboRaw;
        if (raw != null && splitMode) {
            bindFbo(raw);
            GLES20.glViewport(0, 0, raw.w, raw.h);
            drawOesPass(raw, false);
        }

        Fbo a = fboA;
        Fbo b = fboB;
        // downsample full -> halfA
        bindFbo(a);
        GLES20.glViewport(0, 0, a.w, a.h);
        drawSimple(copyProgram, full.tex, idMatrix);

        // 2 iterations of separable gaussian at half res
        for (int i = 0; i < 2; i++) {
            bindFbo(b);
            GLES20.glViewport(0, 0, b.w, b.h);
            drawBlur(a.tex, 1f / a.w, 0f);
            bindFbo(a);
            GLES20.glViewport(0, 0, a.w, a.h);
            drawBlur(b.tex, 0f, 1f / b.h);
        }

        float cx = cropScaleX();
        float cy = cropScaleY();
        if (splitMode && raw != null) {
            int half = viewWidth / 2;
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
            GLES20.glScissor(0, 0, half, viewHeight);
            drawFinalPass(null, viewWidth, viewHeight, cx, cy, raw.tex, false);
            GLES20.glScissor(half, 0, viewWidth - half, viewHeight);
            drawFinalPass(null, viewWidth, viewHeight, cx, cy, full.tex, true);
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        } else {
            drawFinalPass(null, viewWidth, viewHeight, cx, cy, full.tex, true);
        }
    }

    private void drawOesPass(Fbo target, boolean warps) {
        GlProgram p = oesProgram;
        if (p == null) return;
        p.use();
        setupQuad(p);
        p.setMat4("uSTMatrix", stMatrix);
        p.setInt("sTexture", 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
        p.setVec2("uCrop", 1f, 1f);
        p.setFloat("uRot", rotationRad);
        p.setFloat("uMirror", frontCamera ? 1f : 0f);
        p.setFloat("uAspect", target.w / (float) target.h);
        if (warps) {
            p.setInt("uWarpCount", MAX_WARPS);
            float[] cr = new float[MAX_WARPS * 3];
            float[] dd = new float[MAX_WARPS * 2];
            float[] tp = new float[MAX_WARPS];
            buildWarps(cr, dd, tp);
            p.setVec3Array("uWarpCR", cr, MAX_WARPS);
            p.setVec2Array("uWarpD", dd, MAX_WARPS);
            for (int i = 0; i < MAX_WARPS; i++) {
                GLES20.glUniform1f(p.uniform("uWarpType[" + i + "]"), tp[i]);
            }
        } else {
            p.setInt("uWarpCount", 0);
        }
        drawQuad();
    }

    private void drawFinalPass(Fbo target, int w, int h, float cropX, float cropY,
                               int baseTexOverride, boolean beauty) {
        Fbo f = fboFull;
        Fbo a = fboA;
        if (f == null || a == null || finalProgram == null) return;
        if (target != null) {
            bindFbo(target);
            GLES20.glViewport(0, 0, target.w, target.h);
        } else {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, w, h);
        }
        GlProgram p = finalProgram;
        p.use();
        setupQuad(p);
        p.setMat4("uSTMatrix", idMatrix);
        p.setVec2("uCrop", cropX, cropY);
        p.setFloat("uMirror", 0f);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, baseTexOverride != 0 ? baseTexOverride : f.tex);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, a.tex);
        p.setInt("sBase", 0);
        p.setInt("sBlur", 1);
        p.setVec2("uTexel", 1f / f.w, 1f / f.h);
        if (beauty) {
            p.setFloat("uSmooth", params.smooth);
            p.setFloat("uWhiten", params.whiten);
            p.setFloat("uRuddy", params.ruddy);
            p.setFloat("uSharpen", params.sharpen);
            p.setFloat("uSaturate", params.saturate);
            p.setInt("uFilter", params.filterIndex);
            p.setFloat("uFilterStrength", params.filterStrength);
        } else {
            p.setFloat("uSmooth", 0f);
            p.setFloat("uWhiten", 0f);
            p.setFloat("uRuddy", 0f);
            p.setFloat("uSharpen", 0f);
            p.setFloat("uSaturate", 0f);
            p.setInt("uFilter", 0);
            p.setFloat("uFilterStrength", 0f);
        }
        drawQuad();
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }

    // ---- helpers ----

    private void ensureFbos() {
        Fbo f = fboFull;
        if (f == null || f.w != upWidth || f.h != upHeight) {
            if (fboFull != null) fboFull.release();
            if (fboRaw != null) fboRaw.release();
            if (fboA != null) fboA.release();
            if (fboB != null) fboB.release();
            if (fboCapture != null) fboCapture.release();
            fboFull = new Fbo(upWidth, upHeight);
            fboRaw = new Fbo(upWidth, upHeight);
            int hw = Math.max(upWidth / 2, 2);
            int hh = Math.max(upHeight / 2, 2);
            fboA = new Fbo(hw, hh);
            fboB = new Fbo(hw, hh);
            fboCapture = new Fbo(upWidth, upHeight);
        }
    }

    /** center-crop: sample range must SHRINK (<1) on the axis with surplus content.
     *  Returning >1 would sample outside [0,1] and smear the edges. */
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

    private void buildWarps(float[] cr, float[] dd, float[] tp) {
        float s = facePresence;
        if (s <= 0.01f) {
            Arrays.fill(dd, 0f);
            for (int i = 0; i < MAX_WARPS; i++) put(cr, dd, tp, i, 0.5f, 0.5f, 0.01f, 0f, 0f, true);
            return;
        }
        // Face landmarks are tracked in the un-compensated display space; when an
        // orientation-compensation rotation is applied to the whole frame the warp
        // centers and pull directions must rotate identically.
        int deg = rotationOverride;
        float[] f = face;
        float fw = f[6] * s;
        float[] le = rotUv(f[0], f[1], deg);
        float[] re = rotUv(f[2], f[3], deg);
        float[] c = rotUv(f[4], f[5], deg);
        float[] cl = rotUv(f[8], f[9], deg);
        float[] crp = rotUv(f[10], f[11], deg);
        float[] ch = rotUv(f[12], f[13], deg);

        float eyeR = fw * 0.20f;
        put(cr, dd, tp, SLOT_EYE_L, le[0], le[1], eyeR, params.eyeEnlarge * s, 0f, true);
        put(cr, dd, tp, SLOT_EYE_R, re[0], re[1], eyeR, params.eyeEnlarge * s, 0f, true);

        float cheekR = fw * 0.55f;
        float pull = params.faceSlim * fw * 0.32f * s;
        float ox = c[0] - cl[0];
        float oy = c[1] - cl[1];
        float len = Math.max(1e-4f, (float) Math.sqrt(ox * ox + oy * oy));
        put(cr, dd, tp, SLOT_CHEEK_L, cl[0], cl[1], cheekR, ox / len * pull, oy / len * pull, false);
        ox = c[0] - crp[0];
        oy = c[1] - crp[1];
        len = Math.max(1e-4f, (float) Math.sqrt(ox * ox + oy * oy));
        put(cr, dd, tp, SLOT_CHEEK_R, crp[0], crp[1], cheekR, ox / len * pull, oy / len * pull, false);

        // chin: pull along the rotated "face up" direction
        float[] up = rotVec(0f, 1f, deg);
        put(cr, dd, tp, SLOT_CHIN, ch[0], ch[1], fw * 0.35f, up[0] * params.chinSlim * fw * 0.18f * s,
                up[1] * params.chinSlim * fw * 0.18f * s, false);

        for (int i = 5; i < MAX_WARPS; i++) put(cr, dd, tp, i, 0.5f, 0.5f, 0.01f, 0f, 0f, true);
    }

    /** Rotate a uv point around (0.5,0.5) CCW by deg - matches the vertex shader. */
    private static float[] rotUv(float u, float v, int deg) {
        double a = Math.toRadians(deg);
        float c = (float) Math.cos(a);
        float s = (float) Math.sin(a);
        float cu = u - 0.5f;
        float cv = v - 0.5f;
        return new float[]{c * cu - s * cv + 0.5f, s * cu + c * cv + 0.5f};
    }

    private static float[] rotVec(float x, float y, int deg) {
        double a = Math.toRadians(deg);
        float c = (float) Math.cos(a);
        float s = (float) Math.sin(a);
        return new float[]{c * x - s * y, s * x + c * y};
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

    private void bindFbo(Fbo f) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f.fbo);
    }

    private void drawSimple(GlProgram p, int tex, float[] m) {
        p.use();
        setupQuad(p);
        p.setMat4("uSTMatrix", m);
        p.setInt("sTexture", 0);
        p.setVec2("uCrop", 1f, 1f);
        p.setFloat("uMirror", 0f);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        drawQuad();
    }

    private void drawBlur(int tex, float dx, float dy) {
        GlProgram p = blurProgram;
        p.use();
        setupQuad(p);
        p.setMat4("uSTMatrix", idMatrix);
        p.setInt("sTexture", 0);
        p.setVec2("uCrop", 1f, 1f);
        p.setFloat("uMirror", 0f);
        p.setVec2("uTexel", dx, dy);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        drawQuad();
    }

    private void setupQuad(GlProgram p) {
        int aPos = GLES20.glGetAttribLocation(p.handle(), "aPosition");
        int aUv = GLES20.glGetAttribLocation(p.handle(), "aTexCoord");
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quadPos);
        GLES20.glEnableVertexAttribArray(aUv);
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, quadUv);
    }

    private void drawQuad() {
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    static class Fbo {
        final int w;
        final int h;
        final int tex;
        final int fbo;

        Fbo(int w, int h) {
            this.w = w;
            this.h = h;
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            tex = t[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

            int[] arr = new int[1];
            GLES20.glGenFramebuffers(1, arr, 0);
            fbo = arr[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, tex, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }

        void release() {
            GLES20.glDeleteFramebuffers(1, new int[]{fbo}, 0);
            GLES20.glDeleteTextures(1, new int[]{tex}, 0);
        }
    }
}
