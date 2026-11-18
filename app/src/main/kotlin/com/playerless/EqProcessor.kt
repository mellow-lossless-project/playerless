package com.playerless

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt

// Runs the equalizer on the decoded audio, inside the player: one peaking filter per point, in
// series, on 16-bit PCM. Always in the chain, so switching it on needs no restart; when off or
// flat it only copies.
@OptIn(UnstableApi::class)
class EqProcessor : BaseAudioProcessor() {
    // The state the filters were built for; a new one is picked up at the next block.
    private var builtFor: Equalizer.State? = null
    private var coefficients = DoubleArray(0) // b0 b1 b2 a1 a2 per filter
    private var filters = 0
    private var gain = 1.0
    // Filter memory, per filter and channel.
    private var z1 = DoubleArray(0)
    private var z2 = DoubleArray(0)

    // Anything but 16-bit PCM passes by untouched; the sink converts to it before us anyway.
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val input = inputBuffer.slice().order(ByteOrder.nativeOrder())
        inputBuffer.position(inputBuffer.limit())
        val output = replaceOutputBuffer(size)

        val state = Equalizer.state
        if (state !== builtFor) build(state)
        if (filters == 0) {
            output.put(input)
            output.flip()
            return
        }

        val channels = inputAudioFormat.channelCount
        val c = coefficients
        val samples = size / 2
        var channel = 0
        for (i in 0 until samples) {
            var x = input.getShort(i * 2) * gain
            var k = channel
            var f = 0
            while (f < filters) {
                val base = f * 5
                val y = c[base] * x + z1[k]
                z1[k] = c[base + 1] * x - c[base + 3] * y + z2[k]
                z2[k] = c[base + 2] * x - c[base + 4] * y
                x = y
                k += channels
                f++
            }
            output.putShort(x.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            channel = if (channel + 1 == channels) 0 else channel + 1
        }
        output.flip()
    }

    // A seek or a new format: start from silence, and rebuild for the format's sample rate.
    override fun onFlush() {
        builtFor = null
        z1.fill(0.0)
        z2.fill(0.0)
    }

    override fun onReset() {
        builtFor = null
        filters = 0
    }

    private fun build(state: Equalizer.State) {
        builtFor = state
        val points = if (state.enabled) state.points else emptyList()
        val rate = inputAudioFormat.sampleRate.toDouble()
        val channels = inputAudioFormat.channelCount
        val built = Equalizer.filters(points, rate)
        val count = built.size
        coefficients = DoubleArray(count * 5)
        built.forEachIndexed { i, b ->
            coefficients[i * 5] = b.b0
            coefficients[i * 5 + 1] = b.b1
            coefficients[i * 5 + 2] = b.b2
            coefficients[i * 5 + 3] = b.a1
            coefficients[i * 5 + 4] = b.a2
        }
        // Moving a point keeps the memory, so dragging doesn't click; adding or removing one resets it.
        if (count != filters || z1.size != count * channels) {
            z1 = DoubleArray(count * channels)
            z2 = DoubleArray(count * channels)
        }
        filters = count
        gain = 10.0.pow(-Equalizer.headroomDb(points) / 20)
    }
}
