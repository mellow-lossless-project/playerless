package com.playerless

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot

private val GRID_HZ = doubleArrayOf(50.0, 100.0, 200.0, 500.0, 1_000.0, 2_000.0, 5_000.0, 10_000.0)
private val LABELS = listOf(20.0 to "20", 100.0 to "100", 1_000.0 to "1k", 10_000.0 to "10k", 20_000.0 to "20k")
private val GRID_DB = doubleArrayOf(-12.0, -6.0, 6.0, 12.0)
// Within this of 0 dB a point lands on it exactly, so flattening one is easy.
private const val SNAP_DB = 0.3

// The equalizer as a curve: frequency across, 20 Hz to 20 kHz on the ear's log scale, gain up
// and down. Tap empty space to add a point, drag one to move it, double-tap one to remove it.
class EqView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var points: List<EqPoint> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    // Off, the curve stays but goes grey.
    var active = true
        set(value) {
            field = value
            invalidate()
        }

    var selected = -1

    // On every change while a finger moves; done when the gesture is over and worth saving.
    var onChange: ((done: Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    // The graph is a panel, like the loading log: its colors come from the theme.
    private val accent = context.themeColor(android.R.attr.colorAccent)
    private val background = context.themeColor(R.attr.panelColor)
    private val offColor = context.themeColor(R.attr.panelText)
    private val gridPaint = Paint().apply { color = context.themeColor(R.attr.eqGrid); strokeWidth = density }
    private val zeroPaint = Paint().apply { color = context.themeColor(R.attr.eqZero); strokeWidth = density }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.panelText)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = context.themeColor(R.attr.panelTextTitle)
    }
    private val curve = Path()
    private val fill = Path()

    private val side = 12 * density
    private val labelSpace = 18 * density
    private val pointRadius = 7 * density
    private val hitRadius = 28 * density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private var dragging = -1
    private var moved = false
    private var downX = 0f
    private var downY = 0f

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        // Confirmed, so the first tap of a double-tap doesn't add a point.
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (pointAt(e.x, e.y) < 0 && points.size < Equalizer.MAX_POINTS) {
                points = points + EqPoint(hzOf(e.x), dbOf(e.y))
                selected = points.lastIndex
                onChange?.invoke(true)
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val hit = pointAt(e.x, e.y)
            if (hit >= 0) {
                points = points.filterIndexed { i, _ -> i != hit }
                selected = -1
                dragging = -1
                onChange?.invoke(true)
            }
            return true
        }
    })

    private val plotLeft get() = side
    private val plotRight get() = width - side
    private val plotTop get() = side
    private val plotBottom get() = height - labelSpace

    private fun xOf(hz: Double) = (plotLeft + Equalizer.fractionOf(hz) * (plotRight - plotLeft)).toFloat()

    private fun hzOf(x: Float) =
        Equalizer.hzAt(((x - plotLeft) / (plotRight - plotLeft)).toDouble().coerceIn(0.0, 1.0))

    private fun yOf(db: Double) =
        (plotTop + (Equalizer.MAX_DB - db) / (2 * Equalizer.MAX_DB) * (plotBottom - plotTop)).toFloat()

    private fun dbOf(y: Float): Double {
        val db = Equalizer.MAX_DB - (y - plotTop) / (plotBottom - plotTop) * 2 * Equalizer.MAX_DB
        return if (abs(db) < SNAP_DB) 0.0 else db.coerceIn(-Equalizer.MAX_DB, Equalizer.MAX_DB)
    }

    private fun pointAt(x: Float, y: Float): Int {
        var best = -1
        var bestDistance = hitRadius
        points.forEachIndexed { i, p ->
            val distance = hypot(x - xOf(p.hz), y - yOf(p.db))
            if (distance < bestDistance) {
                best = i
                bestDistance = distance
            }
        }
        return best
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(background)
        for (hz in GRID_HZ) canvas.drawLine(xOf(hz), plotTop, xOf(hz), plotBottom, gridPaint)
        for (db in GRID_DB) canvas.drawLine(plotLeft, yOf(db), plotRight, yOf(db), gridPaint)
        val zero = yOf(0.0)
        canvas.drawLine(plotLeft, zero, plotRight, zero, zeroPaint)

        val baseline = height - 5 * density
        for ((hz, label) in LABELS) {
            val textWidth = labelPaint.measureText(label)
            val x = (xOf(hz) - textWidth / 2).coerceIn(0f, width - textWidth)
            canvas.drawText(label, x, baseline, labelPaint)
        }
        for (db in GRID_DB) {
            val label = if (db > 0) "+${db.toInt()}" else "${db.toInt()}"
            canvas.drawText(label, plotLeft + 3 * density, yOf(db) - 3 * density, labelPaint)
        }

        // The real response of the filters, sampled every couple of pixels.
        val color = if (active) accent else offColor
        val filters = Equalizer.filters(points)
        val steps = ((plotRight - plotLeft) / (2 * density)).toInt().coerceAtLeast(2)
        curve.rewind()
        for (i in 0..steps) {
            val x = plotLeft + (plotRight - plotLeft) * i / steps
            val db = Equalizer.responseDb(filters, hzOf(x)).coerceIn(-Equalizer.MAX_DB, Equalizer.MAX_DB)
            if (i == 0) curve.moveTo(x, yOf(db)) else curve.lineTo(x, yOf(db))
        }
        fill.set(curve)
        fill.lineTo(plotRight, zero)
        fill.lineTo(plotLeft, zero)
        fill.close()
        fillPaint.color = color and 0x00FFFFFF or 0x38000000
        canvas.drawPath(fill, fillPaint)
        curvePaint.color = color
        canvas.drawPath(curve, curvePaint)

        pointPaint.color = color
        points.forEachIndexed { i, p ->
            canvas.drawCircle(xOf(p.hz), yOf(p.db), pointRadius, pointPaint)
            if (i == selected) canvas.drawCircle(xOf(p.hz), yOf(p.db), pointRadius + 4 * density, ringPaint)
        }
    }

    // A tap is a click too, for accessibility services.
    override fun performClick() = super.performClick()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = pointAt(event.x, event.y)
                moved = false
                downX = event.x
                downY = event.y
                if (dragging >= 0) {
                    selected = dragging
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    onChange?.invoke(false)
                }
            }
            MotionEvent.ACTION_MOVE -> if (dragging >= 0) {
                // A tap shouldn't nudge the point it lands on.
                if (!moved && hypot(event.x - downX, event.y - downY) < slop) return true
                moved = true
                val moving = points[dragging].copy(hz = hzOf(event.x), db = dbOf(event.y))
                points = points.toMutableList().also { it[dragging] = moving }
                onChange?.invoke(false)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val tap = !moved && hypot(event.x - downX, event.y - downY) < slop
                if (event.actionMasked == MotionEvent.ACTION_UP && tap) performClick()
                if (dragging >= 0) {
                    dragging = -1
                    onChange?.invoke(true)
                }
            }
        }
        return true
    }
}
