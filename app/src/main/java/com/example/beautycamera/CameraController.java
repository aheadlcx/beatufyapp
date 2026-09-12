package com.example.beautycamera;

import android.content.Context;
import android.util.Log;
import android.view.Surface;

import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceRequest;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraController {

    private static final String TAG = "CameraController";

    public interface OnReadyListener {
        void onReady();
    }

    private final Context context;
    private final LifecycleOwner lifecycleOwner;
    private final CameraRenderer renderer;
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService segExecutor = Executors.newSingleThreadExecutor();
    private ProcessCameraProvider provider;
    private Camera camera;
    private boolean front = true;
    private float zoomLevel = 1f;

    public CameraController(Context context, LifecycleOwner lifecycleOwner, CameraRenderer renderer) {
        this.context = context;
        this.lifecycleOwner = lifecycleOwner;
        this.renderer = renderer;
    }

    public void start(final OnReadyListener onReady) {
        cameraExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final ProcessCameraProvider p = ProcessCameraProvider.getInstance(context).get();
                    provider = p;
                    ContextCompat.getMainExecutor(context).execute(new Runnable() {
                        @Override
                        public void run() {
                            bind();
                            if (onReady != null) onReady.onReady();
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "camera provider failed", e);
                }
            }
        });
    }

    public void switchCamera() {
        front = !front;
        renderer.setFrontCamera(front);
        zoomLevel = 1f;
        bind();
    }

    public boolean isFront() {
        return front;
    }

    /** Multiply current zoom by the given factor (clamped 1..8). */
    public void zoomBy(float factor) {
        androidx.camera.core.Camera c = camera;
        if (c == null) return;
        try {
            androidx.camera.core.CameraControl ctl = c.getCameraControl();
            zoomLevel = Math.max(1f, Math.min(8f, zoomLevel * factor));
            ctl.setZoomRatio(zoomLevel);
        } catch (Exception e) {
            Log.w(TAG, "zoom failed", e);
        }
    }

    public void resetZoom() {
        zoomLevel = 1f;
        if (camera != null) camera.getCameraControl().setZoomRatio(1f);
    }

    private void bind() {
        ProcessCameraProvider p = provider;
        if (p == null) return;
        p.unbindAll();

        int rotation = rotationDegrees();

        Preview preview = new Preview.Builder().setTargetRotation(rotation).build();
        preview.setSurfaceProvider(cameraExecutor, new Preview.SurfaceProvider() {
            @Override
            public void onSurfaceRequested(final SurfaceRequest request) {
                Surface surface = renderer.createInputSurface(
                        request.getResolution().getWidth(), request.getResolution().getHeight());
                request.provideSurface(surface, cameraExecutor, new androidx.core.util.Consumer<SurfaceRequest.Result>() {
                    @Override
                    public void accept(SurfaceRequest.Result result) {
                    }
                });
            }
        });

        ImageAnalysis faceAnalysis = new ImageAnalysis.Builder()
                .setTargetRotation(rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        faceAnalysis.setAnalyzer(analysisExecutor, new FaceTracker(new FaceTracker.FrontCameraProvider() {
            @Override
            public boolean isFront() {
                return front;
            }
        }, renderer));

        ImageAnalysis segAnalysis = new ImageAnalysis.Builder()
                .setTargetRotation(rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        segAnalysis.setAnalyzer(segExecutor, new SegmentationAnalyzer(new SegmentationAnalyzer.MaskSink() {
            @Override
            public void setMask(int w, int h, byte[] data) {
                renderer.setMask(w, h, data);
            }
        }));

        try {
            camera = p.bindToLifecycle(lifecycleOwner,
                    front ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, faceAnalysis, segAnalysis);
        } catch (Exception e) {
            Log.e(TAG, "bind failed", e);
            try {
                // some devices only support 2 use cases
                camera = p.bindToLifecycle(lifecycleOwner,
                        front ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, faceAnalysis);
            } catch (Exception e2) {
                Log.e(TAG, "bind fallback failed", e2);
            }
        }
    }

    private int rotationDegrees() {
        android.view.WindowManager wm =
                (android.view.WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        int rotation = wm.getDefaultDisplay().getRotation();
        switch (rotation) {
            case Surface.ROTATION_90: return 90;
            case Surface.ROTATION_180: return 180;
            case Surface.ROTATION_270: return 270;
            default: return 0;
        }
    }

    public void release() {
        if (provider != null) provider.unbindAll();
        cameraExecutor.shutdown();
        analysisExecutor.shutdown();
        segExecutor.shutdown();
    }
}
