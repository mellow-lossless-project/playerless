package com.playerless

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

// The poster themes print covers the way a photocopier prints a photo: in two inks, no greys. The
// tones are stretched to fill the range, grain is added, and each dot comes out dark or light, so
// shadows stay solid and the edges between them turn grainy. Off the main thread: a 500x500 cover
// takes a few milliseconds.
object Print {
    // How far the grain moves a dot, out of 255: enough for grainy edges, not a noisy picture.
    private const val GRAIN = 40

    // The darkest and lightest 2% don't count when stretching, so a stray highlight can't flatten it.
    private const val CLIP_DIVISOR = 50

    fun apply(source: Bitmap, dark: Int, light: Int): Bitmap {
        val width = source.width
        val height = source.height
        val count = width * height
        val pixels = IntArray(count)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        // Brightness of each dot, kept in the same array, and how many dots have each brightness.
        val histogram = IntArray(256)
        for (i in 0 until count) {
            val p = pixels[i]
            val luma = ((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
            pixels[i] = luma
            histogram[luma]++
        }
        var low = 0
        var seen = 0
        while (low < 255) {
            seen += histogram[low]
            if (seen >= count / CLIP_DIVISOR) break
            low++
        }
        var high = 255
        seen = 0
        while (high > 0) {
            seen += histogram[high]
            if (seen >= count / CLIP_DIVISOR) break
            high--
        }
        // A nearly flat picture isn't blown up into pure noise.
        if (high - low < 32) {
            val mid = (high + low) / 2
            low = max(0, mid - 16)
            high = min(255, mid + 16)
        }
        val range = high - low

        // The same grain every time for the same cover: xorshift from a fixed seed.
        var seed = 0x2545F491
        for (i in 0 until count) {
            val tone = (pixels[i] - low) * 255 / range
            seed = seed xor (seed shl 13)
            seed = seed xor (seed ushr 17)
            seed = seed xor (seed shl 5)
            val grain = (seed ushr 1) % (2 * GRAIN + 1) - GRAIN
            pixels[i] = if (tone + grain >= 128) light else dark
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.RGB_565)
    }
}
