package com.blefinder.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 追踪模式下的运动传感器封装（均在主线程回调）：
 * - ROTATION_VECTOR → 两种方位角（0° = 磁北，顺时针增大）：
 *   [azimuthScreenDeg] 屏幕法线（面朝）方向 —— 罗盘模式，手机贴胸、屏幕朝外；
 *   [azimuthTopDeg] 设备顶部方向 —— 轨迹模式，竖屏手持看屏、顶部朝前进方向。
 * - ACCELEROMETER → 简易步进检测（平滑加速度的峰谷周期 + 最小间隔），
 *   不使用硬件计步器，因此无需 ACTIVITY_RECOGNITION 权限。
 */
class SensorTracker(context: Context) : SensorEventListener {

    var onRotation: ((azimuthScreenDeg: Float, azimuthTopDeg: Float) -> Unit)? = null
    var onStep: ((azimuthTopDeg: Float) -> Unit)? = null

    val hasSensors: Boolean
        get() = rotationSensor != null && accelSensor != null

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val rotationMatrix = FloatArray(9)
    private val orientationValues = FloatArray(3)

    private var smoothedMag = 9.8f
    private var stepArmed = true
    private var lastStepAt = 0L

    var lastAzimuthScreenDeg = Float.NaN
        private set
    var lastAzimuthTopDeg = Float.NaN
        private set

    fun start() {
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        accelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() = sensorManager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                // 屏幕法线（+Z 轴）的世界方位：世界 X=东、Y=北
                val screen = Math.toDegrees(
                    atan2(rotationMatrix[2].toDouble(), rotationMatrix[5].toDouble())
                ).toFloat()
                lastAzimuthScreenDeg = normalize(screen)
                SensorManager.getOrientation(rotationMatrix, orientationValues)
                lastAzimuthTopDeg =
                    normalize(Math.toDegrees(orientationValues[0].toDouble()).toFloat())
                onRotation?.invoke(lastAzimuthScreenDeg, lastAzimuthTopDeg)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val mag = sqrt(x * x + y * y + z * z)
                smoothedMag = smoothedMag * 0.8f + mag * 0.2f
                val now = SystemClock.elapsedRealtime()
                if (smoothedMag < 9.95f) stepArmed = true
                if (stepArmed && smoothedMag > 10.15f &&
                    now - lastStepAt > 380 && !lastAzimuthTopDeg.isNaN()
                ) {
                    stepArmed = false
                    lastStepAt = now
                    onStep?.invoke(lastAzimuthTopDeg)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun normalize(deg: Float): Float {
        var d = deg
        while (d < 0f) d += 360f
        return d % 360f
    }
}
