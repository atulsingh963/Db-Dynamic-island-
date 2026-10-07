package com.dynamicisland.app.overlay

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.view.*
import com.dynamicisland.app.model.IslandPrefs
import com.dynamicisland.app.model.IslandState

/**
 * IslandOverlayManager
 *
 * Responsible for:
 * 1. Adding / removing the [IslandView] to the system window layer.
 * 2. Translating user-configured X/Y preferences into WindowManager params.
 * 3. Exposing [setState] which delegates to [IslandView.transitionTo].
 *
 * The overlay uses FLAG_NOT_TOUCH_MODAL so touches outside the island
 * pass through to whatever app is below. FLAG_NOT_FOCUSABLE ensures the
 * overlay never steals keyboard focus.
 */
class IslandOverlayManager(private val context: Context) {

    val islandView: IslandView = IslandView(context)

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var isAttached = false

    // Current layout params – updated when prefs change
    private val params: WindowManager.LayoutParams by lazy { buildParams() }

    // ── Public API ────────────────────────────────────────────────────────────

    fun attach() {
        if (isAttached) return
        loadPrefsAndUpdatePosition()
        try {
            windowManager.addView(islandView, params)
            isAttached = true
        } catch (e: Exception) {
            // SYSTEM_ALERT_WINDOW permission not yet granted
        }
    }

    fun detach() {
        if (!isAttached) return
        try {
            windowManager.removeView(islandView)
        } catch (_: Exception) {}
        isAttached = false
    }

    fun setState(state: IslandState) {
        islandView.post { islandView.transitionTo(state) }
    }

    fun updatePosition(xOffsetPx: Int, yOffsetPx: Int) {
        params.x = xOffsetPx
        params.y = yOffsetPx
        if (isAttached) {
            windowManager.updateViewLayout(islandView, params)
        }
        // Recalculate draw centre for the canvas
        val displayMetrics = context.resources.displayMetrics
        islandView.setIslandCentre(
            displayMetrics.widthPixels / 2f + xOffsetPx,
            yOffsetPx.toFloat()
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun buildParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE      or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL    or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN    or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
    }

    private fun loadPrefsAndUpdatePosition() {
        val prefs: SharedPreferences =
            context.getSharedPreferences(IslandPrefs.PREFS_NAME, Context.MODE_PRIVATE)
        val x = prefs.getInt(IslandPrefs.KEY_X, 0)
        val y = prefs.getInt(IslandPrefs.KEY_Y, 0)
        updatePosition(x, y)
    }
}
