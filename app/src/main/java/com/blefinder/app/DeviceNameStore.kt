package com.blefinder.app

import android.content.Context

/**
 * 用户给设备起的自定义名称，按 MAC 地址持久化在 SharedPreferences 中。
 */
object DeviceNameStore {

    private const val PREFS_NAME = "device_names"

    fun get(context: Context, mac: String): String? =
        prefs(context).getString(mac, null)?.takeIf { it.isNotBlank() }

    fun put(context: Context, mac: String, name: String?) {
        prefs(context).edit().apply {
            if (name.isNullOrBlank()) remove(mac) else putString(mac, name.trim())
        }.apply()
    }

    fun all(context: Context): Map<String, String> =
        prefs(context).all
            .filterValues { it is String && it.isNotBlank() }
            .mapValues { it.value as String }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
