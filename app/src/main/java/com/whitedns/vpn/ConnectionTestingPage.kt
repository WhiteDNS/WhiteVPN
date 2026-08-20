package com.whitedns.vpn

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.abs

/* Hallmark · macrostructure: Workbench · navigation: edge-aligned slide page · tone: technical, restrained */
/* Hallmark · pre-emit critique: P5 H5 E4 S5 R5 V4 · contrast: pass (40–41) · nav: N9 · footer: none · slop: pass */
internal class ConnectionTestingPage(context: Context) : FrameLayout(context) {
    var onSwipeBack: (() -> Unit)? = null
    private var downX = 0f
    private var downY = 0f

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }

            MotionEvent.ACTION_UP -> if (
                ConnectionTestingSwipePolicy.shouldClose(
                    distanceX = event.x - downX,
                    distanceY = event.y - downY,
                    pageWidth = width.toFloat(),
                    density = resources.displayMetrics.density,
                    isRtl = layoutDirection == LAYOUT_DIRECTION_RTL,
                )
            ) {
                MotionEvent.obtain(event).also { cancel ->
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
                post { onSwipeBack?.invoke() }
                return true
            }
        }
        return super.dispatchTouchEvent(event)
    }
}

internal object ConnectionTestingSwipePolicy {
    fun shouldClose(
        distanceX: Float,
        distanceY: Float,
        pageWidth: Float,
        density: Float,
        isRtl: Boolean = false,
    ): Boolean {
        val backDistance = if (isRtl) -distanceX else distanceX
        return backDistance >= maxOf(72f * density, pageWidth * 0.18f) &&
            backDistance >= abs(distanceY) * 1.25f
    }
}
