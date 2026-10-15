package com.crediser.plugins.dniscanner;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;

/** Uses available inertial sensors without requiring permissions. */
final class DeviceMotionMonitor implements SensorEventListener {
    private final ScannerConfig config;
    private final SensorManager sensorManager;
    private final Sensor gyroscopeSensor;
    private final Sensor accelerationSensor;
    private final boolean usesLinearAcceleration;
    private final MotionStabilityTracker tracker;
    private final float[] gravity = new float[3];
    private boolean gravityInitialized;
    private boolean gyroscopeRegistered;
    private boolean accelerationRegistered;

    DeviceMotionMonitor(Context context, ScannerConfig config) {
        this.config = config;
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        tracker = new MotionStabilityTracker(config);
        tracker.reset(SystemClock.elapsedRealtime());
        if (sensorManager == null) {
            gyroscopeSensor = null;
            accelerationSensor = null;
            usesLinearAcceleration = false;
            return;
        }
        gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        Sensor linear = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        usesLinearAcceleration = linear != null;
        accelerationSensor = usesLinearAcceleration
            ? linear
            : sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    void start() {
        long now = SystemClock.elapsedRealtime();
        tracker.reset(now);
        gravityInitialized = false;
        gyroscopeRegistered = false;
        accelerationRegistered = false;
        if (sensorManager == null) {
            return;
        }
        if (gyroscopeSensor != null) {
            gyroscopeRegistered = sensorManager.registerListener(
                this,
                gyroscopeSensor,
                SensorManager.SENSOR_DELAY_GAME
            );
        }
        if (accelerationSensor != null) {
            accelerationRegistered = sensorManager.registerListener(
                this,
                accelerationSensor,
                SensorManager.SENSOR_DELAY_GAME
            );
        }
    }

    void stop() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        gyroscopeRegistered = false;
        accelerationRegistered = false;
    }

    MotionSnapshot snapshot() {
        return tracker.snapshot(
            SystemClock.elapsedRealtime(),
            gyroscopeRegistered,
            accelerationRegistered
        );
    }

    boolean hasMovedSince(long motionSequence) {
        return snapshot().motionSequence != motionSequence;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.values.length < 3) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            tracker.addGyroscope(event.values[0], event.values[1], event.values[2], now);
            return;
        }
        if (event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
            tracker.addAcceleration(event.values[0], event.values[1], event.values[2], now);
            return;
        }
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) {
            return;
        }
        if (!gravityInitialized) {
            System.arraycopy(event.values, 0, gravity, 0, 3);
            gravityInitialized = true;
            tracker.addAcceleration(0f, 0f, 0f, now);
            return;
        }
        float alpha = config.accelerometerGravityFilterAlpha;
        for (int index = 0; index < 3; index++) {
            gravity[index] = alpha * gravity[index] + (1f - alpha) * event.values[index];
        }
        tracker.addAcceleration(
            event.values[0] - gravity[0],
            event.values[1] - gravity[1],
            event.values[2] - gravity[2],
            now
        );
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Thresholds tolerate normal consumer-sensor accuracy changes.
    }
}
