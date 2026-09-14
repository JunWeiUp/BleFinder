package com.blefinder.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.sin

/**
 * 方向罗盘：24 个方位扇区（每个 15°）按该方位采到的信号强弱着色。
 * 白色小三角 = 当前面朝方向；绿色大箭头 = 信号最强的方位。
 * 未采样的扇区显示为暗灰色。
 */
class CompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val BIN_COUNT = 24
        const val BIN_DEGREES = 360f / BIN_COUNT
    }

    /** 各方位扇区的平滑 RSSI，NaN 表示未采样；下标 i 覆盖方位角 [i*15, (i+1)*15) */
    var bins: FloatArray = FloatArray(BIN_COUNT) { Float.NaN }
        set(value) {
            field = value
            invalidate()
        }

    /** 当前面朝方位（度，0=北），白色小三角 */
    var currentAzimuthDeg: Float = Float.NaN
        set(value) {
            field = value
            invalidate()
        }

    /** 结论方位（度），绿色箭头；NaN 表示暂无结论 */
    var conclusionAzimuthDeg: Float = Float.NaN
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1B2333")
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0E1420")
    }
    private val sectorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.signal_green)
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val dirTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8ECF4")
        textSize = 14f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val northPaint = Paint(dirTextPaint).apply {
        color = ContextCompat.getColor(context, R.color.signal_red)
    }
    private val emptySectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22374B59")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 26f * density
        if (r <= 0) return

        canvas.drawCircle(cx, cy, r, bgPaint)

        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        val halfSweep = BIN_DEGREES / 2f - 1f
        for (i in 0 until BIN_COUNT) {
            val v = bins[i]
            val paint = if (v.isNaN()) emptySectorPaint else sectorPaint.apply {
                color = ContextCompat.getColor(context, SignalColors.colorRes(v.toInt()))
            }
            val midAzimuth = i * BIN_DEGREES + BIN_DEGREES / 2f
            canvas.drawArc(oval, midAzimuth - 90f - halfSweep, halfSweep * 2f, true, paint)
        }

        val innerR = r * 0.42f
        canvas.drawCircle(cx, cy, innerR, centerPaint)
        canvas.drawCircle(cx, cy, r, ringPaint)

        // N / E / S / 刻度
        val labelR = r + 14f * density
        drawDirLabel(canvas, cx + x(labelR, 0f), cy + y(labelR, 0f), "N", northPaint)
        drawDirLabel(canvas, cx + x(labelR, 90f), cy + y(labelR, 90f), "E", dirTextPaint)
        drawDirLabel(canvas, cx + x(labelR, 180f), cy + y(labelR, 180f), "S", dirTextPaint)
        drawDirLabel(canvas, cx + x(labelR, 270f), cy + y(labelR, 270f), "W", dirTextPaint)

        // 当前面朝方向：外圈白色小三角（尖朝圆心）
        if (!currentAzimuthDeg.isNaN()) {
            val a = Math.toRadians((currentAzimuthDeg - 90f).toDouble())
            val tipX = (cx + cos(a) * r).toFloat()
            val tipY = (cy + sin(a) * r).toFloat()
            val baseR = r + 16f * density
            val left = Math.toRadians((currentAzimuthDeg - 90f - 9f).toDouble())
            val right = Math.toRadians((currentAzimuthDeg - 90f + 9f).toDouble())
            val path = android.graphics.Path().apply {
                moveTo(tipX, tipY)
                lineTo((cx + cos(left) * baseR).toFloat(), (cy + sin(left) * baseR).toFloat())
                lineTo((cx + cos(right) * baseR).toFloat(), (cy + sin(right) * baseR).toFloat())
                close()
            }
            canvas.drawPath(path, markerPaint)
        }

        // 结论箭头：中心指向最强方位
        if (!conclusionAzimuthDeg.isNaN()) {
            val a = Math.toRadians((conclusionAzimuthDeg - 90f).toDouble())
            val endX = (cx + cos(a) * innerR * 0.85f).toFloat()
            val endY = (cy + sin(a) * innerR * 0.85f).toFloat()
            canvas.drawLine(cx, cy, endX, endY, arrowPaint)
            val headA = 32f * density
            val back = Math.toRadians((conclusionAzimuthDeg - 90f + 150f).toDouble())
            val back2 = Math.toRadians((conclusionAzimuthDeg - 90f - 150f).toDouble())
            canvas.drawLine(endX, endY, (endX + cos(back) * headA).toFloat(), (endY + sin(back) * headA).toFloat(), arrowPaint)
            canvas.drawLine(endX, endY, (endX + cos(back2) * headA).toFloat(), (endY + sin(back2) * headA).toFloat(), arrowPaint)
        }
    }

    /** 方位角 → 屏幕坐标偏移（画布 0° = 东，方位 0° = 北 = 屏幕上方） */
    private fun x(radius: Float, azimuthDeg: Float): Float =
        (radius * cos(Math.toRadians((azimuthDeg - 90f).toDouble()))).toFloat()

    private fun y(radius: Float, azimuthDeg: Float): Float =
        (radius * sin(Math.toRadians((azimuthDeg - 90f).toDouble()))).toFloat()

    private fun drawDirLabel(canvas: Canvas, px: Float, py: Float, text: String, paint: Paint) {
        val offset = -(paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, px, py + offset, paint)
    }
}
