package com.example.beautycamera;

import android.graphics.PointF;

import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceContour;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.face.FaceLandmark;

import java.util.List;

/**
 * ML Kit 人脸关键点提取器。
 *
 * <p>每帧把相机分析流送入 ML Kit（轮廓模式），把检测结果转成 {@link FaceData}：
 * 显示空间 uv 坐标（前置摄像头已镜像，与预览画面直接对应），整块数组交给渲染器。
 * 检测失败/无人脸时发布 NO_FACE，渲染端据此关闭关键点驱动的局部效果。</p>
 */
public class FaceTracker implements ImageAnalysis.Analyzer {

    private static final float MIN_EYE_DIST = 0.02f;
    private static final float MAX_EYE_DIST = 0.35f;
    private static final float MIN_FACE_SIZE_RATIO = 0.08f;
    private static final long ANALYZE_INTERVAL_MS = 40;   // 约 25fps，够用且省电

    public interface FrontCameraProvider {
        boolean isFront();
    }

    private static final FaceData NO_FACE = new FaceData();

    static {
        NO_FACE.clear();
    }

    private final FaceDetector detector;
    private final FrontCameraProvider frontCamera;
    private final CameraRenderer renderer;
    private final FaceData out = new FaceData();
    private long lastRunMs = 0L;
    private long lastLogMs = 0L;

    /** debug counters, shown on screen by MainActivity */
    public static volatile int analyzeCalls = 0;
    public static volatile int lastFaces = -1;
    public static volatile float lastEulerX = 0f;
    public static volatile String lastError = "";

    public FaceTracker(FrontCameraProvider frontCamera, CameraRenderer renderer) {
        this.frontCamera = frontCamera;
        this.renderer = renderer;
        this.detector = FaceDetection.getClient(new FaceDetectorOptions.Builder()
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(MIN_FACE_SIZE_RATIO)
                .enableTracking()
                .build());
    }

    @Override
    public void analyze(ImageProxy image) {
        analyzeCalls++;
        long now = System.currentTimeMillis();
        if (now - lastRunMs < ANALYZE_INTERVAL_MS || image.getImage() == null) {
            image.close();
            return;
        }
        lastRunMs = now;
        if (now - lastLogMs > 2000) {
            lastLogMs = now;
            android.util.Log.d("FaceTracker", "size=" + image.getWidth() + "x" + image.getHeight()
                    + " rot=" + image.getImageInfo().getRotationDegrees());
        }
        final int w = image.getWidth();
        final int h = image.getHeight();
        InputImage input = InputImage.fromMediaImage(image.getImage(),
                image.getImageInfo().getRotationDegrees());
        final ImageProxy img = image;
        detector.process(input)
                .addOnSuccessListener(new com.google.android.gms.tasks.OnSuccessListener<java.util.List<Face>>() {
                    @Override
                    public void onSuccess(java.util.List<Face> faces) {
                        lastFaces = faces.size();
                        if (!faces.isEmpty()) {
                            lastEulerX = faces.get(0).getHeadEulerAngleX();
                        }
                        renderer.setFaceData(faces.isEmpty()
                                ? NO_FACE : buildData(faces.get(0), w, h));
                    }
                })
                .addOnFailureListener(new com.google.android.gms.tasks.OnFailureListener() {
                    @Override
                    public void onFailure(Exception e) {
                        lastError = String.valueOf(e.getMessage());
                        renderer.setFaceData(NO_FACE);
                    }
                })
                .addOnCompleteListener(new com.google.android.gms.tasks.OnCompleteListener<java.util.List<Face>>() {
                    @Override
                    public void onComplete(com.google.android.gms.tasks.Task<java.util.List<Face>> task) {
                        img.close();
                    }
                });
    }

    private FaceData buildData(Face face, int w, int h) {
        List<PointF> oval = points(face.getContour(FaceContour.FACE));
        float[] le = centroid(face.getContour(FaceContour.LEFT_EYE));
        float[] re = centroid(face.getContour(FaceContour.RIGHT_EYE));
        if (oval == null || le == null || re == null) return NO_FACE;

        // 头部相对传感器倒置/过度倾斜时，关键点驱动的变形会落错位置，
        // 此时禁用变形（磨皮等全局效果不受影响）。
        float eyeDist = dist(le, re);
        if (eyeDist < MIN_EYE_DIST || eyeDist > MAX_EYE_DIST) return NO_FACE;

        boolean mirror = frontCamera.isFront();

        // 遍历脸轮廓：取左右极值（脸颊）、上下极值（下巴/发际）与质心（脸中心）
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float sumX = 0f, sumY = 0f, topX = 0f;
        for (PointF p : oval) {
            if (p.x < minX) minX = p.x;
            if (p.x > maxX) maxX = p.x;
            if (p.y < minY) { minY = p.y; topX = p.x; }
            if (p.y > maxY) maxY = p.y;
            sumX += p.x;
            sumY += p.y;
        }
        float midY = sumY / oval.size();

        float[] leUv = uv(le[0], le[1], w, h, mirror);
        float[] reUv = uv(re[0], re[1], w, h, mirror);
        float[] center = uv(sumX / oval.size(), sumY / oval.size(), w, h, mirror);
        float[] nose = landmark(face, FaceLandmark.NOSE_BASE, w, h, mirror);
        float[] mouthL = landmark(face, FaceLandmark.MOUTH_LEFT, w, h, mirror);
        float[] mouthR = landmark(face, FaceLandmark.MOUTH_RIGHT, w, h, mirror);
        float[] browL = centroid(face.getContour(FaceContour.LEFT_EYEBROW_TOP));
        float[] browR = centroid(face.getContour(FaceContour.RIGHT_EYEBROW_TOP));
        float[] brow = browL != null && browR != null
                ? uv((browL[0] + browR[0]) / 2f, (browL[1] + browR[1]) / 2f, w, h, mirror) : null;

        out.set(FaceData.EYE_L, leUv[0], leUv[1]);
        out.set(FaceData.EYE_R, reUv[0], reUv[1]);
        out.set(FaceData.CENTER, center[0], center[1]);
        out.set(FaceData.FACE_W, (maxX - minX) / (float) h, 0f);
        out.set(FaceData.CHEEK_L, uv(minX, midY, w, h, mirror)[0], 1f - midY / (float) h);
        out.set(FaceData.CHEEK_R, uv(maxX, midY, w, h, mirror)[0], 1f - midY / (float) h);
        out.set(FaceData.CHIN, center[0], 1f - maxY / (float) h);
        if (nose != null) out.set(FaceData.NOSE, nose[0], nose[1]);
        if (mouthL != null) out.set(FaceData.MOUTH_L, mouthL[0], mouthL[1]);
        if (mouthR != null) out.set(FaceData.MOUTH_R, mouthR[0], mouthR[1]);
        if (brow != null) out.set(FaceData.BROW, brow[0], brow[1]);
        out.set(FaceData.FOREHEAD, uv(topX, minY, w, h, mirror)[0], 1f - minY / (float) h);
        return out;
    }

    /** 取一个 landmark 并转到显示空间 uv；不存在时返回 null。 */
    private static float[] landmark(Face face, int type, int w, int h, boolean mirror) {
        FaceLandmark lm = face.getLandmark(type);
        return lm == null ? null : uv(lm.getPosition().x, lm.getPosition().y, w, h, mirror);
    }

    private static List<PointF> points(FaceContour contour) {
        return contour == null ? null : contour.getPoints();
    }

    /** 传感器像素坐标 → 显示空间 uv（u 按前摄镜像，v 翻转为向上）。 */

    private static float[] uv(float px, float py, int w, int h, boolean mirror) {
        float u = px / (float) w;
        if (mirror) u = 1f - u;
        return new float[]{u, 1f - py / (float) h};
    }

    private static float[] centroid(FaceContour contour) {
        List<PointF> pts = points(contour);
        if (pts == null || pts.isEmpty()) return null;
        float x = 0f, y = 0f;
        for (PointF p : pts) {
            x += p.x;
            y += p.y;
        }
        return new float[]{x / pts.size(), y / pts.size()};
    }

    private static float dist(float[] a, float[] b) {
        float dx = a[0] - b[0];
        float dy = a[1] - b[1];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
