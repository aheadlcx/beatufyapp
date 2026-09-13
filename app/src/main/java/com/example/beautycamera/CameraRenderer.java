package com.example.beautycamera;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 相机预览渲染管线（GLES2）。
 *
 * <p>数据流：
 * <pre>
 * CameraX Preview → SurfaceTexture(OES 外部纹理)
 *   ├─ pass1  把相机帧画进 full FBO，同时按人脸关键点做美型变形（瘦脸/大眼…）
 *   ├─ pass2  GuidedFilter：半分辨率引导滤波磨皮 → q（+ 可选的背景虚化模糊图）
 *   └─ pass3  最终合成到屏幕/拍照：磨皮混合、祛黑眼圈、修容、肤色、
 *             锐化、饱和度、背景虚化、滤镜
 * </pre>
 * 线程模型：GLSurfaceView 的 GL 线程跑 onDrawFrame；FaceTracker / SegmentationAnalyzer
 * 在 CameraX 分析线程产出人脸关键点与分割 mask，经 volatile 字段/queueEvent 交接；
 * 拍照请求从任意线程投递到 GL 线程执行后回调 Bitmap。</p>
 */
public class CameraRenderer implements GLSurfaceView.Renderer {

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
    private final FaceWarpBuilder warpBuilder;

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

    private final FaceData face = new FaceData();
    private final FaceData faceTarget = new FaceData();
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
        this.warpBuilder = new FaceWarpBuilder(params);
        faceTarget.clear();
        face.clear();
    }

    /** 由 FaceTracker 在分析线程调用；整块数组拷贝，无锁竞争。 */
    public void setFaceData(FaceData src) {
        synchronized (faceTarget) {
            System.arraycopy(src.raw(), 0, faceTarget.raw(), 0, FaceData.SIZE);
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
        float[] cr = new float[FaceWarpBuilder.WARP_COUNT * 3];
        float[] dd = new float[FaceWarpBuilder.WARP_COUNT * 2];
        float[] tp = new float[FaceWarpBuilder.WARP_COUNT];
        warpBuilder.build(face, facePresence, rotationOverride, cr, dd, tp);
        p.setVec3Array("uWarpCR", cr, FaceWarpBuilder.WARP_COUNT);
        p.setVec2Array("uWarpD", dd, FaceWarpBuilder.WARP_COUNT);
        for (int i = 0; i < FaceWarpBuilder.WARP_COUNT; i++) {
            GLES20.glUniform1f(p.uniform("uWarpType[" + i + "]"), tp[i]);
        }
        p.setInt("uWarpCount", FaceWarpBuilder.WARP_COUNT);
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
        // k：非美颜（对比模式左半屏）时全部系数置 0；s：叠加人脸渐入系数
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

        // 关键点驱动的局部效果：作用半径都由"眼距"推导，随脸大小自适应
        float eyeDist = face.eyeDist() * s;
        p.setFloat("uEyeDist", eyeDist);
        p.setVec2("uEyeL", face.x(FaceData.EYE_L), face.y(FaceData.EYE_L));
        p.setVec2("uEyeR", face.x(FaceData.EYE_R), face.y(FaceData.EYE_R));
        p.setVec2("uNoseBase", face.x(FaceData.NOSE), face.y(FaceData.NOSE));
        p.setVec2("uCheekL", face.x(FaceData.CHEEK_L), face.y(FaceData.CHEEK_L));
        p.setVec2("uCheekR", face.x(FaceData.CHEEK_R), face.y(FaceData.CHEEK_R));
    }

    // ---- 小工具 ----

    /** 归一化到 0..359。 */
    private static int normDeg(int deg) {
        int d = deg % 360;
        return d < 0 ? d + 360 : d;
    }

    // ---- 帧资源管理 ----

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
            boolean hasFace = faceTarget.hasFace();
            float goal = hasFace ? 1f : 0f;
            facePresence += (goal - facePresence) * 0.25f;
            if (hasFace) System.arraycopy(faceTarget.raw(), 0, face.raw(), 0, FaceData.SIZE);
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
