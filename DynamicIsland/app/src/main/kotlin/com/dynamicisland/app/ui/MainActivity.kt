package com.dynamicisland.app.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dynamicisland.app.R
import com.dynamicisland.app.model.IslandPrefs
import com.dynamicisland.app.model.IslandState
import com.dynamicisland.app.service.IslandAccessibilityService

/**
 * MainActivity – Island alignment & permission configuration UI.
 *
 * Uses [findViewById] instead of ViewBinding to avoid the dataBinding
 * Gradle instrumentation pipeline (keeps the build simple & fast).
 *
 * The user can:
 *  • Grant all required permissions (Overlay, Accessibility, Notification Listener)
 *  • Use X/Y sliders to offset the island pill over their device's camera hole
 *  • Preview each island state (Compact / Expanded / Minimal) in real-time
 *
 * X offset range: ±300px from screen centre.
 * Y offset range: 0–200px from the very top of the display.
 */
class MainActivity : AppCompatActivity() {

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(IslandPrefs.PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ── Views (lazy to avoid NPE before setContentView) ──────────────────────
    private val btnOverlayPermission:       Button   by lazy { findViewById(R.id.btnOverlayPermission) }
    private val btnAccessibilityPermission: Button   by lazy { findViewById(R.id.btnAccessibilityPermission) }
    private val btnNotificationPermission:  Button   by lazy { findViewById(R.id.btnNotificationPermission) }
    private val btnBatteryOptimization:     Button   by lazy { findViewById(R.id.btnBatteryOptimization) }
    private val statusOverlay:              TextView by lazy { findViewById(R.id.statusOverlay) }
    private val statusAccessibility:        TextView by lazy { findViewById(R.id.statusAccessibility) }
    private val statusNotif:                TextView by lazy { findViewById(R.id.statusNotif) }
    private val seekbarX:                   SeekBar  by lazy { findViewById(R.id.seekbarX) }
    private val seekbarY:                   SeekBar  by lazy { findViewById(R.id.seekbarY) }
    private val labelX:                     TextView by lazy { findViewById(R.id.labelX) }
    private val labelY:                     TextView by lazy { findViewById(R.id.labelY) }
    private val btnResetPosition:           Button   by lazy { findViewById(R.id.btnResetPosition) }
    private val btnPreviewCompact:          Button   by lazy { findViewById(R.id.btnPreviewCompact) }
    private val btnPreviewWaveform:         Button   by lazy { findViewById(R.id.btnPreviewWaveform) }
    private val btnPreviewExpanded:         Button   by lazy { findViewById(R.id.btnPreviewExpanded) }
    private val btnPreviewBattery:          Button   by lazy { findViewById(R.id.btnPreviewBattery) }
    private val btnPreviewTimer:            Button   by lazy { findViewById(R.id.btnPreviewTimer) }
    private val btnHide:                    Button   by lazy { findViewById(R.id.btnHide) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupPermissionButtons()
        setupSliders()
        setupPreviewButtons()
        updatePermissionStatus()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    // ── Permission setup ──────────────────────────────────────────────────────

    private fun setupPermissionButtons() {
        btnOverlayPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }

        btnAccessibilityPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnNotificationPermission.setOnClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }

        btnBatteryOptimization.setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun updatePermissionStatus() {
        val hasOverlay       = Settings.canDrawOverlays(this)
        val hasAccessibility = IslandAccessibilityService.instance != null
        val hasNotifListener = isNotificationListenerEnabled()

        statusOverlay.text       = if (hasOverlay)       "✓ Granted" else "✗ Required"
        statusAccessibility.text = if (hasAccessibility) "✓ Active"  else "✗ Enable in Settings"
        statusNotif.text         = if (hasNotifListener) "✓ Granted" else "✗ Required"

        statusOverlay.setTextColor(
            getColor(if (hasOverlay) R.color.status_ok else R.color.status_error))
        statusAccessibility.setTextColor(
            getColor(if (hasAccessibility) R.color.status_ok else R.color.status_error))
        statusNotif.setTextColor(
            getColor(if (hasNotifListener) R.color.status_ok else R.color.status_error))
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val listeners = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners") ?: return false
        return listeners.contains(packageName)
    }

    // ── Slider setup ──────────────────────────────────────────────────────────

    private fun setupSliders() {
        val savedX = prefs.getInt(IslandPrefs.KEY_X, 0)
        val savedY = prefs.getInt(IslandPrefs.KEY_Y, 0)

        // X: range -300 to +300  → SeekBar 0..600 (centre = 300)
        seekbarX.max      = 600
        seekbarX.progress = savedX + 300
        updateXLabel(savedX)

        // Y: range 0 to 200 px
        seekbarY.max      = 200
        seekbarY.progress = savedY
        updateYLabel(savedY)

        seekbarX.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val xOffset = progress - 300
                updateXLabel(xOffset)
                applyPosition(xOffset, seekbarY.progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                savePosition(sb.progress - 300, seekbarY.progress)
            }
        })

        seekbarY.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                updateYLabel(progress)
                applyPosition(seekbarX.progress - 300, progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                savePosition(seekbarX.progress - 300, sb.progress)
            }
        })

        btnResetPosition.setOnClickListener {
            seekbarX.progress = 300
            seekbarY.progress = 0
            savePosition(0, 0)
            applyPosition(0, 0)
        }
    }

    private fun applyPosition(xOffset: Int, yOffset: Int) {
        IslandAccessibilityService.instance?.updatePosition(xOffset, yOffset)
    }

    private fun savePosition(xOffset: Int, yOffset: Int) {
        prefs.edit()
            .putInt(IslandPrefs.KEY_X, xOffset)
            .putInt(IslandPrefs.KEY_Y, yOffset)
            .apply()
    }

    private fun updateXLabel(x: Int) { labelX.text = "Horizontal offset: ${x}px" }
    private fun updateYLabel(y: Int) { labelY.text = "Vertical offset (top): ${y}px" }

    // ── Preview buttons ───────────────────────────────────────────────────────

    private fun setupPreviewButtons() {
        btnPreviewCompact.setOnClickListener {
            IslandAccessibilityService.instance?.setState(
                IslandState.Compact(
                    trailingLabel = "LIVE",
                    trailingType  = IslandState.TrailingType.TEXT,
                    tintColor     = 0xFFFF3B30.toInt()
                )
            ) ?: showServiceWarning()
        }

        btnPreviewWaveform.setOnClickListener {
            IslandAccessibilityService.instance?.setState(
                IslandState.Compact(
                    trailingLabel = "",
                    trailingType  = IslandState.TrailingType.WAVEFORM,
                    tintColor     = 0xFF1DB954.toInt()
                )
            ) ?: showServiceWarning()
        }

        btnPreviewExpanded.setOnClickListener {
            IslandAccessibilityService.instance?.setState(
                IslandState.Expanded(
                    IslandState.ContentType.Media(
                        title     = "Preview Track",
                        artist    = "Demo Artist",
                        isPlaying = true,
                        progress  = 0.4f
                    )
                )
            ) ?: showServiceWarning()
        }

        btnPreviewBattery.setOnClickListener {
            IslandAccessibilityService.instance?.setState(
                IslandState.Expanded(IslandState.ContentType.Battery(72))
            ) ?: showServiceWarning()
        }

        btnPreviewTimer.setOnClickListener {
            IslandAccessibilityService.instance?.setState(
                IslandState.Compact(
                    trailingLabel = "12:34",
                    trailingType  = IslandState.TrailingType.TEXT,
                    tintColor     = 0xFFFF9500.toInt()
                )
            ) ?: showServiceWarning()
        }

        btnHide.setOnClickListener {
            IslandAccessibilityService.instance?.setState(IslandState.Hidden)
                ?: showServiceWarning()
        }
    }

    private fun showServiceWarning() {
        Toast.makeText(this,
            "Enable the Accessibility Service first!", Toast.LENGTH_SHORT).show()
    }
}
