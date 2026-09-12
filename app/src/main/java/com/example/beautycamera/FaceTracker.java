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

import java.util.List;

/**
 * ML Kit contour-based face tracker. Publishes normalized (display space, uv) geometry:
 * [0..1] = left eye xy, [2..3] right eye xy, [4..5] face center xy, [6] face width
 * (normalized by frame height), [7..8] left cheek, [9..10] right cheek, [11..12] chin.
 * All -1 when no face. Mirrored for front camera so coords match the rendered preview.
 */
public class FaceTracker implements ImageAnalysis.Analyzer {

    public interface FrontCameraProvider {
        boolean isFront();
    }

    private final FaceDetector detector;
    private final FrontCameraProvider frontCamera;
    private final CameraRenderer renderer;
    private final float[] out = new float[14];
    private long lastRunMs = 0L;
    private long lastRollLogMs = 0L;

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
                .setMinFaceSize(0.08f)
                .enableTracking()
                .build());
    }

    @Override
    public void analyze(ImageProxy image) {
        analyzeCalls++;
        long now = System.currentTimeMillis();
        if (now - lastRunMs < 40) { // ~25fps max
            image.close();
            return;
        }
        lastRunMs = now;
        long now2 = System.currentTimeMillis();
        if (now2 - lastRollLogMs > 2000) {
            lastRollLogMs = now2;
            android.util.Log.d("FaceTracker", "analyze called, size="
                    + image.getWidth() + "x" + image.getHeight()
                    + " rot=" + image.getImageInfo().getRotationDegrees());
        }
        if (image.getImage() == null) {
            image.close();
            return;
        }
        final int rotation = image.getImageInfo().getRotationDegrees();
        final int w = image.getWidth();
        final int h = image.getHeight();
        InputImage input = InputImage.fromMediaImage(image.getImage(), rotation);
        final ImageProxy img = image;
        detector.process(input)
                .addOnSuccessListener(new com.google.android.gms.tasks.OnSuccessListener<java.util.List<Face>>() {
                    @Override
                    public void onSuccess(java.util.List<Face> faces) {
                        lastFaces = faces.size();
                        if (!faces.isEmpty()) {
                            lastEulerX = faces.get(0).getHeadEulerAngleX();
                        }
                        float[] data = faces.isEmpty() ? noFace() : buildData(faces.get(0), w, h);
                        renderer.setFaceData(data);
                    }
                })
                .addOnFailureListener(new com.google.android.gms.tasks.OnFailureListener() {
                    @Override
                    public void onFailure(Exception e) {
                        lastError = String.valueOf(e.getMessage());
                        renderer.setFaceData(noFace());
                    }
                })
                .addOnCompleteListener(new com.google.android.gms.tasks.OnCompleteListener<java.util.List<Face>>() {
                    @Override
                    public void onComplete(com.google.android.gms.tasks.Task<java.util.List<Face>> task) {
                        img.close();
                    }
                });
    }

    private static float[] noFace() {
        return new float[]{-1f, -1f, -1f, -1f, -1f, -1f, -1f, -1f, -1f, -1f, -1f, -1f, -1f, -1f};
    }

    private float[] buildData(Face face, int w, int h) {
        FaceContour ovalContour = face.getContour(FaceContour.FACE);
        List<PointF> oval = ovalContour != null ? ovalContour.getPoints() : null;
        float[] le = centroid(face.getContour(FaceContour.LEFT_EYE));
        float[] re = centroid(face.getContour(FaceContour.RIGHT_EYE));
        if (oval == null || oval.isEmpty() || le == null || re == null) return noFace();

        if (oval == null || oval.isEmpty() || le == null || re == null) return noFace();

        // Geometry sanity check: when the head is tilted / inverted relative to the
        // sensor (extreme pose), landmark-based warps would land on the wrong spots
        // and produce grotesque artifacts. Disable reshaping in that case; global
        // effects (smoothing etc.) stay active.
        float dx = re[0] - le[0];
        float dy = re[1] - le[1];
        float eyeDist = (float) Math.sqrt(dx * dx + dy * dy);
        if (eyeDist < 0.02f || eyeDist > 0.35f) return noFace();     // implausible eye spacing
        if (Math.abs(dy) > 0.12f * eyeDist / 0.1f) return noFace();  // heavily rolled head
        if (le[0] < 0.02f || le[0] > 0.98f || le[1] < 0.02f || le[1] > 0.98f) return noFace();
        if (re[0] < 0.02f || re[0] > 0.98f || re[1] < 0.02f || re[1] > 0.98f) return noFace();

        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float sumX = 0f, sumY = 0f;
        for (PointF p : oval) {
            if (p.x < minX) minX = p.x;
            if (p.x > maxX) maxX = p.x;
            if (p.y > maxY) maxY = p.y;
            sumX += p.x;
            sumY += p.y;
        }
        float midY = sumY / oval.size();
        float faceW = (maxX - minX) / (float) h;

        boolean mirror = frontCamera.isFront();

        float[] leUv = uv(le[0], le[1], w, h, mirror);
        float[] reUv = uv(re[0], re[1], w, h, mirror);
        float[] c = uv(sumX / oval.size(), sumY / oval.size(), w, h, mirror);
        float[] cl = uv(minX, midY, w, h, mirror);
        float[] cr = uv(maxX, midY, w, h, mirror);
        float[] ch = uv(sumX / oval.size(), maxY, w, h, mirror);

        out[0] = leUv[0]; out[1] = leUv[1];
        out[2] = reUv[0]; out[3] = reUv[1];
        out[4] = c[0];    out[5] = c[1];
        out[6] = faceW;
        out[7] = cl[0];   out[8] = cl[1];
        out[9] = cr[0];   out[10] = cr[1];
        out[11] = ch[0];  out[12] = ch[1];
        return out;
    }

    private static float[] uv(float px, float py, int w, int h, boolean mirror) {
        float u = px / (float) w;
        if (mirror) u = 1f - u;
        return new float[]{u, 1f - py / (float) h};
    }

    private static float[] centroid(FaceContour contour) {
        List<PointF> pts = contour != null ? contour.getPoints() : null;
        if (pts == null || pts.isEmpty()) return null;
        float x = 0f, y = 0f;
        for (PointF p : pts) {
            x += p.x;
            y += p.y;
        }
        return new float[]{x / pts.size(), y / pts.size()};
    }
}
