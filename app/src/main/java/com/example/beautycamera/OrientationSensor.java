package com.example.beautycamera;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

/**
 * Tracks how the device is physically held (via the gravity sensor) and reports
 * the extra rotation (0/90/180/270) needed so the camera preview stays upright
 * from the user's point of view - e.g. flipping the phone upside down flips the
 * preview by 180. When the phone lies flat the last orientation is kept.
 */
public class OrientationSensor implements SensorEventListener {

    public interface Listener {
        void onOrientationChanged(int deg);
    }

    private final SensorManager sensorManager;
    private final Listener listener;
    private int current = 0;

    public OrientationSensor(SensorManager sm, Listener listener) {
        this.sensorManager = sm;
        this.listener = listener;
    }

    public void start() {
        Sensor s = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        if (s == null) s = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (s != null) {
            sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    public void stop() {
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];

        int deg;
        if (Math.abs(z) > 7f && Math.abs(z) > Math.abs(x) && Math.abs(z) > Math.abs(y)) {
            // lying flat: keep the last known orientation (no reliable "up")
            return;
        } else if (Math.abs(y) >= Math.abs(x)) {
            deg = y > 0 ? 0 : 180;   // portrait / upside-down portrait
        } else {
            deg = x > 0 ? 270 : 90;  // landscape left / right
        }

        if (deg != current) {
            current = deg;
            Log.d("OrientationSensor", "orientation=" + deg
                    + " (x=" + x + " y=" + y + " z=" + z + ")");
            if (listener != null) listener.onOrientationChanged(deg);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
