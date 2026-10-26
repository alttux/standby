package dev.standby.cards

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs

/** A full-screen page of the vertical card stack. */
abstract class Card(ctx: Context) : FrameLayout(ctx) {
    abstract val key: String

    var isActiveCard = false
        private set

    fun setActiveCard(a: Boolean) {
        if (a == isActiveCard) return
        isActiveCard = a
        onActiveChanged(a)
        refresh()
    }

    protected open fun onActiveChanged(active: Boolean) {}

    /** Minute tick, settings change, theme change… */
    open fun refresh() = invalidateTree(this)

    open fun onCompanion(topic: String) {}
    open fun onWeather() {}

    private fun invalidateTree(v: View) {
        v.invalidate()
        if (v is ViewGroup) for (i in 0 until v.childCount) invalidateTree(v.getChildAt(i))
    }
}

/** Helper that recognises taps without stealing swipes from the pagers. */
class TapDetector(ctx: Context, private val onTap: (Float, Float) -> Boolean) {
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var x = 0f
    private var y = 0f
    private var t = 0L

    fun onTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x = e.x; y = e.y; t = e.eventTime; return true }
            MotionEvent.ACTION_UP -> {
                if (abs(e.x - x) < slop && abs(e.y - y) < slop && e.eventTime - t < 400) return onTap(e.x, e.y)
            }
        }
        return true
    }
}
