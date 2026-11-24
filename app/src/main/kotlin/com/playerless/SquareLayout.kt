package com.playerless

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.LinearLayout

// As tall as it is wide, and no larger than maxSide.
class SquareLayout(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    var maxSide = Int.MAX_VALUE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = if (maxSide < MeasureSpec.getSize(widthMeasureSpec)) {
            MeasureSpec.makeMeasureSpec(maxSide, MeasureSpec.EXACTLY)
        } else {
            widthMeasureSpec
        }
        super.onMeasure(spec, spec)
    }
}

// The full-screen player's column. Where the screen is too short for everything at full size, the
// cover gives up the height that's missing, so the buttons and the volume stay on screen. On a tall
// screen it measures once, as a plain column.
class PlayerColumn(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cover = findViewById<SquareLayout>(R.id.fullCoverBox)
        cover.maxSide = Int.MAX_VALUE
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) return
        var used = paddingTop + paddingBottom
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val params = child.layoutParams as LayoutParams
            used += child.measuredHeight + params.topMargin + params.bottomMargin
        }
        val missing = used - MeasureSpec.getSize(heightMeasureSpec)
        if (missing <= 0) return
        cover.maxSide = (cover.measuredHeight - missing).coerceAtLeast(0)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
