package com.example.beautycamera;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

/**
 * 通过重力传感器判断持机姿势，输出补偿角（0/90/180/270）：
 * 竖持 y>0 → 0°；倒持 y<0 → 180°；横持按 x 符号 → 90°/270°；
 * 平放（z 轴分量占主导）时重力无法指示"哪边是上"，保持最近一次的方向。
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
