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

import java.util.Arrays;
import java.util.List;

/**
 * ML Kit contour-based face tracker. Publishes normalized (display space, uv)
 * geometry into a float array (all -1 when no face) — slot layout matches the
 * FD_* constants in {@link CameraRenderer}:
 *   0/1 left eye, 2/3 right eye, 4/5 center, 6 face width (by frame height),
 *   7/8 left cheek, 9/10 right cheek, 11/12 chin, 13/14 nose base,
 *   15/16 mouth L, 17/18 mouth R, 19/20 brow middle, 21/22 forehead top.
 * Coordinates are mirrored for the front camera to match the rendered preview.
 */
public class FaceTracker implements ImageAnalysis.Analyzer {

    public static final int FACE_DATA_SIZE = 24;

    private static final float MIN_EYE_DIST = 0.02f;
    private static final float MAX_EYE_DIST = 0.35f;
    private static final float MIN_FACE_SIZE_RATIO = 0.08f;
    private static final long ANALYZE_INTERVAL_MS = 40;   // ~25fps

    public interface FrontCameraProvider {
        boolean isFront();
    }

    private static final float[] NO_FACE = new float[FACE_DATA_SIZE];

    static {
        Arrays.fill(NO_FACE, -1f);
    }

    private final FaceDetector detector;
    private final FrontCameraProvider frontCamera;
    private final CameraRenderer renderer;
    private final float[] out = new float[FACE_DATA_SIZE];
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

    private float[] buildData(Face face, int w, int h) {
        List<PointF> oval = points(face.getContour(FaceContour.FACE));
        float[] le = centroid(face.getContour(FaceContour.LEFT_EYE));
        float[] re = centroid(face.getContour(FaceContour.RIGHT_EYE));
        if (oval == null || le == null || re == null) return NO_FACE;

        // When the head is tilted/inverted relative to the sensor, warp landmarks
        // would land on wrong spots; disable reshaping then (global effects stay).
        float eyeDist = dist(le, re);
        if (eyeDist < MIN_EYE_DIST || eyeDist > MAX_EYE_DIST) return NO_FACE;

        boolean mirror = frontCamera.isFront();

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

        out[0] = leUv[0];  out[1] = leUv[1];
        out[2] = reUv[0];  out[3] = reUv[1];
        out[4] = center[0]; out[5] = center[1];
        out[6] = (maxX - minX) / (float) h;
        out[7] = uv(minX, midY, w, h, mirror)[0];
        out[8] = 1f - midY / (float) h;
        out[9] = uv(maxX, midY, w, h, mirror)[0];
        out[10] = 1f - midY / (float) h;
        out[11] = center[0];
        out[12] = 1f - maxY / (float) h;
        out[13] = nose == null ? -1f : nose[0];
        out[14] = nose == null ? -1f : nose[1];
        out[15] = mouthL == null ? -1f : mouthL[0];
        out[16] = mouthL == null ? -1f : mouthL[1];
        out[17] = mouthR == null ? -1f : mouthR[0];
        out[18] = mouthR == null ? -1f : mouthR[1];
        out[19] = brow == null ? -1f : brow[0];
        out[20] = brow == null ? -1f : brow[1];
        out[21] = uv(topX, minY, w, h, mirror)[0];
        out[22] = 1f - minY / (float) h;
        return out;
    }

    private static float[] landmark(Face face, int type, int w, int h, boolean mirror) {
        FaceLandmark lm = face.getLandmark(type);
        return lm == null ? null : uv(lm.getPosition().x, lm.getPosition().y, w, h, mirror);
    }

    private static List<PointF> points(FaceContour contour) {
        return contour == null ? null : contour.getPoints();
    }

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
