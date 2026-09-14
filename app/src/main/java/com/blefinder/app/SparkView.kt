package com.blefinder.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * 最近一段时间 RSSI 平滑值的走势折线图，纵轴固定 -100 ~ -35 dBm。
 */
class SparkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val lineColor = Color.parseColor("#4ADE80")

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#264ADE80")
        style = Paint.Style.FILL
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F8FAFC")
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF")
        strokeWidth = 1f
    }

    var values: List<Float> = emptyList()
        set(v) {
            field = v
            invalidate()
        }

    private val minRssi = -100f
    private val maxRssi = -35f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 8f

        // 三条参考线：-40 / -60 / -80 dBm
        listOf(-40f, -60f, -80f).forEach { level ->
            val ny = ((level - minRssi) / (maxRssi - minRssi)).coerceIn(0f, 1f)
            val y = h - pad - (h - 2 * pad) * ny
            canvas.drawLine(pad, y, w - pad, y, gridPaint)
        }

        val v = values
        if (v.size < 2) return

        val stepX = (w - 2 * pad) / (v.size - 1)
        fun yOf(rssi: Float): Float {
            val ny = ((rssi - minRssi) / (maxRssi - minRssi)).coerceIn(0f, 1f)
            return h - pad - (h - 2 * pad) * ny
        }

        val line = Path()
        val fill = Path()
        v.forEachIndexed { i, rssi ->
            val x = pad + stepX * i
            val y = yOf(rssi)
            if (i == 0) {
                line.moveTo(x, y)
                fill.moveTo(x, h - pad)
                fill.lineTo(x, y)
            } else {
                line.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo(w - pad, h - pad)
        fill.close()

        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(line, linePaint)

        val lastX = pad + stepX * (v.size - 1)
        canvas.drawCircle(lastX, yOf(v.last()), 5f, dotPaint)
    }
}
