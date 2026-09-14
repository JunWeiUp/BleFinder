package com.blefinder.app

import androidx.annotation.ColorRes

/** RSSI 四档配色，列表 / 罗盘 / 轨迹共用同一标准 */
object SignalColors {

    @ColorRes
    fun colorRes(rssi: Int) = when {
        rssi >= -55 -> R.color.signal_green
        rssi >= -70 -> R.color.signal_yellow
        rssi >= -82 -> R.color.signal_orange
        else -> R.color.signal_red
    }
}
