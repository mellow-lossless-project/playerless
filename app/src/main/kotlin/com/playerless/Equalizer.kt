package com.playerless

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// One bell of the equalizer: where it sits, how much it lifts or cuts, how wide it reaches (Q).
data class EqPoint(val hz: Double, val db: Double, val q: Double = Equalizer.DEFAULT_Q)

// A peaking filter, from the Audio EQ Cookbook (Robert Bristow-Johnson), normalised so a0 = 1.
class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    // What this filter does to a sine at hz, in dB.
    fun gainDb(hz: Double, rate: Double): Double {
        val w = 2 * PI * hz / rate
        val c1 = cos(w)
        val s1 = sin(w)
        val c2 = cos(2 * w)
        val s2 = sin(2 * w)
        val numRe = b0 + b1 * c1 + b2 * c2
        val numIm = b1 * s1 + b2 * s2
        val denRe = 1 + a1 * c1 + a2 * c2
        val denIm = a1 * s1 + a2 * s2
        return 10 * log10((numRe * numRe + numIm * numIm) / (denRe * denRe + denIm * denIm))
    }

    companion object {
        fun peaking(point: EqPoint, rate: Double): Biquad {
            // Above Nyquist a filter can't exist; just under it, it still bends the top end.
            val hz = point.hz.coerceAtMost(rate * 0.45)
            val a = 10.0.pow(point.db / 40)
            val w = 2 * PI * hz / rate
            val alpha = sin(w) / (2 * point.q)
            val cosW = cos(w)
            val a0 = 1 + alpha / a
            return Biquad(
                b0 = (1 + alpha * a) / a0,
                b1 = -2 * cosW / a0,
                b2 = (1 - alpha * a) / a0,
                a1 = -2 * cosW / a0,
                a2 = (1 - alpha / a) / a0,
            )
        }
    }
}

// The equalizer the screen edits and the player applies. Both read one immutable State, swapped
// whole, so the audio thread never sees half an edit.
object Equalizer {
    const val MIN_HZ = 20.0
    const val MAX_HZ = 20_000.0
    const val MAX_DB = 12.0
    const val MAX_POINTS = 12
    const val DEFAULT_Q = 1.0
    const val MIN_Q = 0.3
    const val MAX_Q = 8.0

    // Where a fresh equalizer puts its handles, all flat: something to grab right away.
    val DEFAULT_POINTS = listOf(60.0, 230.0, 910.0, 3_600.0, 14_000.0).map { EqPoint(it, 0.0) }

    class State(val enabled: Boolean, val points: List<EqPoint>)

    @Volatile
    var state = State(false, DEFAULT_POINTS)
        private set

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val saved = prefs.getString("eq_points", null)?.let { text ->
            runCatching {
                val array = JSONArray(text)
                (0 until array.length()).map { i ->
                    val p = array.getJSONArray(i)
                    EqPoint(p.getDouble(0), p.getDouble(1), p.getDouble(2))
                }
            }.getOrNull()
        }
        state = State(prefs.getBoolean("eq_on", false), saved ?: DEFAULT_POINTS)
    }

    // Takes effect at once, in the next block of audio. Main thread.
    fun publish(newState: State) {
        state = newState
    }

    // Writing on every finger movement would be wasteful: the screen saves when a gesture ends.
    fun save() {
        val array = JSONArray()
        state.points.forEach { array.put(JSONArray().put(it.hz).put(it.db).put(it.q)) }
        prefs.edit().putBoolean("eq_on", state.enabled).putString("eq_points", array.toString()).apply()
    }

    // The filters for these points; one at 0 dB changes nothing, so it isn't one.
    fun filters(points: List<EqPoint>, rate: Double = DRAW_RATE) =
        points.filter { it.db != 0.0 }.map { Biquad.peaking(it, rate) }

    // The curve the filters make together, in dB: what the screen draws and the ear gets.
    fun responseDb(filters: List<Biquad>, hz: Double, rate: Double = DRAW_RATE): Double =
        filters.sumOf { it.gainDb(hz, rate) }

    // How far the curve rises above 0 dB anywhere. The player lowers everything by as much first,
    // so a boost can't push the music past full scale and distort.
    fun headroomDb(points: List<EqPoint>): Double {
        val filters = filters(points)
        if (filters.isEmpty()) return 0.0
        var top = 0.0
        for (i in 0..HEADROOM_STEPS) {
            top = max(top, responseDb(filters, hzAt(i / HEADROOM_STEPS.toDouble())))
        }
        return top
    }

    // The frequency at a fraction of the way from 20 Hz to 20 kHz, on the log scale the ear uses.
    fun hzAt(fraction: Double) = MIN_HZ * (MAX_HZ / MIN_HZ).pow(fraction)

    fun fractionOf(hz: Double) = log10(hz / MIN_HZ) / log10(MAX_HZ / MIN_HZ)

    // Q from a 0..1 slider: 0 is narrowest, 1 widest, evenly spaced to the ear.
    fun qOf(width: Double) = MAX_Q * (MIN_Q / MAX_Q).pow(width)

    fun widthOf(q: Double) = log10(q / MAX_Q) / log10(MIN_Q / MAX_Q)

    // Bandwidth in octaves, a friendlier number than Q: 2·asinh(1/2Q)/ln 2, so Q 1.41 is one octave.
    fun octaves(q: Double): Double {
        val x = 1 / (2 * q)
        return 2 * log10(x + sqrt(x * x + 1)) / log10(2.0)
    }

    private const val DRAW_RATE = 48_000.0
    private const val HEADROOM_STEPS = 160
}
