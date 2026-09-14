package com.blefinder.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 轨迹上的一个脚印点：世界坐标（x=东，y=北，米）+ 该时刻的平滑 RSSI */
data class TrailPoint(val x: Double, val y: Double, val rssi: Float)

/**
 * 信号轨迹热力图：走过的路径按当时的信号强度染色（绿=近，红=远），
 * 北朝上，自动缩放适配全部轨迹。绿色最深的路段就是离设备最近的位置。
 */
class TrailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var points: List<TrailPoint> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density

    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 9f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.WHITE
    }
    private val currentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1B2333")
    }
    private val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A94A8")
        textSize = 11f * density
    }
    private val legendBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#101826"))

        val pad = 30f * density
        val w = width.toFloat()
        val h = height.toFloat()

        var minX = 0.0; var maxX = 0.0
        var minY = 0.0; var maxY = 0.0
        points.forEach { p ->
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        val spanX = max(maxX - minX, 1.0)
        val spanY = max(maxY - minY, 1.0)
        val scale = min((w - 2 * pad) / spanX, (h - 2 * pad) / spanY)
        val offX = (w - spanX * scale) / 2f
        val offY = (h - spanY * scale) / 2f

        fun sx(x: Double) = (offX + (x - minX) * scale).toFloat()
        fun sy(y: Double) = (h - offY - (y - minY) * scale).toFloat() // 北朝上

        // 网格：起点水平/垂直虚线基准
        canvas.drawLine(sx(minX), sy(0.0), sx(maxX), sy(0.0), gridPaint)
        canvas.drawLine(sx(0.0), sy(minY), sx(0.0), sy(maxY), gridPaint)

        // 轨迹线段，颜色取段末点的信号
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            segmentPaint.color = ContextCompat.getColor(
                context, SignalColors.colorRes(b.rssi.roundToInt())
            )
            canvas.drawLine(sx(a.x), sy(a.y), sx(b.x), sy(b.y), segmentPaint)
        }

        // 起点（空心圆）与当前位置（实心点）
        if (points.isNotEmpty()) {
            canvas.drawCircle(sx(0.0), sy(0.0), 7f * density, startPaint)
            val last = points.last()
            canvas.drawCircle(sx(last.x), sy(last.y), 6f * density, currentPaint)
        } else {
            canvas.drawCircle(sx(0.0), sy(0.0), 7f * density, startPaint)
        }

        drawLegend(canvas, 12f * density, 12f * density)
    }

    private fun drawLegend(canvas: Canvas, x0: Float, y0: Float) {
        val labels = listOf(-40 to R.color.signal_green, -70 to R.color.signal_yellow, -85 to R.color.signal_red)
        var x = x0
        labels.forEach { (rssi, res) ->
            legendBoxPaint.color = ContextCompat.getColor(context, res)
            val boxSize = 8f * density
            canvas.drawRect(x, y0, x + boxSize, y0 + boxSize, legendBoxPaint)
            val label = "$rssi dBm"
            canvas.drawText(label, x + boxSize + 4f * density, y0 + boxSize, legendPaint)
            x += boxSize + 4f * density + legendPaint.measureText(label) + 10f * density
        }
    }
}
