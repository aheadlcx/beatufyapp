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
 * GL pipeline (GLES2):
 *   camera OES --(face warps)--> full FBO
 *   full --downsample--> half A(P)
 *   guided filter (edge-aware smoothing) over half-res --> A(q)
 *   q --extra blur (optional)--> I  (background blur source)
 *   screen/capture = final: skin smoothing mix + dark-circle removal +
 *   face contouring + skin tone + sharpen/saturate/filter + bg blur mix
 */
public class CameraRenderer implements GLSurfaceView.Renderer {

    private static final int MAX_WARPS = 12;
    private static final int FACE_SIZE = FaceTracker.FACE_DATA_SIZE;

    // face data slots (see FaceTracker)
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

    public interface GLTaskRunner {
        void post(Runnable r);
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
        "precision mediump float;\n" +
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

    // ---- guided filter helper shaders ----
    private static final String FS_LUMA =
        "precision mediump float;\n" +
        "uniform sampler2D sTexture;\n" +
        "varying vec2 vUV;\n" +
        "void main() {\n" +
        "    vec3 c = texture2D(sTexture, vUV).rgb;\n" +
        "    float y = dot(c, vec3(0.299, 0.587, 0.114));\n" +
        "    gl_FragColor = vec4(y, y, y, 1.0);\n" +
        "}\n";

    private static final String FS_MUL =
        "precision mediump float;\n" +
        "uniform sampler2D sTex1;\n" +
        "uniform sampler2D sTex2;\n" +
        "varying vec2 vUV;\n" +
        "void main() { gl_FragColor = texture2D(sTex1, vUV) * texture2D(sTex2, vUV); }\n";

    private static final String FS_COMBINE_A =
        "precision mediump float;\n" +
        "uniform sampler2D sI;\n" +     // meanI
        "uniform sampler2D sII;\n" +   // meanII
        "uniform sampler2D sIP;\n" +   // meanIP
        "uniform sampler2D sP;\n" +    // meanP
        "uniform float uEps;\n" +
        "varying vec2 vUV;\n" +
        "void main() {\n" +
        "    float mI = texture2D(sI, vUV).r;\n" +
        "    float mII = texture2D(sII, vUV).r;\n" +
        "    float mIP = texture2D(sIP, vUV).r;\n" +
        "    float mP = texture2D(sP, vUV).r;\n" +
        "    float varI = mII - mI * mI;\n" +
        "    float covIP = mIP - mI * mP;\n" +
        "    float a = covIP / (varI + uEps);\n" +
        "    gl_FragColor = vec4(a, 0.0, 0.0, 1.0);\n" +
        "}\n";

    private static final String FS_COMBINE_B =
        "precision mediump float;\n" +
        "uniform sampler2D sA;\n" +
        "uniform sampler2D sI;\n" +
        "uniform sampler2D sP;\n" +
        "varying vec2 vUV;\n" +
        "void main() {\n" +
        "    float a = texture2D(sA, vUV).r;\n" +
        "    float mI = texture2D(sI, vUV).r;\n" +
        "    float mP = texture2D(sP, vUV).r;\n" +
        "    gl_FragColor = vec4(mP - a * mI, 0.0, 0.0, 1.0);\n" +
        "}\n";

    private static final String FS_APPLY_Q =
        "precision mediump float;\n" +
        "uniform sampler2D sA;\n" +
        "uniform sampler2D sI;\n" +
        "uniform sampler2D sB;\n" +
        "varying vec2 vUV;\n" +
        "void main() {\n" +
        "    float a = texture2D(sA, vUV).r;\n" +
        "    float i = texture2D(sI, vUV).r;\n" +
        "    float b = texture2D(sB, vUV).r;\n" +
        "    float q = a * i + b;\n" +
        "    gl_FragColor = vec4(q, q, q, 1.0);\n" +
        "}\n";

    private static final String FS_FINAL =
        "precision mediump float;\n" +
        "uniform sampler2D sBase;\n" +
        "uniform sampler2D sQ;\n" +
        "uniform sampler2D sBg;\n" +
        "uniform sampler2D sMask;\n" +
        "uniform float uHasMask;\n" +
        "uniform float uMaskMirror;\n" +
        "uniform vec2 uTexel;\n" +
        "uniform float uSmooth;\n" +
        "uniform float uWhiten;\n" +
        "uniform float uRuddy;\n" +
        "uniform float uSharpen;\n" +
        "uniform float uSaturate;\n" +
        "uniform float uSkinTone;\n" +
        "uniform float uEyeCircle;\n" +
        "uniform float uContour;\n" +
        "uniform vec2 uEyeL;\n" +
        "uniform vec2 uEyeR;\n" +
        "uniform float uEyeDist;\n" +
        "uniform vec2 uNoseBase;\n" +
        "uniform vec2 uCheekL;\n" +
        "uniform vec2 uCheekR;\n" +
        "uniform float uDispAspect;\n" +
        "uniform float uBgMix;\n" +
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
        "    } else if (uFilter == 6) {\n" +
        "        f = vec3(lum(c));\n" +
        "    } else if (uFilter == 7) {\n" +
        "        f = vec3(lum(c)) * vec3(1.14, 1.0, 0.82);\n" +
        "        f = mix(c, f, 0.85);\n" +
        "    } else if (uFilter == 8) {\n" +
        "        f = vec3(c.r + 0.06, c.g * 0.97 + 0.01, c.b * 0.96 + 0.04);\n" +
        "        f = mix(vec3(lum(f)), f, 0.9);\n" +
        "        f = pow(clamp(f, 0.0, 1.0), vec3(0.92));\n" +
        "    } else if (uFilter == 9) {\n" +
        "        f = vec3(c.r * 1.07 + 0.03, c.g * 0.99, c.b * 0.95 + 0.02);\n" +
        "        f = mix(vec3(lum(f)), f, 1.05);\n" +
        "    } else if (uFilter == 10) {\n" +
        "        f = vec3(c.r * 0.92, c.g * 1.0, c.b * 1.12) * 1.03;\n" +
        "    } else if (uFilter == 11) {\n" +
        "        f = vec3(c.r * 0.92 + 0.01, c.g * 1.06 + 0.02, c.b * 0.94);\n" +
        "        f = mix(vec3(lum(f)), f, 0.95);\n" +
        "    }\n" +
        "    return mix(c, clamp(f, 0.0, 1.0), uFilterStrength);\n" +
        "}\n" +

        // dark-circle removal: soft ellipse right under each eye
        "float eyeMask(vec2 eye) {\n" +
        "    vec2 d = vUV - (eye - vec2(0.0, uEyeDist * 0.16));\n" +
        "    d.x *= uDispAspect;\n" +
        "    float r = uEyeDist * 0.34;\n" +
        "    float len = length(d / vec2(1.0, 1.35));\n" +
        "    return smoothstep(r, r * 0.25, len);\n" +
        "}\n" +

        "void main() {\n" +
        "    vec3 base = texture2D(sBase, vUV).rgb;\n" +
        "    vec3 q = texture2D(sQ, vUV).rgb;\n" +
        "    float skin = skinMask(base);\n" +

        // guided-smoothing mix with detail preservation
        "    float detail = length(base - q);\n" +
        "    float edge = smoothstep(0.02, 0.16, detail);\n" +
        "    float w = clamp(uSmooth, 0.0, 1.0) * (1.0 - edge) * mix(0.35, 1.0, skin);\n" +
        "    vec3 color = mix(base, q, w);\n" +

        // dark circles
        "    if (uEyeCircle > 0.001 && uEyeDist > 0.0) {\n" +
        "        float m = max(eyeMask(uEyeL), eyeMask(uEyeR));\n" +
        "        vec3 fixedC = q * 1.05 + 0.025;\n" +
        "        color = mix(color, fixedC, m * uEyeCircle * 0.85);\n" +
        "    }\n" +

        // contouring: nose side shadow + cheekbone highlight
        "    if (uContour > 0.001) {\n" +
        "        float eyeMidY = (uEyeL.y + uEyeR.y) * 0.5;\n" +
        "        float lineX = (uEyeL.x + uEyeR.x) * 0.5;\n" +
        "        float band = smoothstep(eyeMidY + uEyeDist * 0.35, eyeMidY + uEyeDist * 0.75, vUV.y)\n" +
        "                   * (1.0 - smoothstep(uNoseBase.y, uNoseBase.y - uEyeDist * 0.2, vUV.y));\n" +
        "        float dxn = abs(vUV.x - lineX) * uDispAspect;\n" +
        "        float shadow = smoothstep(uEyeDist * 0.22, 0.0, dxn) * band;\n" +
        "        color -= shadow * uContour * 0.085 * skin;\n" +
        "        vec2 dl = vUV - (uCheekL + vec2(0.0, uEyeDist * 0.22));\n" +
        "        vec2 dr = vUV - (uCheekR + vec2(0.0, uEyeDist * 0.22));\n" +
        "        dl.x *= uDispAspect;\n" +
        "        dr.x *= uDispAspect;\n" +
        "        float hl = smoothstep(uEyeDist * 0.55, uEyeDist * 0.12, length(dl))\n" +
        "                 + smoothstep(uEyeDist * 0.55, uEyeDist * 0.12, length(dr));\n" +
        "        color += min(hl, 1.0) * uContour * 0.045 * skin;\n" +
        "    }\n" +

        // skin tone shift toward cool/fair (YCbCr)
        "    if (uSkinTone > 0.001) {\n" +
        "        float y = lum(color);\n" +
        "        float cb = 0.5 - 0.169 * color.r - 0.331 * color.g + 0.5 * color.b;\n" +
        "        float cr = 0.5 + 0.5 * color.r - 0.419 * color.g - 0.081 * color.b;\n" +
        "        cb = mix(cb, 0.53, uSkinTone * skin * 0.45);\n" +
        "        cr = mix(cr, 0.44, uSkinTone * skin * 0.55);\n" +
        "        color = vec3(y + 1.402 * (cr - 0.5),\n" +
        "                     y - 0.344 * (cb - 0.5) - 0.714 * (cr - 0.5),\n" +
        "                     y + 1.772 * (cb - 0.5));\n" +
        "    }\n" +

        // sharpen
        "    vec3 nb2 = texture2D(sBase, vUV + vec2(uTexel.x, 0.0)).rgb\n" +
        "             + texture2D(sBase, vUV - vec2(uTexel.x, 0.0)).rgb\n" +
        "             + texture2D(sBase, vUV + vec2(0.0, uTexel.y)).rgb\n" +
        "             + texture2D(sBase, vUV - vec2(0.0, uTexel.y)).rgb;\n" +
        "    color += (base * 4.0 - nb2) * uSharpen * 0.35;\n" +

        // saturation
        "    float l2 = lum(color);\n" +
        "    color = mix(vec3(l2), color, 1.0 + uSaturate);\n" +

        // background blur (before filter so it is graded too)
        "    if (uBgMix > 0.001) {\n" +
        "        vec3 bg = texture2D(sBg, vUV).rgb;\n" +
        "        vec2 mUV = vUV;\n" +
        "        if (uMaskMirror > 0.5) mUV.x = 1.0 - mUV.x;\n" +
        "        float fg = uHasMask > 0.5 ? texture2D(sMask, mUV).r : 1.0;\n" +
        "        color = mix(color, bg, uBgMix * (1.0 - fg));\n" +
        "    }\n" +

        "    color = applyFilter(color);\n" +
        "    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);\n" +
        "}\n";

    // ---- GL objects ----
    private GlProgram oesProgram;
    private GlProgram copyProgram;
    private GlProgram blurProgram;
    private GlProgram lumaProgram;
    private GlProgram mulProgram;
    private GlProgram combineAProgram;
    private GlProgram combineBProgram;
    private GlProgram applyQProgram;
    private GlProgram finalProgram;
    private FloatBuffer quadPos;
    private FloatBuffer quadUv;
    private int oesTex = 0;
    private int maskTex = 0;
    private int maskW = 0;
    private int maskH = 0;
    private boolean maskReady = false;
    private Fbo fboFull;
    private Fbo fboRaw;
    private Fbo fboA;  // half: P -> q
    private Fbo fboB;  // half: meanP / scratch
    private Fbo fboC;  // half: I
    private Fbo fboD;  // half: meanI
    private Fbo fboE;  // half: scratch
    private Fbo fboF;  // half: IP / meanIP
    private Fbo fboG;  // half: II / b / meanB
    private Fbo fboH;  // half: a / meanA
    private Fbo fboI;  // half: bg blur

    private final float[] stMatrix = new float[16];
    private final float[] idMatrix = new float[16];

    private SurfaceTexture surfaceTexture;
    private int frameWidth = 0;
    private int frameHeight = 0;
    private int viewWidth = 0;
    private int viewHeight = 0;
    private boolean frontCamera = true;

    private final float[] face = new float[FACE_SIZE];
    private final float[] faceTarget = new float[FACE_SIZE];
    private float facePresence = 0f;

    public volatile boolean splitMode = false;
    /** additional orientation-compensation rotation: 0/90/180/270 */
    public volatile int rotationOverride = 0;
    private float rotationRad = 0f;

    private volatile int stLegacyWidth = 0;
    private volatile int stLegacyHeight = 0;

    private final BeautyParams params;
    private final SurfaceTextureReadyListener onSurfaceTextureReady;
    private GLTaskRunner glThreadTask;

    public CameraRenderer(BeautyParams params, SurfaceTextureReadyListener onSurfaceTextureReady) {
        this.params = params;
        this.onSurfaceTextureReady = onSurfaceTextureReady;
        Matrix.setIdentityM(idMatrix, 0);
        Arrays.fill(faceTarget, -1f);
        Arrays.fill(face, -1f);
    }

    public void setFaceData(float[] data) {
        synchronized (faceTarget) {
            System.arraycopy(data, 0, faceTarget, 0, FACE_SIZE);
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

    /** Upload a new segmentation mask (8-bit grayscale) on the GL thread. */
    public void setMask(final int w, final int h, final byte[] data) {
        if (glThreadTask == null) return;
        glThreadTask.post(new Runnable() {
            @Override
            public void run() {
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
                            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE,
                            ByteBuffer.wrap(new byte[w * h]));
                    maskW = w;
                    maskH = h;
                }
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h,
                        GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(data));
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
                maskReady = true;
            }
        });
    }

    /** Capture the current beauty-processed frame. Result bitmap delivered off the GL thread. */
    public void capture(final BitmapCallback callback) {
        final Fbo cap = fboCapture;
        final Fbo f = fboFull;
        final Fbo a = fboA;
        final Fbo i = fboI;
        if (cap == null || f == null || a == null || i == null || glThreadTask == null) {
            callback.onResult(null);
            return;
        }
        glThreadTask.post(new Runnable() {
            @Override
            public void run() {
                try {
                    drawFinalPass(cap, upWidth, upHeight, 1f, 1f, f.tex, a.tex, i.tex, true);
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
        lumaProgram = new GlProgram(VS, FS_LUMA);
        mulProgram = new GlProgram(VS, FS_MUL);
        combineAProgram = new GlProgram(VS, FS_COMBINE_A);
        combineBProgram = new GlProgram(VS, FS_COMBINE_B);
        applyQProgram = new GlProgram(VS, FS_APPLY_Q);
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
        // stMatrix already contains whatever rotation is needed to display the
        // buffer upright; rotationOverride adds an extra user/orientation rotation.
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
            if (hasFace) System.arraycopy(faceTarget, 0, face, 0, FACE_SIZE);
            if (facePresence < 0.02f && !hasFace) facePresence = 0f;
        }

        Fbo full = fboFull;
        if (full == null) return;

        bindFbo(full);
        GLES20.glViewport(0, 0, full.w, full.h);
        drawOesPass(full, true);

        Fbo raw = fboRaw;
        if (raw != null && splitMode) {
            bindFbo(raw);
            GLES20.glViewport(0, 0, raw.w, raw.h);
            drawOesPass(raw, false);
        }

        drawGuidedFilter();

        Fbo a = fboA;
        Fbo i = fboI;
        float cx = cropScaleX();
        float cy = cropScaleY();
        if (splitMode && raw != null) {
            int half = viewWidth / 2;
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
            GLES20.glScissor(0, 0, half, viewHeight);
            drawFinalPass(null, viewWidth, viewHeight, cx, cy, raw.tex, a.tex, i.tex, false);
            GLES20.glScissor(half, 0, viewWidth - half, viewHeight);
            drawFinalPass(null, viewWidth, viewHeight, cx, cy, full.tex, a.tex, i.tex, true);
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        } else {
            drawFinalPass(null, viewWidth, viewHeight, cx, cy, full.tex, a.tex, i.tex, true);
        }
    }

    // ---- guided filter chain (all at half res, result in fboA) ----
    private void drawGuidedFilter() {
        Fbo full = fboFull;
        Fbo A = fboA, B = fboB, C = fboC, D = fboD, E = fboE, F = fboF, G = fboG, H = fboH, I = fboI;
        if (full == null || A == null || I == null) return;

        blurTo(full.tex, A);                       // A = P (downsample)
        oneTex(lumaProgram, A.tex, C);             // C = I (luma)
        twoTex(mulProgram, C.tex, A.tex, F);       // F = I*P

        blurTo(C.tex, D);                          // D = meanI
        blurTo(F.tex, F);                          // F = meanIP (ping-pong internal)
        blurTo(A.tex, B);                          // B = meanP
        twoTex(mulProgram, C.tex, C.tex, G);       // G = I*I
        blurTo(G.tex, G);                          // G = meanII

        fourTex(combineAProgram, D.tex, G.tex, F.tex, B.tex, 0.008f, H);  // H = a
        threeTex(combineBProgram, H.tex, D.tex, B.tex, G);                // G = b

        blurTo(H.tex, H);                          // H = meanA
        blurTo(G.tex, G);                          // G = meanB

        threeTex(applyQProgram, H.tex, C.tex, G.tex, A);                  // A = q

        if (params.bgBlur > 0.001f) {
            blurTo(A.tex, I);                      // I = bg blur pass 1
            blurTo(I.tex, I);                      // pass 2
        }
    }

    /** separable gaussian: read src, write dst (internal ping-pong via fboE) */
    private void blurTo(int srcTex, Fbo dst) {
        GlProgram p = blurProgram;
        Fbo E = fboE;
        if (p == null || E == null || dst == null) return;
        p.use();
        setupQuad(p);
        p.setMat4("uSTMatrix", idMatrix);
        p.setInt("sTexture", 0);
        p.setVec2("uCrop", 1f, 1f);
        p.setFloat("uMirror", 0f);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        bindFbo(E);
        GLES20.glViewport(0, 0, E.w, E.h);
        p.setVec2("uTexel", 1f / dst.w, 0f);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex);
        drawQuad();
        bindFbo(dst);
        GLES20.glViewport(0, 0, dst.w, dst.h);
        p.setVec2("uTexel", 0f, 1f / dst.h);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, E.tex);
        drawQuad();
    }

    private void oneTex(GlProgram p, int src, Fbo dst) {
        if (p == null || dst == null) return;
        p.use();
        setupQuad(p);
        p.setMat4("uSTMatrix", idMatrix);
        p.setVec2("uCrop", 1f, 1f);
        p.setFloat("uMirror", 0f);
        p.setInt("sTexture", 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, src);
        bindFbo(dst);
        GLES20.glViewport(0, 0, dst.w, dst.h);
        drawQuad();
    }

    private void twoTex(GlProgram p, int t1, int t2, Fbo dst) {
        if (p == null || dst == null) return;
        p.use();
        setupQuad(p);
        p.setMat4("uSTMatrix", idMatrix);
        p.setVec2("uCrop", 1f, 1f);
        p.setFloat("uMirror", 0f);
        p.setInt("sTex1", 0);
        p.setInt("sTex2", 1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t2);
        bindFbo(dst);
        GLES20.glViewport(0, 0, dst.w, dst.h);
        drawQuad();
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }

    private void threeTex(GlProgram p, int t1, int t2, int t3, Fbo dst) {
        if (p == null || dst == null) return;
        p.use();
        setupQuad(p);
        p.setMat4("uSTMatrix", idMatrix);
        p.setVec2("uCrop", 1f, 1f);
        p.setFloat("uMirror", 0f);
        p.setInt("sA", 0);
        p.setInt("sB", 1);
        p.setInt("sI", 2);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t2);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t3);
        bindFbo(dst);
        GLES20.glViewport(0, 0, dst.w, dst.h);
        drawQuad();
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }

    private void fourTex(GlProgram p, int t1, int t2, int t3, int t4, float eps, Fbo dst) {
        if (p == null || dst == null) return;
        p.use();
        setupQuad(p);
        p.setMat4("uSTMatrix", idMatrix);
        p.setVec2("uCrop", 1f, 1f);
        p.setFloat("uMirror", 0f);
        p.setInt("sI", 0);
        p.setInt("sII", 1);
        p.setInt("sIP", 2);
        p.setInt("sP", 3);
        p.setFloat("uEps", eps);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t2);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t3);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t4);
        bindFbo(dst);
        GLES20.glViewport(0, 0, dst.w, dst.h);
        drawQuad();
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
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
                               int baseTex, int qTex, int bgTex, boolean beauty) {
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
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, baseTex);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, qTex);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgTex);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex);
        p.setInt("sBase", 0);
        p.setInt("sQ", 1);
        p.setInt("sBg", 2);
        p.setInt("sMask", 3);
        p.setVec2("uTexel", 1f / f.w, 1f / f.h);
        float s = facePresence;
        float[] fdat = face;
        if (beauty) {
            p.setFloat("uSmooth", params.smooth);
            p.setFloat("uWhiten", params.whiten);
            p.setFloat("uRuddy", params.ruddy);
            p.setFloat("uSharpen", params.sharpen);
            p.setFloat("uSaturate", params.saturate);
            p.setFloat("uSkinTone", params.skinTone);
            p.setFloat("uEyeCircle", params.eyeCircle * s);
            p.setFloat("uContour", params.contouring * s);
            p.setVec2("uEyeL", fdat[FD_EYE_L], fdat[FD_EYE_L + 1]);
            p.setVec2("uEyeR", fdat[FD_EYE_R], fdat[FD_EYE_R + 1]);
            float edx = fdat[FD_EYE_R] - fdat[FD_EYE_L];
            float edy = fdat[FD_EYE_R + 1] - fdat[FD_EYE_L + 1];
            p.setFloat("uEyeDist", (float) Math.sqrt(edx * edx + edy * edy) * s);
            p.setVec2("uNoseBase", fdat[FD_NOSE], fdat[FD_NOSE + 1]);
            p.setVec2("uCheekL", fdat[FD_CHEEK_L], fdat[FD_CHEEK_L + 1]);
            p.setVec2("uCheekR", fdat[FD_CHEEK_R], fdat[FD_CHEEK_R + 1]);
            p.setFloat("uBgMix", params.bgBlur);
        } else {
            p.setFloat("uSmooth", 0f);
            p.setFloat("uWhiten", 0f);
            p.setFloat("uRuddy", 0f);
            p.setFloat("uSharpen", 0f);
            p.setFloat("uSaturate", 0f);
            p.setFloat("uSkinTone", 0f);
            p.setFloat("uEyeCircle", 0f);
            p.setFloat("uContour", 0f);
            p.setVec2("uEyeL", 0f, 0f);
            p.setVec2("uEyeR", 0f, 0f);
            p.setFloat("uEyeDist", 0f);
            p.setVec2("uNoseBase", 0f, 0f);
            p.setVec2("uCheekL", 0f, 0f);
            p.setVec2("uCheekR", 0f, 0f);
            p.setFloat("uBgMix", 0f);
        }
        float viewA = viewHeight == 0 ? 1f : viewWidth / (float) viewHeight;
        float texA = upWidth / (float) upHeight;
        p.setFloat("uDispAspect", texA / viewA);
        p.setFloat("uHasMask", maskReady ? 1f : 0f);
        p.setFloat("uMaskMirror", frontCamera ? 1f : 0f);
        p.setInt("uFilter", beauty ? params.filterIndex : 0);
        p.setFloat("uFilterStrength", beauty ? params.filterStrength : 0f);
        drawQuad();
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }

    // ---- helpers ----

    private int upWidth = 0;
    private int upHeight = 0;
    private Fbo fboCapture;

    private void ensureFbos() {
        Fbo f = fboFull;
        if (f == null || f.w != upWidth || f.h != upHeight) {
            releaseFbo(fboFull); releaseFbo(fboRaw);
            releaseFbo(fboA); releaseFbo(fboB); releaseFbo(fboC); releaseFbo(fboD);
            releaseFbo(fboE); releaseFbo(fboF); releaseFbo(fboG); releaseFbo(fboH); releaseFbo(fboI);
            releaseFbo(fboCapture);
            fboFull = new Fbo(upWidth, upHeight);
            fboRaw = new Fbo(upWidth, upHeight);
            fboCapture = new Fbo(upWidth, upHeight);
            int hw = Math.max(upWidth / 2, 2);
            int hh = Math.max(upHeight / 2, 2);
            fboA = new Fbo(hw, hh); fboB = new Fbo(hw, hh); fboC = new Fbo(hw, hh);
            fboD = new Fbo(hw, hh); fboE = new Fbo(hw, hh); fboF = new Fbo(hw, hh);
            fboG = new Fbo(hw, hh); fboH = new Fbo(hw, hh); fboI = new Fbo(hw, hh);
        }
    }

    private static void releaseFbo(Fbo f) {
        if (f != null) f.release();
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

    private void buildWarps(float[] cr, float[] dd, float[] tp) {
        float s = facePresence;
        for (int i = 0; i < MAX_WARPS; i++) put(cr, dd, tp, i, 0.5f, 0.5f, 0.01f, 0f, 0f, true);
        if (s <= 0.01f) {
            Arrays.fill(dd, 0f);
            return;
        }
        // Warp landmarks live in the un-compensated display space; rotate centers
        // and pull directions by the orientation-compensation rotation so they land
        // on the rotated preview correctly.
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
        float ox = c[0] - cl[0];
        float oy = c[1] - cl[1];
        float len = Math.max(1e-4f, (float) Math.sqrt(ox * ox + oy * oy));
        put(cr, dd, tp, W_CHEEK_L, cl[0], cl[1], cheekR, ox / len * pull, oy / len * pull, false);
        ox = c[0] - crp[0];
        oy = c[1] - crp[1];
        len = Math.max(1e-4f, (float) Math.sqrt(ox * ox + oy * oy));
        put(cr, dd, tp, W_CHEEK_R, crp[0], crp[1], cheekR, ox / len * pull, oy / len * pull, false);

        put(cr, dd, tp, W_CHIN, ch[0], ch[1], fw * 0.35f,
                up[0] * params.chinSlim * fw * 0.18f * s,
                up[1] * params.chinSlim * fw * 0.18f * s, false);

        // nose slim: two pull points on nose sides toward the nose line
        if (f[FD_NOSE] >= 0 && params.noseSlim > 0.001f) {
            float[] nb = rotUv(f[FD_NOSE], f[FD_NOSE + 1], deg);
            float ex = (le[0] + re[0]) * 0.5f;
            float ey = (le[1] + re[1]) * 0.5f;
            float ax = nb[0] - ex;
            float ay = nb[1] - ey;
            float al = Math.max(1e-4f, (float) Math.sqrt(ax * ax + ay * ay));
            float px = ay / al;   // perpendicular
            float py = -ax / al;
            float nw = fw * 0.13f;
            float npull = params.noseSlim * fw * 0.16f * s;
            put(cr, dd, tp, W_NOSE_L, nb[0] - px * nw, nb[1] - py * nw, fw * 0.20f,
                    px * npull, py * npull, false);
            put(cr, dd, tp, W_NOSE_R, nb[0] + px * nw, nb[1] + py * nw, fw * 0.20f,
                    -px * npull, -py * npull, false);
        }

        // smile: lift mouth corners
        if (f[FD_MOUTH_L] >= 0 && f[FD_MOUTH_R] >= 0 && params.smileLift > 0.001f) {
            float[] ml = rotUv(f[FD_MOUTH_L], f[FD_MOUTH_L + 1], deg);
            float[] mr = rotUv(f[FD_MOUTH_R], f[FD_MOUTH_R + 1], deg);
            float lift = params.smileLift * fw * 0.12f * s;
            put(cr, dd, tp, W_MOUTH_L, ml[0], ml[1], fw * 0.18f, up[0] * lift, up[1] * lift, false);
            put(cr, dd, tp, W_MOUTH_R, mr[0], mr[1], fw * 0.18f, up[0] * lift, up[1] * lift, false);
        }

        // forehead: push hairline band up
        if (f[FD_BROW] >= 0 && params.forehead > 0.001f) {
            float[] bm = rotUv(f[FD_BROW], f[FD_BROW + 1], deg);
            float[] fh = rotUv(f[FD_FOREHEAD], f[FD_FOREHEAD + 1], deg);
            float mx = (bm[0] + fh[0]) * 0.5f;
            float my = (bm[1] + fh[1]) * 0.5f;
            float br = (float) Math.sqrt((fh[0] - bm[0]) * (fh[0] - bm[0])
                    + (fh[1] - bm[1]) * (fh[1] - bm[1]));
            float lift = params.forehead * fw * 0.10f * s;
            put(cr, dd, tp, W_FOREHEAD, mx, my, Math.max(br * 0.9f, 0.01f),
                    up[0] * lift, up[1] * lift, false);
        }
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
