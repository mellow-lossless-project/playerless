package com.playerless

import android.view.View
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

private const val WIDTH_STEPS = 100

// The equalizer screen: the curve, the selected point's width, on and off, back to flat. Built the
// first time it's opened, like the full-screen player.
class EqScreen(view: View) {
    private val graph: EqView = view.findViewById(R.id.eqGraph)
    private val info: TextView = view.findViewById(R.id.eqInfo)
    private val width: SeekBar = view.findViewById(R.id.eqWidth)
    private val widthLabel: View = view.findViewById(R.id.eqWidthLabel)
    private val enabled: Switch = view.findViewById(R.id.eqEnabled)
    private val context = view.context

    init {
        val state = Equalizer.state
        graph.points = state.points
        graph.active = state.enabled
        enabled.isChecked = state.enabled
        graph.onChange = { done -> changed(done) }

        enabled.setOnCheckedChangeListener { _, on ->
            graph.active = on
            changed(true)
        }

        width.max = WIDTH_STEPS
        width.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                val i = graph.selected
                if (!fromUser || i < 0) return
                val q = Equalizer.qOf(value / WIDTH_STEPS.toDouble())
                graph.points = graph.points.toMutableList().also { it[i] = it[i].copy(q = q) }
                changed(false)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            override fun onStopTrackingTouch(bar: SeekBar) = changed(true)
        })

        view.findViewById<View>(R.id.eqReset).setOnClickListener {
            graph.points = Equalizer.DEFAULT_POINTS
            graph.selected = -1
            changed(true)
        }
        describe()
    }

    // Heard at once; written to disk only when a gesture ends.
    private fun changed(done: Boolean) {
        Equalizer.publish(Equalizer.State(enabled.isChecked, graph.points))
        if (done) Equalizer.save()
        describe()
    }

    private fun describe() {
        val point = graph.points.getOrNull(graph.selected)
        // The width belongs to a point: with none selected there's nothing to show.
        width.visibility = if (point == null) View.INVISIBLE else View.VISIBLE
        widthLabel.visibility = width.visibility
        val first = if (point == null) {
            context.getString(R.string.eq_hint)
        } else {
            width.progress = (Equalizer.widthOf(point.q) * WIDTH_STEPS).roundToInt()
            context.getString(R.string.eq_point, hz(point.hz), decibels(point.db), number(Equalizer.octaves(point.q)))
        }
        // Boosts cost loudness: say how much, so a quieter song isn't a mystery.
        val headroom = if (enabled.isChecked) Equalizer.headroomDb(graph.points) else 0.0
        info.text = if (headroom < 0.05) first else first + "\n" + context.getString(R.string.eq_headroom, decibels(-headroom))
    }

    private fun hz(hz: Double) =
        if (hz < 1_000) String.format(Lang.locale, "%.0f Hz", hz) else String.format(Lang.locale, "%.1f kHz", hz / 1_000)

    private fun decibels(db: Double) = String.format(Lang.locale, "%+.1f dB", db)

    private fun number(value: Double) = String.format(Lang.locale, "%.1f", value)
}
