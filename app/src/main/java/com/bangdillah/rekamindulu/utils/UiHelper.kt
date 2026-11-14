package com.bangdillah.rekamindulu.utils

import android.graphics.Rect
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Shared UI/UX helper utilities for consistent system-bar inset handling,
 * accessible minimum touch targets (48dp), and unified duration formatting.
 */
object UiHelper {

    private data class InitialPadding(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    private val initialPaddingMap = WeakHashMap<View, InitialPadding>()

    private fun captureInitialPadding(view: View): InitialPadding {
        return initialPaddingMap.getOrPut(view) {
            InitialPadding(
                left = view.paddingLeft,
                top = view.paddingTop,
                right = view.paddingRight,
                bottom = view.paddingBottom
            )
        }
    }

    /**
     * Applies edge-to-edge WindowInsets idempotently (safe across repeated inset dispatches).
     *
     * @param anchorView The root/anchor view receiving the OnApplyWindowInsetsListener.
     * @param topView View whose top padding should account for the status bar / display cutout.
     * @param bottomView View whose bottom padding should account for navigation bars (or IME).
     * @param horizontalView Optional view whose left/right padding should account for landscape bars.
     * @param includeImeBottom When true, bottom inset uses max(navigationBars, ime).
     */
    fun applySystemBarInsets(
        anchorView: View?,
        topView: View? = null,
        bottomView: View? = null,
        horizontalView: View? = null,
        includeImeBottom: Boolean = false
    ) {
        if (anchorView == null) return

        topView?.let { captureInitialPadding(it) }
        bottomView?.let { captureInitialPadding(it) }
        horizontalView?.let { captureInitialPadding(it) }

        ViewCompat.setOnApplyWindowInsetsListener(anchorView) { _, windowInsets ->
            val systemBars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val imeInsets = if (includeImeBottom) {
                windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            } else {
                androidx.core.graphics.Insets.NONE
            }
            val effectiveBottomInset = maxOf(systemBars.bottom, imeInsets.bottom)

            if (topView != null && topView === bottomView) {
                val base = captureInitialPadding(topView)
                topView.setPadding(
                    if (horizontalView === topView) base.left + systemBars.left else base.left,
                    base.top + systemBars.top,
                    if (horizontalView === topView) base.right + systemBars.right else base.right,
                    base.bottom + effectiveBottomInset
                )
            } else {
                topView?.let { target ->
                    val base = captureInitialPadding(target)
                    target.setPadding(
                        if (horizontalView === target) base.left + systemBars.left else base.left,
                        base.top + systemBars.top,
                        if (horizontalView === target) base.right + systemBars.right else base.right,
                        base.bottom
                    )
                }

                bottomView?.let { target ->
                    val base = captureInitialPadding(target)
                    target.setPadding(
                        if (horizontalView === target) base.left + systemBars.left else base.left,
                        base.top,
                        if (horizontalView === target) base.right + systemBars.right else base.right,
                        base.bottom + effectiveBottomInset
                    )
                }
            }

            if (horizontalView != null && horizontalView !== topView && horizontalView !== bottomView) {
                val base = captureInitialPadding(horizontalView)
                horizontalView.setPadding(
                    base.left + systemBars.left,
                    base.top,
                    base.right + systemBars.right,
                    base.bottom
                )
            }

            windowInsets
        }
        ViewCompat.requestApplyInsets(anchorView)
    }

    /**
     * Expands hit areas of one or more compact child views inside [parent] to at least [minSizeDp]
     * using a composite TouchDelegate without clipping visual layout.
     */
    fun expandTouchTargets(parent: View?, targets: List<Pair<View?, Int>>) {
        if (parent == null) return
        val validTargets = targets.mapNotNull { (child, dp) ->
            child?.let { it to dp }
        }
        if (validTargets.isEmpty()) return

        parent.post {
            val density = parent.resources.displayMetrics.density
            val composite = MultiTouchDelegate(parent)
            for ((child, minDp) in validTargets) {
                if (child.visibility != View.VISIBLE) continue
                val rect = Rect()
                child.getHitRect(rect)
                // Walk up in case child is nested inside an intermediate ViewGroup inside parent
                var currentParent = child.parent as? View
                while (currentParent != null && currentParent !== parent) {
                    rect.offset(currentParent.left, currentParent.top)
                    currentParent = currentParent.parent as? View
                }
                val minPx = (minDp * density).roundToInt()
                val extraW = ((minPx - rect.width()).coerceAtLeast(0)) / 2
                val extraH = ((minPx - rect.height()).coerceAtLeast(0)) / 2
                if (extraW > 0 || extraH > 0) {
                    rect.inset(-extraW, -extraH)
                }
                composite.addDelegate(TouchDelegate(rect, child))
            }
            parent.touchDelegate = composite
        }
    }

    /**
     * Formats milliseconds consistently as HH:mm:ss when >= 1 hour, or mm:ss when < 1 hour.
     */
    @JvmStatic
    fun formatDurationMs(ms: Long): String {
        val safeMs = ms.coerceAtLeast(0L)
        val totalSeconds = safeMs / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private class MultiTouchDelegate(boundView: View) : TouchDelegate(Rect(), boundView) {
        private val delegates = mutableListOf<TouchDelegate>()
        private var currentDelegate: TouchDelegate? = null

        fun addDelegate(delegate: TouchDelegate) {
            delegates.add(delegate)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            return when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    currentDelegate = null
                    for (delegate in delegates) {
                        val copy = MotionEvent.obtain(event)
                        val handled = delegate.onTouchEvent(copy)
                        copy.recycle()
                        if (handled) {
                            currentDelegate = delegate
                            return true
                        }
                    }
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    currentDelegate?.onTouchEvent(event) ?: false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val active = currentDelegate
                    currentDelegate = null
                    active?.onTouchEvent(event) ?: false
                }
                else -> currentDelegate?.onTouchEvent(event) ?: false
            }
        }
    }
}
