package com.dynamicisland.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.dynamicisland.app.model.IslandState
import com.dynamicisland.app.overlay.IslandOverlayManager

/**
 * IslandAccessibilityService
 *
 * This service has two jobs:
 *
 * 1. **Host the overlay** – It holds an [IslandOverlayManager] and attaches
 *    the [IslandView] to the system window layer. Using an AccessibilityService
 *    as the host means we inherit the service's long-lived process lifetime
 *    and we never steal touch focus from the foreground app.
 *
 * 2. **React to window changes** – When a new app window appears we can
 *    optionally collapse / update the island (e.g., dismiss on full-screen
 *    video playback).
 *
 * The overlay is the primary drawing surface; other services push state
 * changes via [IslandEventBus].
 */
class IslandAccessibilityService : AccessibilityService() {

    companion object {
        /** Singleton reference so other services can push state changes. */
        @Volatile var instance: IslandAccessibilityService? = null
            private set
    }

    internal lateinit var overlayManager: IslandOverlayManager

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlayManager = IslandOverlayManager(this)

        // Only attach if overlay permission is granted
        if (Settings.canDrawOverlays(this)) {
            overlayManager.attach()
            // Wire up interaction callbacks
            wireCallbacks()
        }

        // Also start the foreground keep-alive service
        startService(Intent(this, IslandForegroundService::class.java))
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        overlayManager.detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        overlayManager.detach()
        super.onDestroy()
    }

    override fun onInterrupt() { /* required by abstract class */ }

    // ── Accessibility events ──────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op for now; reserved for future window-change reactions
        // (e.g., collapsing island when a full-screen video launches)
    }

    // ── State management (called by other services) ───────────────────────────

    fun setState(state: IslandState) {
        overlayManager.setState(state)
    }

    fun updatePosition(xPx: Int, yPx: Int) {
        overlayManager.updatePosition(xPx, yPx)
    }

    // ── Interaction callbacks ─────────────────────────────────────────────────

    private fun wireCallbacks() {
        val view = overlayManager.islandView

        view.onSingleTap = {
            // Open the source app for the current notification state
            val state = currentState()
            if (state is IslandState.Expanded &&
                state.contentType is IslandState.ContentType.Notification) {
                // Delegate to notification service to launch the app
                IslandNotificationService.instance?.launchNotificationApp()
            }
        }

        view.onLongPress = {
            // Morph to expanded view
            val current = currentState()
            if (current is IslandState.Compact) {
                IslandNotificationService.instance?.expandCurrentNotification()
                    ?: run {
                        // No notification – expand last media session
                        IslandNotificationService.instance?.expandMedia()
                    }
            } else if (current is IslandState.Expanded) {
                setState(IslandState.Hidden)
            }
        }

        view.onSwipeDismiss = {
            IslandNotificationService.instance?.dismissCurrentNotification()
            setState(IslandState.Hidden)
        }

        view.onPlayPause = {
            IslandNotificationService.instance?.togglePlayPause()
        }

        view.onPrevious = {
            IslandNotificationService.instance?.previousTrack()
        }

        view.onNext = {
            IslandNotificationService.instance?.nextTrack()
        }

        view.onSeekChanged = { progress ->
            IslandNotificationService.instance?.seekTo(progress)
        }
    }

    private fun currentState(): IslandState =
        overlayManager.islandView.let {
            // Reflection-free: we keep a reference in the view
            IslandState.Hidden  // placeholder; real state tracked in IslandView
        }
}
