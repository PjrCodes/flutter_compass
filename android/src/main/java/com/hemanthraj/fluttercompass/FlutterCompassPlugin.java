/*
 The MIT License

 Portions Copyright (c) 2020 Mapbox
 */

package com.hemanthraj.fluttercompass;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.EventChannel.EventSink;
import io.flutter.plugin.common.EventChannel.StreamHandler;
import io.flutter.plugin.common.PluginRegistry.Registrar;

public final class FlutterCompassPlugin implements StreamHandler {
    private static final String TAG = "FlutterCompass";
    // The rate sensor events will be delivered at. As the Android documentation states, this is only
    // a hint to the system and the events might actually be received faster or slower then this
    // specified rate. Since the minimum Android API levels about 9, we are able to set this value
    // ourselves rather than using one of the provided constants which deliver updates too quickly for
    // our use case. The default is set to 100ms
    private static final int SENSOR_DELAY_MICROS = 100 * 1000;
    // Filtering coefficient 0 < ALPHA < 1
    private static final float ALPHA = 0.45f;

    // Controls the compass update rate in milliseconds
    private static final int COMPASS_UPDATE_RATE_MS = 500;

    private SensorEventListener sensorEventListener;

    private final WindowManager windowManager;
    private final SensorManager sensorManager;

    @Nullable
    private Sensor compassSensor;
    @Nullable
    private Sensor gravitySensor;
    @Nullable
    private Sensor magneticFieldSensor;

    private float[] truncatedRotationVectorValue = new float[4];
    private float[] rotationMatrix = new float[9];
    private float[] rotationVectorValue;
    private float lastHeading;
    private int lastAccuracySensorStatus;

    private long compassUpdateNextTimestamp;
    private float[] gravityValues = new float[3];
    private float[] magneticValues = new float[3];


    private FlutterCompassPlugin(Context context) {
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        compassSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (compassSensor == null) {
            Log.d(TAG, "Rotation vector sensor not supported on device, "
                    + "falling back to accelerometer and magnetic field.");
            gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            magneticFieldSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        }
    }

    public static void registerWith(Registrar registrar) {
        EventChannel channel = new EventChannel(registrar.messenger(), "hemanthraj/flutter_compass");
        channel.setStreamHandler(new FlutterCompassPlugin(registrar.context()));
    }

    public void onListen(Object arguments, EventSink events) {
        sensorEventListener = createSensorEventListener(events);

        if (isCompassSensorAvailable()) {
            // Does nothing if the sensors already registered.
            sensorManager.registerListener(sensorEventListener, compassSensor, SENSOR_DELAY_MICROS);
        } else {
            sensorManager.registerListener(sensorEventListener, gravitySensor, SENSOR_DELAY_MICROS);
            sensorManager.registerListener(sensorEventListener, magneticFieldSensor, SENSOR_DELAY_MICROS);
        }
    }

    public void onCancel(Object arguments) {
        if (isCompassSensorAvailable()) {
            sensorManager.unregisterListener(sensorEventListener, compassSensor);
        } else {
            sensorManager.unregisterListener(sensorEventListener, gravitySensor);
            sensorManager.unregisterListener(sensorEventListener, magneticFieldSensor);
        }
    }

    private boolean isCompassSensorAvailable() {
        return compassSensor != null;
    }


    SensorEventListener createSensorEventListener(final EventSink events) {
        return new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                if (lastAccuracySensorStatus == SensorManager.SENSOR_STATUS_UNRELIABLE) {
                    Log.d(TAG, "Compass sensor is unreliable, device calibration is needed.");
                    // Update the heading, even if the sensor is unreliable.
                    // This makes it possible to use a different indicator for the unreliable case,
                    // instead of just changing the RenderMode to NORMAL.
                }
                if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
                    rotationVectorValue = getRotationVectorFromSensorEvent(event);
                    updateOrientation();
                } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                    gravityValues = lowPassFilter(getRotationVectorFromSensorEvent(event), gravityValues);
                    updateOrientation();
                } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
                    magneticValues = lowPassFilter(getRotationVectorFromSensorEvent(event), magneticValues);
                    updateOrientation();
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                if (lastAccuracySensorStatus != accuracy) {
                    lastAccuracySensorStatus = accuracy;
                }
            }

            @SuppressWarnings("SuspiciousNameCombination")
            private void updateOrientation() {
                // check when the last time the compass was updated, return if too soon.
                long currentTime = SystemClock.elapsedRealtime();
                if (currentTime < compassUpdateNextTimestamp) {
                    return;
                }

                if (rotationVectorValue != null) {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVectorValue);
                } else {
                    // Get rotation matrix given the gravity and geomagnetic matrices
                    SensorManager.getRotationMatrix(rotationMatrix, null, gravityValues, magneticValues);
                }

                int worldAxisForDeviceAxisX;
                int worldAxisForDeviceAxisY;

                // Assume the device screen was parallel to the ground,
                // and adjust the rotation matrix for the device orientation.
                switch (windowManager.getDefaultDisplay().getRotation()) {
                    case Surface.ROTATION_90:
                        worldAxisForDeviceAxisX = SensorManager.AXIS_Y;
                        worldAxisForDeviceAxisY = SensorManager.AXIS_MINUS_X;
                        break;
                    case Surface.ROTATION_180:
                        worldAxisForDeviceAxisX = SensorManager.AXIS_MINUS_X;
                        worldAxisForDeviceAxisY = SensorManager.AXIS_MINUS_Y;
                        break;
                    case Surface.ROTATION_270:
                        worldAxisForDeviceAxisX = SensorManager.AXIS_MINUS_Y;
                        worldAxisForDeviceAxisY = SensorManager.AXIS_X;
                        break;
                    case Surface.ROTATION_0:
                    default:
                        worldAxisForDeviceAxisX = SensorManager.AXIS_X;
                        worldAxisForDeviceAxisY = SensorManager.AXIS_Y;
                        break;
                }

                float[] adjustedRotationMatrix = new float[9];
                SensorManager.remapCoordinateSystem(rotationMatrix, worldAxisForDeviceAxisX,
                        worldAxisForDeviceAxisY, adjustedRotationMatrix);

                // Transform rotation matrix into azimuth/pitch/roll
                float[] orientation = new float[3];
                SensorManager.getOrientation(adjustedRotationMatrix, orientation);

                if (orientation[1] < -Math.PI / 4) {
                    // The pitch is less than -45 degrees.
                    // Remap the axes as if the device screen was the instrument panel.
                    switch (windowManager.getDefaultDisplay().getRotation()) {
                        case Surface.ROTATION_90:
                            worldAxisForDeviceAxisX = SensorManager.AXIS_Z;
                            worldAxisForDeviceAxisY = SensorManager.AXIS_MINUS_X;
                            break;
                        case Surface.ROTATION_180:
                            worldAxisForDeviceAxisX = SensorManager.AXIS_MINUS_X;
                            worldAxisForDeviceAxisY = SensorManager.AXIS_MINUS_Z;
                            break;
                        case Surface.ROTATION_270:
                            worldAxisForDeviceAxisX = SensorManager.AXIS_MINUS_Z;
                            worldAxisForDeviceAxisY = SensorManager.AXIS_X;
                            break;
                        case Surface.ROTATION_0:
                        default:
                            worldAxisForDeviceAxisX = SensorManager.AXIS_X;
                            worldAxisForDeviceAxisY = SensorManager.AXIS_Z;
                            break;
                    }
                } else if (orientation[1] > Math.PI / 4) {
                    // The pitch is larger than 45 degrees.
                    // Remap the axes as if the device screen was upside down and facing back.
                    switch (windowManager.getDefaultDisplay().getRotation()) {
                        case Surface.ROTATION_90:
                            worldAxisForDeviceAxisX = SensorManager.AXIS_MINUS_Z;
                            worldAxisForDeviceAxisY = SensorManager.AXIS_MINUS_X;
                            break;
                        case Surface.ROTATION_180:
                            worldAxisForDeviceAxisX = SensorManager.AXIS_MINUS_X;
                            worldAxisForDeviceAxisY = SensorManager.AXIS_Z;
                            break;
                        case Surface.ROTATION_270:
                            worldAxisForDeviceAxisX = SensorManager.AXIS_Z;
                            worldAxisForDeviceAxisY = SensorManager.AXIS_X;
                            break;
                        case Surface.ROTATION_0:
                        default:
                            worldAxisForDeviceAxisX = SensorManager.AXIS_X;
                            worldAxisForDeviceAxisY = SensorManager.AXIS_MINUS_Z;
                            break;
                    }
                } else if (Math.abs(orientation[2]) > Math.PI / 2) {
                    // The roll is less than -90 degrees, or is larger than 90 degrees.
                    // Remap the axes as if the device screen was face down.
                    switch (windowManager.getDefaultDisplay().getRotation()) {
                        case Surface.ROTATION_90:
                            worldAxisForDeviceAxisX = SensorManager.AXIS_MINUS_Y;
                            worldAxisForDeviceAxisY = SensorManager.AXIS_MINUS_X;
                            break;
                        case Surface.ROTATION_180:
                            worldAxisForDeviceAxisX = SensorManager.AXIS_MINUS_X;
                            worldAxisForDeviceAxisY = SensorManager.AXIS_Y;
                            break;
                        case Surface.ROTATION_270:
                            worldAxisForDeviceAxisX = SensorManager.AXIS_Y;
                            worldAxisForDeviceAxisY = SensorManager.AXIS_X;
                            break;
                        case Surface.ROTATION_0:
                        default:
                            worldAxisForDeviceAxisX = SensorManager.AXIS_X;
                            worldAxisForDeviceAxisY = SensorManager.AXIS_MINUS_Y;
                            break;
                    }
                }

                SensorManager.remapCoordinateSystem(rotationMatrix, worldAxisForDeviceAxisX,
                        worldAxisForDeviceAxisY, adjustedRotationMatrix);

                // Transform rotation matrix into azimuth/pitch/roll
                SensorManager.getOrientation(adjustedRotationMatrix, orientation);

                // The x-axis is all we care about here.
                notifyCompassChangeListeners((float) Math.toDegrees(orientation[0]));

                // Update the compassUpdateNextTimestamp
                compassUpdateNextTimestamp = currentTime + COMPASS_UPDATE_RATE_MS;
            }

            private void notifyCompassChangeListeners(float heading) {
                events.success(heading);
                lastHeading = heading;
            }

            /**
             * Helper function, that filters newValues, considering previous values
             *
             * @param newValues      array of float, that contains new data
             * @param smoothedValues array of float, that contains previous state
             * @return float filtered array of float
             */
            private float[] lowPassFilter(float[] newValues, float[] smoothedValues) {
                if (smoothedValues == null) {
                    return newValues;
                }
                for (int i = 0; i < newValues.length; i++) {
                    smoothedValues[i] = smoothedValues[i] + ALPHA * (newValues[i] - smoothedValues[i]);
                }
                return smoothedValues;
            }

            /**
             * Pulls out the rotation vector from a SensorEvent, with a maximum length
             * vector of four elements to avoid potential compatibility issues.
             *
             * @param event the sensor event
             * @return the events rotation vector, potentially truncated
             */
            @NonNull
            private float[] getRotationVectorFromSensorEvent(@NonNull SensorEvent event) {
                if (event.values.length > 4) {
                    // On some Samsung devices SensorManager.getRotationMatrixFromVector
                    // appears to throw an exception if rotation vector has length > 4.
                    // For the purposes of this class the first 4 values of the
                    // rotation vector are sufficient (see crbug.com/335298 for details).
                    // Only affects Android 4.3
                    System.arraycopy(event.values, 0, truncatedRotationVectorValue, 0, 4);
                    return truncatedRotationVectorValue;
                } else {
                    return event.values;
                }
            }
        };
    }
}

