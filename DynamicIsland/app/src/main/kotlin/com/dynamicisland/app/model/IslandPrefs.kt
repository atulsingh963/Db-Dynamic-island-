package com.dynamicisland.app.model

/**
 * Persisted user preferences for overlay positioning.
 * Stored in SharedPreferences by MainActivity and read by IslandOverlayManager.
 */
data class IslandPrefs(
    /** Horizontal offset in pixels from the centre of the screen. Negative = left. */
    val xOffsetPx: Int = 0,
    /** Vertical offset in pixels from the top of the display. */
    val yOffsetPx: Int = 0
) {
    companion object {
        const val PREFS_NAME = "island_prefs"
        const val KEY_X = "x_offset"
        const val KEY_Y = "y_offset"
    }
}
