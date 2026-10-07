package com.dynamicisland.app.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.TextUtils
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.dynamicisland.app.model.IslandState
import kotlin.math.*

/**
 * IslandView – the single custom View that renders every Dynamic Island state.
 *
 * ── Rendering strategy ───────────────────────────────────────────────────────
 *  • All drawing is done on Canvas via onDraw(); no XML layouts.
 *  • Spring animations drive width / height / cornerRadius floats, which are
 *    updated on each animation frame via addUpdateListener → invalidate().
 *  • The metaball "liquid split" effect uses a PorterDuff layer + blur +
 *    threshold colour-matrix filter (the classic GPU-free metaball trick).
 *  • RenderEffect (API 31+) is applied to the view itself for the blur layer.
 *
 * ── Coordinate system ────────────────────────────────────────────────────────
 *  The view is sized to MATCH_PARENT in both axes by the overlay manager so
 *  that touch events can be dispatched anywhere on screen. The "island" itself
 *  is painted at (centreX, topY) which are controlled by the overlay's
 *  WindowManager.LayoutParams x/y values.
 *
 * ── Spring parameters ────────────────────────────────────────────────────────
 *  Stiffness  : 400 (snappy, like iOS spring)
 *  Damping    : 0.75f (slight overshoot / bounce)
 */
@SuppressLint("ClickableViewAccessibility")
class IslandView(context: Context) : View(context) {

    // ── Public callbacks ──────────────────────────────────────────────────────
    var onSingleTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null
    var onSwipeDismiss: (() -> Unit)? = null
    var onSeekChanged: ((Float) -> Unit)? = null
    var onPlayPause: (() -> Unit)? = null
    var onPrevious: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onReply: ((String) -> Unit)? = null

    // ── Dimension constants (dp/sp → px resolved in init) ────────────────────
    private val dp: Float = context.resources.displayMetrics.density
    private val sp: Float = context.resources.displayMetrics.scaledDensity

    private val BASE_HEIGHT     = 36f * dp
    private val ICON_SIZE       = 24f * dp
    private val TEXT_SIZE_SMALL = 15f * sp
    private val TEXT_SIZE_MED   = 13f * sp
    private val CORNER_COMPACT  = 18f * dp   // half of BASE_HEIGHT → full pill
    private val CORNER_EXPANDED = 26f * dp
    private val EXPANDED_W      = 340f * dp
    private val EXPANDED_H      = 158f * dp
    private val COMPACT_W_MIN   = 120f * dp
    private val BUBBLE_RADIUS   = 20f * dp
    private val PADDING         = 12f * dp
    private val SEEKBAR_H       = 4f * dp
    private val WAVEFORM_BARS   = 5

    // ── Animated properties (driven by SpringAnimation) ───────────────────────
    private var animWidth       = COMPACT_W_MIN
    private var animHeight      = BASE_HEIGHT
    private var animCorner      = CORNER_COMPACT
    private var animAlpha       = 1f
    private var animBubbleX     = 0f   // bubble separation offset (Minimal state)
    private var animBubbleAlpha = 0f

    // ── Spring animators ─────────────────────────────────────────────────────
    private val springWidth  = makeSpring(DynamicAnimation.TRANSLATION_X, 400f, 0.75f)
    private val springHeight = makeSpring(DynamicAnimation.TRANSLATION_Y, 400f, 0.75f)

    // Width/height are driven by ValueAnimator (spring-like) for custom props
    private var widthAnim:  ValueAnimator? = null
    private var heightAnim: ValueAnimator? = null
    private var cornerAnim: ValueAnimator? = null

    // ── State ─────────────────────────────────────────────────────────────────
    private var currentState: IslandState = IslandState.Hidden
    private var wavePhase = 0f          // for live waveform animation
    private var waveAnimator: ValueAnimator? = null

    // ── Paint objects (recycled across frames) ────────────────────────────────
    private val islandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val textPaintLg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TEXT_SIZE_SMALL
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val textPaintSm = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TEXT_SIZE_MED
        alpha = 180
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1DB954.toInt()
        style = Paint.Style.FILL
    }
    private val seekBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF444444.toInt()
        style = Paint.Style.FILL
    }
    private val seekFgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
    }
    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Metaball blur layer (reused)
    private var metaballBitmap: Bitmap? = null
    private var metaballCanvas: Canvas? = null
    private val metaballBlur: BlurMaskFilter = BlurMaskFilter(18f * dp, BlurMaskFilter.Blur.NORMAL)
    private val metaballThreshold: ColorMatrixColorFilter by lazy {
        val cm = ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 18f, -7f * 255f   // threshold: alpha > ~0.39 → solid
        ))
        ColorMatrixColorFilter(cm)
    }

    // ── Gesture detector ─────────────────────────────────────────────────────
    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isInsideIsland(e.x, e.y)) {
                    haptic(VibrationEffect.EFFECT_CLICK)
                    handleTap(e.x, e.y)
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (isInsideIsland(e.x, e.y)) {
                    haptic(VibrationEffect.EFFECT_HEAVY_CLICK)
                    onLongPress?.invoke()
                }
            }

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent,
                vX: Float, vY: Float
            ): Boolean {
                if (e1 != null && isInsideIsland(e1.x, e1.y)) {
                    val dx = e2.x - e1.x
                    if (abs(dx) > 80f && abs(vX) > 200f) {
                        haptic(VibrationEffect.EFFECT_TICK)
                        onSwipeDismiss?.invoke()
                        return true
                    }
                }
                return false
            }

            override fun onDown(e: MotionEvent) = true
        }
    )

    // ── Seekbar interaction ───────────────────────────────────────────────────
    private var isSeeking = false
    private var seekProgress = 0f

    // ── Centre position (pixels from left/top of view) ───────────────────────
    private var centreX = 0f
    private var topY    = 0f

    init {
        // The view itself must pass touches through to windows below
        // except where the island is drawn.
        setLayerType(LAYER_TYPE_SOFTWARE, null)  // required for BlurMaskFilter metaball
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ══════════════════════════════════════════════════════════════════════════

    /** Call this whenever the draw-centre changes (from WindowManager position). */
    fun setIslandCentre(cx: Float, cy: Float) {
        centreX = cx
        topY    = cy
        invalidate()
    }

    /** Transition to a new state with spring-animated morphing. */
    fun transitionTo(state: IslandState) {
        val prev = currentState
        currentState = state
        applyStateAnimations(prev, state)
    }

    /** Update seek progress during active media playback (0f–1f). */
    fun updateSeekProgress(progress: Float) {
        if (!isSeeking) {
            seekProgress = progress
            if (currentState is IslandState.Expanded &&
                (currentState as IslandState.Expanded).contentType is IslandState.ContentType.Media) {
                invalidate()
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TOUCH DISPATCH — pass-through except over the island shape
    // ══════════════════════════════════════════════════════════════════════════

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Pass event to gesture detector first
        gestureDetector.onTouchEvent(event)

        // Handle seekbar drag independently
        if (currentState is IslandState.Expanded) {
            handleSeekbarTouch(event)
        }

        // Only consume if touch is inside the island bounding rect
        return isInsideIsland(event.x, event.y)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DRAWING
    // ══════════════════════════════════════════════════════════════════════════

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (val s = currentState) {
            is IslandState.Hidden   -> Unit   // nothing
            is IslandState.Compact  -> drawCompact(canvas, s)
            is IslandState.Expanded -> drawExpanded(canvas, s)
            is IslandState.Minimal  -> drawMinimal(canvas, s)
        }
    }

    // ── Hidden ───────────────────────────────────────────────────────────────

    // ── Compact ───────────────────────────────────────────────────────────────
    private fun drawCompact(canvas: Canvas, state: IslandState.Compact) {
        val rect = islandRect()
        canvas.drawRoundRect(rect, animCorner, animCorner, islandPaint)

        val iconLeft   = rect.left + PADDING
        val iconTop    = rect.centerY() - ICON_SIZE / 2f
        val iconRight  = iconLeft + ICON_SIZE
        val iconBottom = iconTop + ICON_SIZE

        // Leading icon
        state.leadingIcon?.let { bmp ->
            canvas.drawBitmap(bmp, null,
                RectF(iconLeft, iconTop, iconRight, iconBottom), iconPaint)
        } ?: run {
            // Placeholder dot
            accentPaint.color = state.tintColor
            canvas.drawCircle(iconLeft + ICON_SIZE / 2f, rect.centerY(), 6f * dp, accentPaint)
        }

        // Trailing content
        val trailRight  = rect.right - PADDING
        when (state.trailingType) {
            IslandState.TrailingType.TEXT -> {
                textPaintLg.color = state.tintColor
                val tw = textPaintLg.measureText(state.trailingLabel)
                canvas.drawText(state.trailingLabel,
                    trailRight - tw, rect.centerY() + TEXT_SIZE_SMALL / 3f, textPaintLg)
            }
            IslandState.TrailingType.WAVEFORM -> {
                drawWaveform(canvas, trailRight, rect.centerY(), state.tintColor)
            }
        }
    }

    // ── Expanded ──────────────────────────────────────────────────────────────
    private fun drawExpanded(canvas: Canvas, state: IslandState.Expanded) {
        val rect = islandRect()
        canvas.drawRoundRect(rect, animCorner, animCorner, islandPaint)

        when (val ct = state.contentType) {
            is IslandState.ContentType.Media       -> drawMediaCard(canvas, rect, ct)
            is IslandState.ContentType.Notification -> drawNotifCard(canvas, rect, ct)
            is IslandState.ContentType.Battery     -> drawBatteryCard(canvas, rect, ct)
            is IslandState.ContentType.Timer       -> drawTimerCard(canvas, rect, ct)
        }
    }

    // ── Minimal / Split ───────────────────────────────────────────────────────
    private fun drawMinimal(canvas: Canvas, state: IslandState.Minimal) {
        if (state.secondaryIcon == null && animBubbleAlpha <= 0.01f) {
            // Just render compact
            drawCompact(canvas, state.primaryState)
            return
        }

        // Use software layer with blur + threshold for metaball liquid merge
        val bW = (animWidth + animBubbleX + BUBBLE_RADIUS * 4f + 40f * dp).toInt().coerceAtLeast(1)
        val bH = (animHeight + 60f * dp).toInt().coerceAtLeast(1)

        if (metaballBitmap == null ||
            metaballBitmap!!.width != bW || metaballBitmap!!.height != bH) {
            metaballBitmap?.recycle()
            metaballBitmap = Bitmap.createBitmap(bW, bH, Bitmap.Config.ARGB_8888)
            metaballCanvas = Canvas(metaballBitmap!!)
        }
        val mc = metaballCanvas!!
        mc.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        // Draw main pill
        val pillRect = RectF(
            0f, 20f * dp,
            animWidth, 20f * dp + animHeight
        )
        mc.drawRoundRect(pillRect, animCorner, animCorner, islandPaint)

        // Draw secondary bubble (separated by animBubbleX)
        val bubbleCx = animWidth + animBubbleX + BUBBLE_RADIUS * 2f
        val bubbleCy = 20f * dp + animHeight / 2f
        islandPaint.alpha = (animBubbleAlpha * 255f).toInt()
        mc.drawCircle(bubbleCx, bubbleCy, BUBBLE_RADIUS, islandPaint)
        islandPaint.alpha = 255

        // Apply blur
        blurPaint.reset()
        blurPaint.isAntiAlias = true
        blurPaint.maskFilter = metaballBlur
        val blurredArray = metaballBitmap!!.extractAlpha(blurPaint, null)

        // Apply threshold colour-matrix to make metaballs merge
        val threshPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        threshPaint.colorFilter = metaballThreshold

        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        bitmapPaint.color = Color.BLACK

        // Offscreen save for threshold composite
        val sc = canvas.saveLayer(
            centreX - animWidth / 2f - 20f * dp,
            topY - 20f * dp,
            centreX - animWidth / 2f + bW,
            topY + bH,
            null
        )

        canvas.drawBitmap(
            metaballBitmap!!, null,
            RectF(
                centreX - animWidth / 2f - 20f * dp,
                topY - 20f * dp,
                centreX - animWidth / 2f + bW - 20f * dp,
                topY + bH - 20f * dp
            ),
            threshPaint
        )
        canvas.restoreToCount(sc)

        blurredArray.recycle()

        // Now draw icons on top
        val pillCx = centreX
        val pillCy = topY + animHeight / 2f
        state.primaryState.leadingIcon?.let { bmp ->
            canvas.drawBitmap(bmp, null,
                RectF(pillCx - animWidth / 2f + PADDING,
                    pillCy - ICON_SIZE / 2f,
                    pillCx - animWidth / 2f + PADDING + ICON_SIZE,
                    pillCy + ICON_SIZE / 2f), iconPaint)
        }
        state.secondaryIcon?.let { bmp ->
            val bx = centreX - animWidth / 2f + animWidth + animBubbleX + BUBBLE_RADIUS * 2f
            canvas.drawBitmap(bmp, null,
                RectF(bx - ICON_SIZE / 2f, pillCy - ICON_SIZE / 2f,
                    bx + ICON_SIZE / 2f, pillCy + ICON_SIZE / 2f), iconPaint)
        }
    }

    // ── Media card ────────────────────────────────────────────────────────────
    private fun drawMediaCard(canvas: Canvas, rect: RectF, ct: IslandState.ContentType.Media) {
        val albumSize = rect.height() - PADDING * 2f

        // Album art (rounded square)
        ct.albumArt?.let { bmp ->
            val ar = RectF(rect.left + PADDING, rect.top + PADDING,
                rect.left + PADDING + albumSize, rect.bottom - PADDING)
            val path = Path()
            path.addRoundRect(ar, 10f * dp, 10f * dp, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(path)
            canvas.drawBitmap(bmp, null, ar, null)
            canvas.restore()
        } ?: run {
            // Placeholder gradient
            accentPaint.color = 0xFF1DB954.toInt()
            canvas.drawRoundRect(
                RectF(rect.left + PADDING, rect.top + PADDING,
                    rect.left + PADDING + albumSize, rect.bottom - PADDING),
                10f * dp, 10f * dp, accentPaint
            )
        }

        val textLeft = rect.left + PADDING + albumSize + PADDING
        val textRight = rect.right - PADDING

        // Title
        textPaintLg.color = Color.WHITE
        textPaintLg.textSize = TEXT_SIZE_SMALL
        canvas.drawTextClipped(ct.title, textLeft, rect.top + PADDING + TEXT_SIZE_SMALL,
            textRight, textPaintLg)

        // Artist
        textPaintSm.color = 0xFFAAAAAA.toInt().also { textPaintSm.alpha = 200 }
        canvas.drawTextClipped(ct.artist, textLeft,
            rect.top + PADDING + TEXT_SIZE_SMALL * 2.2f, textRight, textPaintSm)

        // Control buttons: prev / play-pause / next
        val btnY = rect.bottom - PADDING - 20f * dp
        val btnCentreX = (textLeft + textRight) / 2f
        drawMediaBtn(canvas, btnCentreX - 40f * dp, btnY, "⏮")
        drawMediaBtn(canvas, btnCentreX, btnY,
            if (ct.isPlaying) "⏸" else "▶")
        drawMediaBtn(canvas, btnCentreX + 40f * dp, btnY, "⏭")

        // Seekbar
        val sbTop  = btnY - 14f * dp
        val sbLeft = textLeft
        val sbRight = textRight
        canvas.drawRoundRect(
            RectF(sbLeft, sbTop, sbRight, sbTop + SEEKBAR_H),
            SEEKBAR_H / 2f, SEEKBAR_H / 2f, seekBgPaint)
        val filled = sbLeft + (sbRight - sbLeft) * seekProgress
        canvas.drawRoundRect(
            RectF(sbLeft, sbTop, filled, sbTop + SEEKBAR_H),
            SEEKBAR_H / 2f, SEEKBAR_H / 2f, seekFgPaint)
        // Thumb
        canvas.drawCircle(filled, sbTop + SEEKBAR_H / 2f, 5f * dp, seekFgPaint)

        // Store seekbar rect for touch handling
        seekbarRect.set(sbLeft, sbTop - 8f * dp, sbRight, sbTop + SEEKBAR_H + 8f * dp)
        prevBtnRect.set(btnCentreX - 60f * dp, btnY - 20f * dp, btnCentreX - 20f * dp, btnY + 20f * dp)
        playBtnRect.set(btnCentreX - 20f * dp, btnY - 20f * dp, btnCentreX + 20f * dp, btnY + 20f * dp)
        nextBtnRect.set(btnCentreX + 20f * dp, btnY - 20f * dp, btnCentreX + 60f * dp, btnY + 20f * dp)
    }

    // Rects used for hit-testing media controls
    private val seekbarRect = RectF()
    private val prevBtnRect = RectF()
    private val playBtnRect = RectF()
    private val nextBtnRect = RectF()

    private fun drawMediaBtn(canvas: Canvas, cx: Float, cy: Float, symbol: String) {
        textPaintLg.textSize = 16f * sp
        textPaintLg.color = Color.WHITE
        val tw = textPaintLg.measureText(symbol)
        canvas.drawText(symbol, cx - tw / 2f, cy + 6f * dp, textPaintLg)
        textPaintLg.textSize = TEXT_SIZE_SMALL
    }

    // ── Notification card ─────────────────────────────────────────────────────
    private fun drawNotifCard(
        canvas: Canvas, rect: RectF,
        ct: IslandState.ContentType.Notification
    ) {
        val iconSize = 32f * dp

        // App icon
        ct.appIcon?.let { bmp ->
            canvas.drawBitmap(bmp, null,
                RectF(rect.left + PADDING, rect.top + PADDING,
                    rect.left + PADDING + iconSize, rect.top + PADDING + iconSize), iconPaint)
        }

        val textLeft = rect.left + PADDING + iconSize + PADDING

        // App name + sender
        textPaintSm.color = 0xFFAAAAAA.toInt()
        textPaintSm.alpha = 180
        canvas.drawText("${ct.appName}  ·  ${ct.sender}",
            textLeft, rect.top + PADDING + TEXT_SIZE_MED, textPaintSm)

        // Message body
        textPaintLg.color = Color.WHITE
        textPaintLg.textSize = TEXT_SIZE_SMALL
        canvas.drawTextClipped(ct.body, textLeft,
            rect.top + PADDING + TEXT_SIZE_MED + TEXT_SIZE_SMALL * 1.6f,
            rect.right - PADDING, textPaintLg)

        // Reply hint
        if (ct.replyAction != null) {
            textPaintSm.color = 0xFF2196F3.toInt()
            textPaintSm.alpha = 220
            val hint = "↩ Reply"
            val tw = textPaintSm.measureText(hint)
            canvas.drawText(hint,
                rect.right - PADDING - tw,
                rect.bottom - PADDING, textPaintSm)
        }
    }

    // ── Battery card ──────────────────────────────────────────────────────────
    private fun drawBatteryCard(canvas: Canvas, rect: RectF, ct: IslandState.ContentType.Battery) {
        val bW = 60f * dp
        val bH = 28f * dp
        val bLeft = rect.left + PADDING
        val bTop  = rect.centerY() - bH / 2f

        // Battery outline
        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f * dp
        }
        canvas.drawRoundRect(RectF(bLeft, bTop, bLeft + bW, bTop + bH),
            4f * dp, 4f * dp, outlinePaint)
        // Terminal nub
        canvas.drawRoundRect(
            RectF(bLeft + bW, rect.centerY() - 5f * dp, bLeft + bW + 4f * dp, rect.centerY() + 5f * dp),
            2f * dp, 2f * dp, outlinePaint)

        // Fill
        val fillColor = when {
            ct.percent >= 50 -> 0xFF4CAF50.toInt()
            ct.percent >= 20 -> 0xFFFF9800.toInt()
            else             -> 0xFFF44336.toInt()
        }
        accentPaint.color = fillColor
        val fillW = (bW - 4f * dp) * ct.percent / 100f
        canvas.drawRoundRect(
            RectF(bLeft + 2f * dp, bTop + 2f * dp, bLeft + 2f * dp + fillW, bTop + bH - 2f * dp),
            3f * dp, 3f * dp, accentPaint)

        // Percentage text
        textPaintLg.color = Color.WHITE
        textPaintLg.textSize = 18f * sp
        val pct = "${ct.percent}%"
        canvas.drawText(pct, bLeft + bW + 12f * dp, rect.centerY() + 7f * dp, textPaintLg)

        // Lightning bolt ⚡ (charging indicator)
        textPaintLg.textSize = 20f * sp
        canvas.drawText("⚡", rect.right - 44f * dp, rect.centerY() + 8f * dp, textPaintLg)
    }

    // ── Timer card ────────────────────────────────────────────────────────────
    private fun drawTimerCard(canvas: Canvas, rect: RectF, ct: IslandState.ContentType.Timer) {
        val total = ct.remainingMs
        val h = total.msToHours()
        val m = total.msToMinutes() % 60
        val s = total.msToSeconds() % 60
        val label = if (h > 0) String.format("%d:%02d:%02d", h, m, s)
                    else        String.format("%02d:%02d", m, s)

        textPaintLg.textSize = 32f * sp
        textPaintLg.color = 0xFFFF9500.toInt()
        val tw = textPaintLg.measureText(label)
        canvas.drawText(label,
            rect.centerX() - tw / 2f, rect.centerY() + 11f * dp, textPaintLg)

        // Label
        textPaintSm.color = 0xFFAAAAAA.toInt()
        textPaintSm.alpha = 180
        textPaintSm.textSize = TEXT_SIZE_MED
        val sub = "Timer"
        val sw = textPaintSm.measureText(sub)
        canvas.drawText(sub, rect.centerX() - sw / 2f,
            rect.bottom - PADDING, textPaintSm)
    }

    // ── Waveform ──────────────────────────────────────────────────────────────
    private fun drawWaveform(canvas: Canvas, rightX: Float, centreY: Float, color: Int) {
        accentPaint.color = color
        val barW = 3f * dp
        val gap  = 2f * dp
        val totalW = WAVEFORM_BARS * barW + (WAVEFORM_BARS - 1) * gap
        var x = rightX - totalW

        for (i in 0 until WAVEFORM_BARS) {
            val phase = wavePhase + i * 0.7f
            val barH = (sin(phase.toDouble()) * 0.5 + 0.6) * 14f * dp
            canvas.drawRoundRect(
                RectF(x, centreY - barH.toFloat() / 2f,
                    x + barW, centreY + barH.toFloat() / 2f),
                barW / 2f, barW / 2f, accentPaint
            )
            x += barW + gap
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // STATE ANIMATIONS
    // ══════════════════════════════════════════════════════════════════════════

    private fun applyStateAnimations(prev: IslandState, next: IslandState) {
        val (targetW, targetH, targetCorner) = when (next) {
            is IslandState.Hidden   -> Triple(0f, 0f, CORNER_COMPACT)
            is IslandState.Compact  -> Triple(COMPACT_W_MIN, BASE_HEIGHT, CORNER_COMPACT)
            is IslandState.Expanded -> Triple(EXPANDED_W, EXPANDED_H, CORNER_EXPANDED)
            is IslandState.Minimal  -> Triple(COMPACT_W_MIN * 0.8f, BASE_HEIGHT, CORNER_COMPACT)
        }

        animateValue(animWidth, targetW)  { animWidth = it;  invalidate() }
        animateValue(animHeight, targetH) { animHeight = it; invalidate() }
        animateValue(animCorner, targetCorner) { animCorner = it; invalidate() }

        // Bubble separation for Minimal
        if (next is IslandState.Minimal) {
            animateValue(animBubbleX, 0f)         { animBubbleX = it; invalidate() }
            animateValue(animBubbleAlpha, 1f)     { animBubbleAlpha = it; invalidate() }
            // Animate bubble splitting outward
            animateValueDelayed(animBubbleX, 16f * dp, 120) { animBubbleX = it; invalidate() }
            startWaveformLoop()
        } else {
            animateValue(animBubbleAlpha, 0f) { animBubbleAlpha = it; invalidate() }
        }

        // Waveform for Compact with WAVEFORM trailing
        if (next is IslandState.Compact &&
            next.trailingType == IslandState.TrailingType.WAVEFORM) {
            startWaveformLoop()
        } else if (prev is IslandState.Compact &&
            prev.trailingType == IslandState.TrailingType.WAVEFORM &&
            next !is IslandState.Compact) {
            stopWaveformLoop()
        }
    }

    /** Smooth spring-like value animator using DecelerateInterpolator + slight overshoot. */
    private fun animateValue(from: Float, to: Float, onUpdate: (Float) -> Unit) {
        ValueAnimator.ofFloat(from, to).apply {
            duration = 420L
            interpolator = SpringInterpolator(stiffness = 400f, dampingRatio = 0.75f)
            addUpdateListener { onUpdate(it.animatedValue as Float) }
            start()
        }
    }

    private fun animateValueDelayed(from: Float, to: Float,
                                    delayMs: Long, onUpdate: (Float) -> Unit) {
        ValueAnimator.ofFloat(from, to).apply {
            duration = 350L
            startDelay = delayMs
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { onUpdate(it.animatedValue as Float) }
            start()
        }
    }

    private fun startWaveformLoop() {
        if (waveAnimator?.isRunning == true) return
        waveAnimator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = 800L
            repeatCount = ValueAnimator.INFINITE
            repeatMode  = ValueAnimator.RESTART
            addUpdateListener {
                wavePhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopWaveformLoop() {
        waveAnimator?.cancel()
        waveAnimator = null
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private fun islandRect(): RectF {
        val left   = centreX - animWidth / 2f
        val top    = topY
        val right  = centreX + animWidth / 2f
        val bottom = topY + animHeight
        return RectF(left, top, right, bottom)
    }

    private fun isInsideIsland(x: Float, y: Float): Boolean {
        return islandRect().contains(x, y)
    }

    private fun handleTap(x: Float, y: Float) {
        val state = currentState
        if (state is IslandState.Expanded) {
            // Media controls
            if (state.contentType is IslandState.ContentType.Media) {
                when {
                    prevBtnRect.contains(x, y) -> { onPrevious?.invoke(); return }
                    playBtnRect.contains(x, y) -> { onPlayPause?.invoke(); return }
                    nextBtnRect.contains(x, y) -> { onNext?.invoke(); return }
                }
            }
        }
        onSingleTap?.invoke()
    }

    private fun handleSeekbarTouch(event: MotionEvent) {
        val state = currentState
        if (state !is IslandState.Expanded ||
            state.contentType !is IslandState.ContentType.Media) return

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (seekbarRect.contains(event.x, event.y)) isSeeking = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isSeeking) {
                    val prog = ((event.x - seekbarRect.left) /
                            (seekbarRect.right - seekbarRect.left)).coerceIn(0f, 1f)
                    seekProgress = prog
                    onSeekChanged?.invoke(prog)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> isSeeking = false
        }
    }

    private fun haptic(effectId: Int) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                .defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(effectId))
        }
    }

    /** Create a SpringAnimation (used only for translational springs, e.g., view position). */
    private fun makeSpring(
        prop: DynamicAnimation.ViewProperty,
        stiffness: Float,
        dampingRatio: Float
    ): SpringAnimation {
        return SpringAnimation(this, prop).apply {
            spring = SpringForce(0f).apply {
                this.stiffness = stiffness
                this.dampingRatio = dampingRatio
            }
        }
    }

    private fun Canvas.drawTextClipped(
        text: String, x: Float, y: Float, maxX: Float, paint: Paint
    ) {
        val ellipsised = TextUtils.ellipsize(
            text, android.text.TextPaint(paint), maxX - x, TextUtils.TruncateAt.END
        ).toString()
        drawText(ellipsised, x, y, paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopWaveformLoop()
        widthAnim?.cancel()
        heightAnim?.cancel()
        cornerAnim?.cancel()
    }
}

// ── TimeUnit helpers ──────────────────────────────────────────────────────────
private fun Long.msToHours()   = this / 3_600_000L
private fun Long.msToMinutes() = this / 60_000L
private fun Long.msToSeconds() = this / 1_000L

// ── Spring interpolator (mimics SpringForce for ValueAnimator) ─────────────────
private class SpringInterpolator(
    private val stiffness: Float = 400f,
    private val dampingRatio: Float = 0.75f
) : android.view.animation.Interpolator {

    override fun getInterpolation(t: Float): Float {
        // Underdamped spring response:  e^(-ζωt) * cos(ωd*t) approximation
        val omega  = sqrt(stiffness.toDouble())
        val zeta   = dampingRatio.toDouble()
        val omegaD = omega * sqrt(1.0 - zeta * zeta)
        return (1.0 - exp(-zeta * omega * t) *
                (cos(omegaD * t) + (zeta / sqrt(1.0 - zeta * zeta)) * sin(omegaD * t))).toFloat()
    }
}
