package com.example.beautycamera;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.tabs.TabLayout;

public class MainActivity extends AppCompatActivity {

    private interface Getter {
        float get();
    }

    private interface Setter {
        void set(float v);
    }

    private static final class SliderSpec {
        final String label;
        final Getter getter;
        final Setter setter;

        SliderSpec(String label, Getter getter, Setter setter) {
            this.label = label;
            this.getter = getter;
            this.setter = setter;
        }
    }

    private final BeautyParams params = new BeautyParams();
    private CameraRenderer renderer;
    private CameraController controller;
    private OrientationSensor orientationSensor;
    private android.opengl.GLSurfaceView glSurface;
    private LinearLayout sliderPanel;
    private ScrollView sliderScroll;
    private View filterRow;
    private View presetsRow;
    private LinearLayout presetChips;
    private View captureRow;
    private LinearLayout captureChips;
    private TextView tvHint;
    private TextView tvCount;
    private TextView btnCompare;
    private TextView btnFlip;
    private TextView btnTimer;
    private TextView btnLight;

    private ScaleGestureDetector scaleDetector;
    private int countdownSeconds = 0;
    private boolean lightOn = false;
    private volatile boolean countingDown = false;

    private final SliderSpec[] beautySliders = {
            new SliderSpec("磨皮", new Getter() { public float get() { return params.smooth; } },
                    new Setter() { public void set(float v) { params.smooth = v; } }),
            new SliderSpec("美白", new Getter() { public float get() { return params.whiten; } },
                    new Setter() { public void set(float v) { params.whiten = v; } }),
            new SliderSpec("红润", new Getter() { public float get() { return params.ruddy; } },
                    new Setter() { public void set(float v) { params.ruddy = v; } }),
            new SliderSpec("锐化", new Getter() { public float get() { return params.sharpen; } },
                    new Setter() { public void set(float v) { params.sharpen = v; } }),
            new SliderSpec("饱和", new Getter() { public float get() { return params.saturate; } },
                    new Setter() { public void set(float v) { params.saturate = v; } }),
            new SliderSpec("肤色", new Getter() { public float get() { return params.skinTone; } },
                    new Setter() { public void set(float v) { params.skinTone = v; } }),
            new SliderSpec("黑眼圈", new Getter() { public float get() { return params.eyeCircle; } },
                    new Setter() { public void set(float v) { params.eyeCircle = v; } }),
            new SliderSpec("修容", new Getter() { public float get() { return params.contouring; } },
                    new Setter() { public void set(float v) { params.contouring = v; } }),
    };

    private final SliderSpec[] shapeSliders = {
            new SliderSpec("大眼", new Getter() { public float get() { return params.eyeEnlarge; } },
                    new Setter() { public void set(float v) { params.eyeEnlarge = v; } }),
            new SliderSpec("瘦脸", new Getter() { public float get() { return params.faceSlim; } },
                    new Setter() { public void set(float v) { params.faceSlim = v; } }),
            new SliderSpec("下巴", new Getter() { public float get() { return params.chinSlim; } },
                    new Setter() { public void set(float v) { params.chinSlim = v; } }),
            new SliderSpec("瘦鼻", new Getter() { public float get() { return params.noseSlim; } },
                    new Setter() { public void set(float v) { params.noseSlim = v; } }),
            new SliderSpec("微笑", new Getter() { public float get() { return params.smileLift; } },
                    new Setter() { public void set(float v) { params.smileLift = v; } }),
            new SliderSpec("额头", new Getter() { public float get() { return params.forehead; } },
                    new Setter() { public void set(float v) { params.forehead = v; } }),
    };

    private final SliderSpec[] blurSliders = {
            new SliderSpec("背景虚化", new Getter() { public float get() { return params.bgBlur; } },
                    new Setter() { public void set(float v) { params.bgBlur = v; } }),
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        glSurface = findViewById(R.id.glSurface);
        sliderPanel = findViewById(R.id.sliderPanel);
        sliderScroll = findViewById(R.id.sliderScroll);
        filterRow = findViewById(R.id.filterRow);
        presetsRow = findViewById(R.id.presetsRow);
        presetChips = findViewById(R.id.presetChips);
        captureRow = findViewById(R.id.captureRow);
        captureChips = findViewById(R.id.captureChips);
        tvHint = findViewById(R.id.tvHint);
        tvCount = findViewById(R.id.tvCount);
        btnCompare = findViewById(R.id.btnCompare);
        btnFlip = findViewById(R.id.btnFlip);

        glSurface.setEGLContextClientVersion(2);
        renderer = new CameraRenderer(params, null);
        renderer.attachGlView(glSurface);
        glSurface.setRenderer(renderer);
        glSurface.setRenderMode(android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        controller = new CameraController(this, this, renderer);

        // pinch to zoom
        scaleDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        controller.zoomBy(detector.getScaleFactor());
                        return true;
                    }
                });
        glSurface.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                scaleDetector.onTouchEvent(event);
                return true;
            }
        });

        ImageButton switchBtn = findViewById(R.id.btnSwitch);
        switchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                controller.switchCamera();
                controller.resetZoom();
            }
        });

        btnCompare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                renderer.splitMode = !renderer.splitMode;
                v.setAlpha(renderer.splitMode ? 1f : 0.6f);
                tvHint.setText("原图    |    美颜");
                tvHint.setVisibility(renderer.splitMode ? View.VISIBLE : View.GONE);
            }
        });
        btnCompare.setAlpha(0.6f);

        btnFlip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                renderer.rotationOverride = (renderer.rotationOverride + 180) % 360;
                v.setAlpha(renderer.rotationOverride == 0 ? 0.7f : 1f);
            }
        });
        btnFlip.setAlpha(0.7f);

        ImageButton captureBtn = findViewById(R.id.btnCapture);
        captureBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                capture();
            }
        });

        orientationSensor = new OrientationSensor(
                (android.hardware.SensorManager) getSystemService(android.hardware.SensorManager.class),
                new OrientationSensor.Listener() {
                    @Override
                    public void onOrientationChanged(int deg) {
                        gravityOrientation = deg;
                    }
                });
        orientationSensor.start();

        final TextView tvDebug = findViewById(R.id.tvDebug);
        final android.os.Handler handler = new android.os.Handler();
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                float euler = FaceTracker.lastEulerX;
                boolean hasFace = FaceTracker.lastFaces > 0;
                float displayRoll = -euler;
                if (hasFace) {
                    if (Math.abs(angleDelta(displayRoll, faceOrientation)) > 60) {
                        int q = (int) Math.round(displayRoll / 90f);
                        faceOrientation = ((q % 4) + 4) % 4 * 90;
                    }
                }
                int base = hasFace ? faceOrientation : gravityOrientation;
                renderer.rotationOverride = (base + (manualFlip ? 180 : 0)) % 360;
                tvDebug.setText("an=" + FaceTracker.analyzeCalls
                        + " fc=" + FaceTracker.lastFaces
                        + " ex=" + (int) euler
                        + " g=" + gravityOrientation
                        + " c=" + renderer.rotationOverride
                        + (FaceTracker.lastError.isEmpty() ? "" : " e=" + FaceTracker.lastError));
                handler.postDelayed(this, 500);
            }
        };
        handler.post(tick);

        setupTabs();
        if (hasPermission()) {
            startCamera();
        } else {
            requestPermission();
        }
    }

    private void setupTabs() {
        TabLayout tabs = findViewById(R.id.tabs);
        tabs.addTab(tabs.newTab().setText("美颜"));
        tabs.addTab(tabs.newTab().setText("美型"));
        tabs.addTab(tabs.newTab().setText("滤镜"));
        tabs.addTab(tabs.newTab().setText("虚化"));
        tabs.addTab(tabs.newTab().setText("拍摄"));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                showTab(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });
        showTab(0);
        buildPresetChips();
        buildFilterChips();
        buildCaptureChips();
    }

    private void showTab(int pos) {
        sliderScroll.setVisibility(View.GONE);
        filterRow.setVisibility(View.GONE);
        presetsRow.setVisibility(View.GONE);
        captureRow.setVisibility(View.GONE);
        if (pos == 0) {
            sliderScroll.setVisibility(View.VISIBLE);
            presetsRow.setVisibility(View.VISIBLE);
            buildSliders(beautySliders);
        } else if (pos == 1) {
            sliderScroll.setVisibility(View.VISIBLE);
            buildSliders(shapeSliders);
        } else if (pos == 2) {
            filterRow.setVisibility(View.VISIBLE);
        } else if (pos == 3) {
            sliderScroll.setVisibility(View.VISIBLE);
            buildSliders(blurSliders);
        } else {
            captureRow.setVisibility(View.VISIBLE);
        }
    }

    private void buildSliders(SliderSpec[] specs) {
        sliderPanel.removeAllViews();
        for (final SliderSpec spec : specs) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView label = new TextView(this);
            label.setText(spec.label);
            label.setTextColor(0xCCFFFFFF);
            label.setTextSize(13f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0.18f);
            lp.gravity = Gravity.CENTER_VERTICAL;
            label.setLayoutParams(lp);

            SeekBar bar = new SeekBar(this);
            bar.setMax(100);
            bar.setProgress((int) (spec.getter.get() * 100));
            bar.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0.82f));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    spec.setter.set(progress / 100f);
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });

            row.addView(label);
            row.addView(bar);
            sliderPanel.addView(row);
        }
    }

    private void buildPresetChips() {
        presetChips.removeAllViews();
        for (int i = 0; i < BeautyParams.PRESET_NAMES.length; i++) {
            final int index = i;
            TextView chip = makeChip(BeautyParams.PRESET_NAMES[i]);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    params.applyPreset(index);
                    showTab(0);
                    toast("预设: " + BeautyParams.PRESET_NAMES[index]);
                }
            });
            presetChips.addView(chip);
        }
    }

    private void buildFilterChips() {
        filterChips = findViewById(R.id.filterChips);
        filterChips.removeAllViews();
        for (int i = 0; i < BeautyParams.FILTER_NAMES.length; i++) {
            final int index = i;
            TextView chip = makeChip(BeautyParams.FILTER_NAMES[i]);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    params.filterIndex = index;
                    refreshChipStates(filterChips);
                }
            });
            filterChips.addView(chip);
        }
        refreshChipStates(filterChips);
    }

    private LinearLayout filterChips;

    private void buildCaptureChips() {
        captureChips.removeAllViews();
        btnTimer = makeChip("倒计时:关");
        btnTimer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                countdownSeconds = countdownSeconds == 0 ? 3 : (countdownSeconds == 3 ? 10 : 0);
                btnTimer.setText(countdownSeconds == 0 ? "倒计时:关"
                        : "倒计时:" + countdownSeconds + "s");
                btnTimer.setAlpha(countdownSeconds == 0 ? 0.7f : 1f);
            }
        });
        captureChips.addView(btnTimer);

        btnLight = makeChip("屏幕补光");
        btnLight.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                lightOn = !lightOn;
                btnLight.setAlpha(lightOn ? 1f : 0.7f);
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = lightOn ? 1f
                        : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                getWindow().setAttributes(lp);
            }
        });
        btnLight.setAlpha(0.7f);
        captureChips.addView(btnLight);
    }

    private TextView makeChip(String text) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextSize(14f);
        chip.setPadding(dp(24), dp(10), dp(24), dp(10));
        chip.setGravity(Gravity.CENTER);
        chip.setTextColor(0xFFFFFFFF);
        chip.setBackgroundResource(R.drawable.chip_bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(12);
        chip.setLayoutParams(lp);
        return chip;
    }

    private void refreshChipStates(LinearLayout chips) {
        for (int i = 0; i < chips.getChildCount(); i++) {
            View v = chips.getChildAt(i);
            v.setAlpha(i == params.filterIndex ? 1f : 0.55f);
        }
    }

    private void capture() {
        if (countingDown) return;
        if (countdownSeconds > 0) {
            countingDown = true;
            final android.os.Handler h = new android.os.Handler();
            final int[] remain = {countdownSeconds};
            final Runnable countTick = new Runnable() {
                @Override
                public void run() {
                    if (remain[0] > 0) {
                        tvCount.setText(String.valueOf(remain[0]));
                        tvCount.setVisibility(View.VISIBLE);
                        remain[0]--;
                        h.postDelayed(this, 1000);
                    } else {
                        tvCount.setVisibility(View.GONE);
                        countingDown = false;
                        doCapture();
                    }
                }
            };
            h.post(countTick);
        } else {
            doCapture();
        }
    }

    private void doCapture() {
        tvHint.setText("处理中…");
        tvHint.setVisibility(View.VISIBLE);
        renderer.capture(new CameraRenderer.BitmapCallback() {
            @Override
            public void onResult(final Bitmap bmp) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvHint.setVisibility(View.GONE);
                        if (bmp != null) {
                            Uri uri = saveImage(bmp);
                            bmp.recycle();
                            toast(uri != null ? "已保存到相册" : "保存失败");
                        } else {
                            toast("拍照失败");
                        }
                    }
                });
            }
        });
    }

    private Uri saveImage(Bitmap bmp) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME,
                    "beauty_" + System.currentTimeMillis() + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/BeautyCamera");
            } else {
                values.put(MediaStore.Images.Media.DATA,
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                                .getAbsolutePath() + "/beauty_" + System.currentTimeMillis() + ".jpg");
            }
            Uri uri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return null;
            java.io.OutputStream os = getContentResolver().openOutputStream(uri);
            if (os == null) return null;
            try {
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, os);
            } finally {
                os.close();
            }
            return uri;
        } catch (Exception e) {
            android.util.Log.e("BeautyCamera", "saveImage failed", e);
            return null;
        }
    }

    private boolean hasPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermission() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            toast("需要相机权限");
            finish();
        }
    }

    private void startCamera() {
        controller.start(null);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static int angleDelta(float target, int current) {
        int d = (((int) target - current) % 360 + 360) % 360;
        return d > 180 ? d - 360 : d;
    }

    private volatile boolean manualFlip = false;
    private volatile int gravityOrientation = 0;
    private volatile int faceOrientation = 0;

    @Override
    protected void onPause() {
        super.onPause();
        if (orientationSensor != null) orientationSensor.stop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (orientationSensor != null) orientationSensor.start();
    }
}
