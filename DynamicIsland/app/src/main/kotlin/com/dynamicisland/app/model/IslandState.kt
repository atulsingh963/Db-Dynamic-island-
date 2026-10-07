package com.dynamicisland.app.model

import android.graphics.Bitmap

/**
 * Sealed hierarchy representing every possible state of the Dynamic Island.
 * The rendering engine (IslandView) reacts to state transitions and animates
 * between them using spring physics.
 */
sealed class IslandState {

    /** Island is invisible – collapsed into camera cutout bounds. */
    object Hidden : IslandState()

    /**
     * Standard pill showing a leading icon + trailing content.
     * @param leadingIcon   24dp icon drawn on the left
     * @param trailingLabel Short text or waveform tag on the right ("LIVE", timer string…)
     * @param trailingType  Whether the right side is [TrailingType.TEXT] or [TrailingType.WAVEFORM]
     * @param tintColor     Accent colour for the trailing element
     */
    data class Compact(
        val leadingIcon: Bitmap? = null,
        val trailingLabel: String = "",
        val trailingType: TrailingType = TrailingType.TEXT,
        val tintColor: Int = 0xFF1DB954.toInt()   // Spotify green default
    ) : IslandState()

    /**
     * Expanded card – shown on long-press.
     * @param contentType What to render inside the expanded card
     */
    data class Expanded(
        val contentType: ContentType
    ) : IslandState()

    /**
     * Split / Minimal view: two concurrent activities.
     * The main pill stays over the camera; a secondary bubble detaches to the right.
     * @param primaryState  State for the main pill (left / camera)
     * @param secondaryIcon 24dp icon for the detached bubble (right)
     */
    data class Minimal(
        val primaryState: Compact,
        val secondaryIcon: Bitmap? = null,
        val secondaryTint: Int = 0xFFFF9500.toInt()   // orange default (timer)
    ) : IslandState()

    // ── Nested types ──────────────────────────────────────────────────────────

    enum class TrailingType { TEXT, WAVEFORM }

    sealed class ContentType {
        /**
         * Media player card.
         * @param albumArt    Optional album art bitmap
         * @param title       Track title
         * @param artist      Artist name
         * @param isPlaying   Playback state
         * @param progress    0f–1f seek position
         * @param duration    Total duration ms (0 = unknown)
         */
        data class Media(
            val albumArt: Bitmap? = null,
            val title: String = "Unknown",
            val artist: String = "",
            val isPlaying: Boolean = false,
            val progress: Float = 0f,
            val duration: Long = 0L
        ) : ContentType()

        /**
         * Notification quick-reply card.
         * @param appIcon   App icon bitmap
         * @param appName   App label
         * @param sender    Contact / sender name
         * @param body      Message body
         * @param replyAction Pending intent action key for inline-reply
         */
        data class Notification(
            val appIcon: Bitmap? = null,
            val appName: String = "",
            val sender: String = "",
            val body: String = "",
            val replyAction: String? = null
        ) : ContentType()

        /**
         * Battery charging overlay.
         * @param percent 0–100
         */
        data class Battery(val percent: Int) : ContentType()

        /**
         * Timer countdown.
         * @param remainingMs Milliseconds remaining
         */
        data class Timer(val remainingMs: Long) : ContentType()
    }
}
