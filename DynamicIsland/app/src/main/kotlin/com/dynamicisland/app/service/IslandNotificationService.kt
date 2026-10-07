package com.dynamicisland.app.service

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.SpannableString
import androidx.core.app.NotificationCompat
import com.dynamicisland.app.model.IslandState
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * IslandNotificationService – NotificationListenerService
 *
 * Responsibilities:
 *  - Intercept incoming notifications (WhatsApp, SMS, etc.)
 *  - Monitor active [MediaController] sessions for track info + seek position
 *  - Detect battery-charging broadcasts (forwarded via [BootReceiver])
 *  - Forward state changes to the [IslandAccessibilityService]
 *
 * Threading:
 *  - Notification callbacks arrive on the main thread.
 *  - Media polling runs on a scheduled executor thread and posts to main.
 */
class IslandNotificationService : NotificationListenerService() {

    companion object {
        @Volatile var instance: IslandNotificationService? = null
            private set

        // Package names that should trigger notification interception
        private val MESSAGING_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.google.android.apps.messaging",   // Google Messages
            "com.samsung.android.messaging",
            "org.telegram.messenger",
            "com.discord",
            "com.facebook.orca",                   // Messenger
            "com.instagram.android",
            "com.twitter.android"
        )
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private var currentSbn: StatusBarNotification? = null
    private var mediaController: MediaController? = null
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var mediaPoller: ScheduledFuture<*>? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        mediaPoller?.cancel(false)
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        startMediaPolling()
    }

    override fun onListenerDisconnected() {
        stopMediaPolling()
        super.onListenerDisconnected()
    }

    // ── Notification callbacks ────────────────────────────────────────────────

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName

        // Only intercept messaging packages
        if (pkg !in MESSAGING_PACKAGES) return

        // Ignore group summaries
        val extras = sbn.notification.extras
        val isGroup = sbn.notification.flags and
                android.app.Notification.FLAG_GROUP_SUMMARY != 0
        if (isGroup) return

        currentSbn = sbn

        val title   = extras.getString(NotificationCompat.EXTRA_TITLE)   ?: ""
        val text    = extras.getCharSequence(NotificationCompat.EXTRA_TEXT)
                          ?.toString() ?: ""
        val appName = packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(pkg, 0)).toString()
        val appIcon = try {
            val icon = packageManager.getApplicationIcon(pkg)
            (icon as? BitmapDrawable)?.bitmap ?: icon.toBitmap(48, 48)
        } catch (_: Exception) { null }

        // Build compact state first (peek)
        pushState(IslandState.Compact(
            leadingIcon   = appIcon,
            trailingLabel = title.take(12),
            trailingType  = IslandState.TrailingType.TEXT,
            tintColor     = 0xFF4CAF50.toInt()
        ))

        // After 1.5 s, auto-expand to show the full notification card
        mainHandler().postDelayed({
            pushState(IslandState.Expanded(
                IslandState.ContentType.Notification(
                    appIcon   = appIcon,
                    appName   = appName,
                    sender    = title,
                    body      = text,
                    replyAction = buildReplyAction(sbn)
                )
            ))
        }, 1500)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.key == currentSbn?.key) {
            currentSbn = null
            pushState(IslandState.Hidden)
        }
    }

    // ── Media polling ─────────────────────────────────────────────────────────

    private fun startMediaPolling() {
        val msm = getSystemService(MediaSessionManager::class.java)
        mediaPoller = executor.scheduleAtFixedRate({
            try {
                val controllers = msm.getActiveSessions(
                    ComponentName(this, IslandNotificationService::class.java)
                )
                val active = controllers.firstOrNull { ctrl ->
                    ctrl.playbackState?.state == PlaybackState.STATE_PLAYING ||
                    ctrl.playbackState?.state == PlaybackState.STATE_PAUSED
                }
                if (active != null && active != mediaController) {
                    mediaController = active
                }
                mediaController?.let { ctrl ->
                    val meta  = ctrl.metadata ?: return@let
                    val state = ctrl.playbackState
                    val isPlaying = state?.state == PlaybackState.STATE_PLAYING
                    val pos   = state?.position ?: 0L
                    val dur   = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)

                    val title  = meta.getString(MediaMetadata.METADATA_KEY_TITLE)  ?: "Unknown"
                    val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                    val art    = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    val prog   = if (dur > 0) pos.toFloat() / dur else 0f

                    mainHandler().post {
                        // Only update if no notification is active
                        if (currentSbn == null) {
                            pushState(IslandState.Compact(
                                leadingIcon   = art?.scale(48),
                                trailingLabel = "",
                                trailingType  = IslandState.TrailingType.WAVEFORM,
                                tintColor     = 0xFF1DB954.toInt()
                            ))
                        }
                        // Always update seekbar
                        IslandAccessibilityService.instance?.let { svc ->
                            svc.overlayManager.islandView.updateSeekProgress(prog)
                        }
                    }
                } ?: run {
                    // No active media
                    mainHandler().post {
                        if (currentSbn == null) pushState(IslandState.Hidden)
                    }
                }
            } catch (_: Exception) {}
        }, 0, 2, TimeUnit.SECONDS)
    }

    private fun stopMediaPolling() {
        mediaPoller?.cancel(false)
        mediaPoller = null
    }

    // ── Media control delegates ───────────────────────────────────────────────

    fun togglePlayPause() {
        val transport = mediaController?.transportControls ?: return
        val isPlaying = mediaController?.playbackState?.state == PlaybackState.STATE_PLAYING
        if (isPlaying) transport.pause() else transport.play()
    }

    fun previousTrack() { mediaController?.transportControls?.skipToPrevious() }
    fun nextTrack()     { mediaController?.transportControls?.skipToNext() }

    fun seekTo(progress: Float) {
        val dur = mediaController?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: return
        mediaController?.transportControls?.seekTo((dur * progress).toLong())
    }

    fun expandMedia() {
        val ctrl = mediaController ?: return
        val meta = ctrl.metadata ?: return
        val state = ctrl.playbackState
        pushState(IslandState.Expanded(
            IslandState.ContentType.Media(
                albumArt  = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.scale(120),
                title     = meta.getString(MediaMetadata.METADATA_KEY_TITLE)  ?: "Unknown",
                artist    = meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "",
                isPlaying = state?.state == PlaybackState.STATE_PLAYING,
                progress  = if ((meta.getLong(MediaMetadata.METADATA_KEY_DURATION)) > 0)
                    (state?.position ?: 0L).toFloat() /
                            meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
                else 0f,
                duration  = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
            )
        ))
    }

    // ── Notification actions ──────────────────────────────────────────────────

    fun expandCurrentNotification() {
        val sbn = currentSbn ?: return
        val extras = sbn.notification.extras
        val title   = extras.getString(NotificationCompat.EXTRA_TITLE) ?: ""
        val text    = extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString() ?: ""
        val pkg     = sbn.packageName
        val appName = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
        val appIcon: Bitmap? = try {
            val icon = packageManager.getApplicationIcon(pkg)
            (icon as? BitmapDrawable)?.bitmap ?: icon.toBitmap(48, 48)
        } catch (_: Exception) { null }

        pushState(IslandState.Expanded(
            IslandState.ContentType.Notification(
                appIcon = appIcon, appName = appName,
                sender = title, body = text,
                replyAction = buildReplyAction(sbn)
            )
        ))
    }

    fun dismissCurrentNotification() {
        currentSbn?.let { cancelNotification(it.key) }
        currentSbn = null
    }

    fun launchNotificationApp() {
        val intent = currentSbn?.notification?.contentIntent ?: return
        try {
            intent.send()
        } catch (_: Exception) {}
    }

    // ── Battery charging (called by BootReceiver) ─────────────────────────────

    fun onChargingChanged(percent: Int, isCharging: Boolean) {
        if (isCharging) {
            pushState(IslandState.Compact(
                leadingIcon   = null,
                trailingLabel = "$percent%",
                trailingType  = IslandState.TrailingType.TEXT,
                tintColor     = 0xFF4CAF50.toInt()
            ))
            // Auto-expand battery card
            mainHandler().postDelayed({
                pushState(IslandState.Expanded(IslandState.ContentType.Battery(percent)))
            }, 800)
            // Collapse after 5 s
            mainHandler().postDelayed({
                pushState(IslandState.Hidden)
            }, 5800)
        }
    }

    // ── Timer (called externally) ─────────────────────────────────────────────

    fun onTimerTick(remainingMs: Long) {
        val mediaActive = mediaController?.playbackState?.state == PlaybackState.STATE_PLAYING

        if (mediaActive) {
            // Split view: music pill + timer bubble
            val ctrl = mediaController!!
            val meta = ctrl.metadata
            pushState(IslandState.Minimal(
                primaryState = IslandState.Compact(
                    leadingIcon  = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.scale(48),
                    trailingType = IslandState.TrailingType.WAVEFORM,
                    tintColor    = 0xFF1DB954.toInt()
                ),
                secondaryTint = 0xFFFF9500.toInt()
            ))
        } else {
            // Plain timer compact
            val total = remainingMs
            val m = total / 60_000L
            val s = (total / 1_000L) % 60
            pushState(IslandState.Compact(
                trailingLabel = String.format("%02d:%02d", m, s),
                trailingType  = IslandState.TrailingType.TEXT,
                tintColor     = 0xFFFF9500.toInt()
            ))
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun pushState(state: IslandState) {
        IslandAccessibilityService.instance?.setState(state)
    }

    private fun mainHandler() =
        android.os.Handler(android.os.Looper.getMainLooper())

    private fun buildReplyAction(sbn: StatusBarNotification): String? {
        val actions = sbn.notification.actions ?: return null
        return actions.firstOrNull { it.remoteInputs?.isNotEmpty() == true }?.title?.toString()
    }

    private fun android.graphics.drawable.Drawable.toBitmap(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        setBounds(0, 0, w, h)
        draw(c)
        return bmp
    }

    private fun Bitmap.scale(sizePx: Int): Bitmap =
        Bitmap.createScaledBitmap(this, sizePx, sizePx, true)
}
