package com.example.beautycamera;

import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.Segmentation;
import com.google.mlkit.vision.segmentation.SegmentationMask;
import com.google.mlkit.vision.segmentation.Segmenter;
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * 人像分割分析器：把相机帧送入 ML Kit 自拍分割模型，产出 8-bit 人像 mask
 * （0=背景，255=人），交给渲染器上传为 GL 纹理，用于背景虚化。
 * 限制在约 8fps 且带 busy 标志防积压——mask 不需要满帧率。
 */
public class SegmentationAnalyzer implements ImageAnalysis.Analyzer {

    public interface MaskSink {
        void setMask(int w, int h, byte[] data);
    }

    private final Segmenter segmenter;
    private final MaskSink sink;
    private long lastRunMs = 0L;
    private volatile boolean busy = false;

    public SegmentationAnalyzer(MaskSink sink) {
        this.sink = sink;
        this.segmenter = Segmentation.getClient(
                new SelfieSegmenterOptions.Builder()
                        .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                        .build());
    }

    @Override
    public void analyze(ImageProxy image) {
        long now = System.currentTimeMillis();
        if (busy || now - lastRunMs < 120) { // ~8fps
            image.close();
            return;
        }
        lastRunMs = now;
        if (image.getImage() == null) {
            image.close();
            return;
        }
        busy = true;
        final int rotation = image.getImageInfo().getRotationDegrees();
        InputImage input = InputImage.fromMediaImage(image.getImage(), rotation);
        final ImageProxy img = image;
        segmenter.process(input)
                .addOnSuccessListener(new com.google.android.gms.tasks.OnSuccessListener<SegmentationMask>() {
                    @Override
                    public void onSuccess(SegmentationMask mask) {
                        try {
                            int w = mask.getWidth();
                            int h = mask.getHeight();
                            ByteBuffer buf = mask.getBuffer();
                            buf.rewind();
                            byte[] out = new byte[w * h];
                            if (buf.remaining() >= w * h * 4) {
                                FloatBuffer fb = buf.asFloatBuffer();
                                for (int i = 0; i < w * h; i++) {
                                    float v = fb.get(i);
                                    out[i] = (byte) (Math.max(0f, Math.min(1f, v)) * 255f);
                                }
                            } else if (buf.remaining() >= w * h) {
                                for (int i = 0; i < w * h; i++) {
                                    out[i] = buf.get();
                                }
                            }
                            sink.setMask(w, h, out);
                        } catch (Throwable t) {
                            FaceTracker.lastError = "seg:" + t.getMessage();
                        }
                    }
                })
                .addOnFailureListener(new com.google.android.gms.tasks.OnFailureListener() {
                    @Override
                    public void onFailure(Exception e) {
                        FaceTracker.lastError = "seg:" + e.getMessage();
                    }
                })
                .addOnCompleteListener(new com.google.android.gms.tasks.OnCompleteListener<SegmentationMask>() {
                    @Override
                    public void onComplete(com.google.android.gms.tasks.Task<SegmentationMask> task) {
                        busy = false;
                        img.close();
                    }
                });
    }
}
