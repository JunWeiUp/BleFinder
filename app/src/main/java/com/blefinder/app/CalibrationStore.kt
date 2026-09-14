package com.blefinder.app

import android.content.Context
import kotlin.math.roundToInt

/**
 * 每台设备的 1 米校准值：该设备在 1 米处实测的 RSSI（dBm），按 MAC 地址持久化。
 * 校准后距离估算使用该值替代通用默认值（-59 dBm）。
 */
object CalibrationStore {

    private const val PREFS_NAME = "device_calibration"

    fun get(context: Context, mac: String): Double? =
        prefs(context).getInt(mac, Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }
            ?.toDouble()

    fun put(context: Context, mac: String, txPower: Double) {
        prefs(context).edit().putInt(mac, txPower.roundToInt()).apply()
    }

    fun all(context: Context): Map<String, Double> =
        prefs(context).all.mapNotNull { (mac, value) ->
            (value as? Int)?.let { mac to it.toDouble() }
        }.toMap()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
