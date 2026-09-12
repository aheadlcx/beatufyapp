package com.example.beautycamera;

import android.content.Context;
import android.util.Log;
import android.view.Surface;

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
    private ProcessCameraProvider provider;
    private boolean front = true;

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
        bind();
    }

    private void bind() {
        ProcessCameraProvider p = provider;
        if (p == null) return;
        p.unbindAll();

        int rotation = rotationDegrees();

        Preview preview = new Preview.Builder().setTargetRotation(rotation).build();
        preview.setSurfaceProvider(cameraExecutor, new Preview.SurfaceProvider() {
            @Override
            public void onSurfaceRequested(SurfaceRequest request) {
                android.view.Surface surface = renderer.createInputSurface(
                        request.getResolution().getWidth(), request.getResolution().getHeight());
                request.provideSurface(surface, cameraExecutor, new androidx.core.util.Consumer<SurfaceRequest.Result>() {
                    @Override
                    public void accept(SurfaceRequest.Result result) {
                    }
                });
            }
        });

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetRotation(rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(analysisExecutor, new FaceTracker(new FaceTracker.FrontCameraProvider() {
            @Override
            public boolean isFront() {
                return front;
            }
        }, renderer));

        try {
            p.bindToLifecycle(lifecycleOwner,
                    front ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, analysis);
        } catch (Exception e) {
            Log.e(TAG, "bind failed", e);
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
    }
}
