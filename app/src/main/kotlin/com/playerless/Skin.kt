package com.playerless

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt
import kotlin.random.Random

// A color of the current theme. Transparent, 0, means the theme doesn't have that thing.
fun Context.themeColor(attr: Int): Int {
    val value = TypedValue()
    if (!theme.resolveAttribute(attr, value, true)) return 0
    return if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) value.data else getColor(value.resourceId)
}

// What the theme an activity wears asks of the code that paints by hand, read once.
class Skin(private val context: Context) {
    val accent = context.themeColor(android.R.attr.colorAccent)
    val pageColor = context.themeColor(R.attr.pageColor)
    val panelTextLatest = context.themeColor(R.attr.panelTextLatest)
    val panelTextTitle = context.themeColor(R.attr.panelTextTitle)
    val systemBarColor = context.themeColor(R.attr.systemBarColor)
    val playerSystemBarColor = context.themeColor(R.attr.playerSystemBarColor)
    val ruleColor = context.themeColor(R.attr.ruleColor)
    private val grainColor = context.themeColor(R.attr.grainColor)
    private val gridColor = context.themeColor(R.attr.gridColor)
    private val printDark = context.themeColor(R.attr.printDark)
    private val printLight = context.themeColor(R.attr.printLight)

    // The two inks covers are printed in, or null where covers show as they are.
    val inks: Covers.Inks? = if (printLight != 0) Covers.Inks(printDark, printLight) else null

    // The page: its color, and in the poster themes grain and the paper's grid laid over it.
    fun page(): Drawable {
        val layers = ArrayList<Drawable>(3)
        layers += ColorDrawable(underGrain())
        if (grainColor != 0) layers += tiled(Grain.specks(grainColor))
        if (gridColor != 0) layers += tiled(Grain.grid(gridColor, (GRID_DP * context.resources.displayMetrics.density).toInt()))
        return if (layers.size == 1) layers[0] else LayerDrawable(layers.toTypedArray())
    }

    // The color under the grain, a shade off the page's so that with the grain on top it still
    // averages to the page's: the plain bars beside it then match. Specks cover a third of their
    // alpha on average (see Grain.specks).
    private fun underGrain(): Int {
        if (grainColor == 0) return pageColor
        val cover = Color.alpha(grainColor) / 3f / 255f
        fun channel(page: Int, speck: Int) = ((page - speck * cover) / (1 - cover)).roundToInt().coerceIn(0, 255)
        return Color.rgb(
            channel(Color.red(pageColor), Color.red(grainColor)),
            channel(Color.green(pageColor), Color.green(grainColor)),
            channel(Color.blue(pageColor), Color.blue(grainColor)),
        )
    }

    private fun tiled(bitmap: Bitmap) = BitmapDrawable(context.resources, bitmap).apply {
        setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private companion object {
        const val GRID_DP = 20
    }
}

// Small tiles, made once and repeated over the page: no image ships with the app for them.
object Grain {
    private const val TILE = 128
    private val made = HashMap<Long, Bitmap>() // Main thread only.

    // Specks of the color, each a random fraction of its alpha: mostly faint, a few strong.
    fun specks(color: Int): Bitmap = made.getOrPut(color.toLong()) {
        val random = Random(0x5EED)
        val maxAlpha = Color.alpha(color)
        val rgb = color and 0xFFFFFF
        val pixels = IntArray(TILE * TILE) {
            val v = random.nextFloat()
            ((maxAlpha * v * v).toInt() shl 24) or rgb
        }
        Bitmap.createBitmap(pixels, TILE, TILE, Bitmap.Config.ARGB_8888)
    }

    // One cell of graph paper: a line along its top and its left edge.
    fun grid(color: Int, cell: Int): Bitmap = made.getOrPut((cell.toLong() shl 32) or (color.toLong() and 0xFFFFFFFFL)) {
        val bitmap = Bitmap.createBitmap(cell, cell, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { this.color = color }
        canvas.drawRect(0f, 0f, cell.toFloat(), 1f, paint)
        canvas.drawRect(0f, 1f, 1f, cell.toFloat(), paint)
        bitmap
    }
}

// Printed rules under song and settings rows, in the poster themes.
class RuleDecoration(color: Int, private val inset: Float, private val thickness: Float) : RecyclerView.ItemDecoration() {
    private val paint = Paint().apply { this.color = color }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val right = parent.width - inset
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val type = parent.getChildViewHolder(child).itemViewType
            // Song rows are SongAdapter's only type, 0; settings rows are HomeAdapter's item_home.
            if (type != 0 && type != R.layout.item_home) continue
            val bottom = child.bottom + child.translationY
            canvas.drawRect(inset, bottom - thickness, right, bottom, paint)
        }
    }
}
