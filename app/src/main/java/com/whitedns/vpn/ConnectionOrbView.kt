package com.whitedns.vpn

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * A smooth liquid orb button with gentle morphing, layered gradients,
 * and particle effects - matching the WhiteVPN design.
 */
class ConnectionOrbView(context: Context) : View(context) {
    private val palette = WhiteDnsDesignTokens.forContext(context)
    private val evaluator = ArgbEvaluator()

    // Dimensions
    private val containerSize = dp(300f)
    private val orbDiameter = dp(224f)
    private val orbRadius = orbDiameter / 2f

    // Paints
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = WhiteDnsDisplayTypeface
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }

    private val orbPath = Path()
    private val clipPath = Path()
    private val iconBounds = RectF()

    // State
    private var state: VpnState = VpnState.Stopped
    private var transitionProgress = 1f
    private var isPressed = false

    // Animation phases (0-1, smooth looping)
    private var blobPhase = 0f           // 9s - gentle blob morphing
    private var swirlPhase = 0f          // 20s - inner mist rotation
    private var breathePhase = 0f        // 4s - outer glow pulsing
    private var corePhase = 0f           // 7s - core glow pulsing
    private var spinPhase = 0f           // 1.1s - connecting spinner
    private var pressScale = 1f
    private var shadeOpacity = 0f

    // Animators
    private var transitionAnimator: ValueAnimator? = null
    private var masterAnimator: ValueAnimator? = null
    private var pressAnimator: ValueAnimator? = null
    private var shadeAnimator: ValueAnimator? = null

    // Particle system
    private data class Particle(
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        var size: Float, var life: Float, var maxLife: Float,
        var alpha: Float, var color: Int, var slow: Boolean,
        // For circular motion (connecting state)
        var isCircular: Boolean = false,
        var angle: Float = 0f,
        var radius: Float = 0f,
        var angularSpeed: Float = 0f
    )

    private val particles = mutableListOf<Particle>()
    private var lastParticleTime = 0L
    private var lastFrameTime = 0L

    // Particle colors - light colors for dark theme, darker for light theme
    private val darkParticleColors = intArrayOf(
        0xFF7EE0BA.toInt(),  // Soft mint
        0xFF9AEBCC.toInt(),  // Light seafoam
        0xFF6BCAA5.toInt(),  // Muted teal
        0xFFAEF0D8.toInt(),  // Pale mint
        0xFF5DBFA0.toInt(),  // Deep mint
        0xFF8CE8C4.toInt(),  // Fresh green
        0xFFB4F5E0.toInt(),  // Very light mint
        0xFF72D8B0.toInt(),  // Medium mint
        0xFFA2ECD0.toInt(),  // Soft seafoam
        0xFF64C8A0.toInt()   // Teal green
    )
    // Darker/more saturated greens for light theme - better visibility
    private val lightParticleColors = intArrayOf(
        0xFF2A9D70.toInt(),  // Deep green
        0xFF35B080.toInt(),  // Medium green
        0xFF40C090.toInt(),  // Teal green
        0xFF28A878.toInt(),  // Forest green
        0xFF45C898.toInt(),  // Mint green
        0xFF30B888.toInt(),  // Sea green
        0xFF3DBD8A.toInt(),  // Jade
        0xFF25A070.toInt(),  // Dark mint
        0xFF48CCA0.toInt(),  // Light jade
        0xFF2DB078.toInt()   // Medium jade
    )

    init {
        isClickable = true
        isFocusable = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private var isPaused = false

    fun setVpnState(newState: VpnState) {
        if (state == newState) return
        state = newState

        transitionAnimator?.cancel()
        if (ValueAnimator.areAnimatorsEnabled()) {
            transitionProgress = 0f
            transitionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 550L
                interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
                addUpdateListener {
                    transitionProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            transitionProgress = 1f
        }
        invalidate()
    }

    /**
     * Call when app goes to background to stop animations and save battery.
     */
    fun pauseAnimation() {
        isPaused = true
        cancelAllAnimations()
        particles.clear()
    }

    /**
     * Call when app comes to foreground to resume animations.
     */
    fun resumeAnimation() {
        isPaused = false
        startMasterAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startMasterAnimation()
    }

    override fun onDetachedFromWindow() {
        cancelAllAnimations()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && !isPaused) startMasterAnimation() else cancelAllAnimations()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = containerSize.toInt()
        setMeasuredDimension(
            resolveSize(size, widthMeasureSpec),
            resolveSize(size, heightMeasureSpec)
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isPressed = true
                animatePress(0.94f)
                animateShade(1f)
                postDelayed({ if (isPressed) emitDustBurst() }, 80)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isPressed) {
                    isPressed = false
                    animatePressRelease()
                    animateShade(0f)
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                animatePressRelease()
                animateShade(0f)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        performHapticFeedback()
        return true
    }

    /**
     * Double-pulse haptic feedback: "boob bweeeeb" effect
     * Safe - won't crash if vibration isn't available
     */
    @Suppress("DEPRECATION")
    private fun performHapticFeedback() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            vibrator?.let { vib ->
                if (!vib.hasVibrator()) return@let

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Gentle double pulse: soft tap, pause, slightly longer soft pulse
                    val timings = longArrayOf(0, 20, 40, 35)
                    val amplitudes = intArrayOf(0, 60, 0, 80)
                    vib.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                } else {
                    // Fallback for older devices - shorter pattern
                    val pattern = longArrayOf(0, 15, 35, 25)
                    vib.vibrate(pattern, -1)
                }
            }
        } catch (e: Exception) {
            // Silently ignore - haptic is non-essential
        }
    }

    override fun onDraw(canvas: Canvas) {
        updateParticles()

        val cx = width / 2f
        val cy = height / 2f

        canvas.save()
        canvas.scale(pressScale, pressScale, cx, cy)
        if (isPressed) {
            canvas.translate(0f, dp(3f) * (1f - pressScale) / 0.06f)
        }

        // 1. Draw particles behind orb
        drawParticles(canvas, cx, cy)

        // 2. Draw outer breathing glow
        drawOuterGlow(canvas, cx, cy)

        // 3. Spinning ring removed - particles handle connecting animation now

        // 4. Build smooth blob path
        buildSmoothBlobPath(cx, cy)

        // 5. Draw main orb with gradient
        drawOrbBase(canvas, cx, cy)

        // 6. Draw inner effects (highlights, shadows, mist)
        drawOrbEffects(canvas, cx, cy)

        // 7. Draw press shade overlay
        if (shadeOpacity > 0f) {
            drawPressShade(canvas, cx, cy)
        }

        // 8. Draw icon and label
        drawIcon(canvas, cx, cy - dp(6f))
        drawLabel(canvas, cx, cy + dp(36f))

        canvas.restore()
    }

    /**
     * Build a smooth organic blob using sine wave modulation.
     * This creates gentle, smooth curves without sharp points.
     * All phase multipliers are integers to ensure perfect looping.
     */
    private fun buildSmoothBlobPath(cx: Float, cy: Float) {
        orbPath.reset()

        val baseRadius = orbRadius
        val segments = 120  // More segments = smoother curve
        val phase = blobPhase * 2f * PI.toFloat()

        // Use multiple low-frequency sine waves for smooth organic shape
        // All phase multipliers are integers (1, 2, 3) for seamless looping
        for (i in 0..segments) {
            val angle = (i.toFloat() / segments) * 2f * PI.toFloat()

            // Combine multiple sine waves - all coefficients are integers for perfect loop
            val wave1 = sin(angle * 2f + phase) * dp(4f)
            val wave2 = sin(angle * 3f - phase) * dp(3f)
            val wave3 = cos(angle * 2f + phase * 2f) * dp(2.5f)

            val r = baseRadius + wave1 + wave2 + wave3
            val x = cx + r * cos(angle)
            val y = cy + r * sin(angle)

            if (i == 0) {
                orbPath.moveTo(x, y)
            } else {
                orbPath.lineTo(x, y)
            }
        }
        orbPath.close()
    }

    private fun drawParticles(canvas: Canvas, cx: Float, cy: Float) {
        for (p in particles) {
            val t = p.life / p.maxLife
            val alpha = if (p.slow) {
                (p.alpha * min(1f, t * 5f) * (1f - t) * 255).toInt()
            } else {
                (p.alpha * (1f - t).pow(1.5f) * 255).toInt()
            }
            if (alpha <= 0) continue

            particlePaint.color = withAlpha(p.color, alpha)
            canvas.drawCircle(p.x, p.y, p.size / 2f, particlePaint)
        }
    }

    private fun drawOuterGlow(canvas: Canvas, cx: Float, cy: Float) {
        // Breathing glow effect - more visible
        val phase = sin(breathePhase * 2f * PI.toFloat())
        val scale = 1f + 0.08f * phase
        val opacity = 0.7f + 0.3f * phase

        if (palette.isDark) {
            // Dark theme: dark green glow
            val outerGlowRadius = orbRadius * 1.6f * scale
            glowPaint.shader = RadialGradient(
                cx, cy, outerGlowRadius,
                intArrayOf(
                    withAlpha(0x256B55, (0.35f * opacity * 255).toInt()),
                    withAlpha(0x1A5040, (0.18f * opacity * 255).toInt()),
                    withAlpha(0x1A5040, (0.06f * opacity * 255).toInt()),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.4f, 0.7f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, outerGlowRadius, glowPaint)

            val innerGlowRadius = orbRadius * 1.25f * scale
            glowPaint.shader = RadialGradient(
                cx, cy, innerGlowRadius,
                intArrayOf(
                    withAlpha(0x3FBE90, (0.50f * opacity * 255).toInt()),
                    withAlpha(0x2D8B6E, (0.25f * opacity * 255).toInt()),
                    withAlpha(0x256B55, (0.10f * opacity * 255).toInt()),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0.5f, 0.72f, 0.88f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, innerGlowRadius, glowPaint)
        } else {
            // Light theme: light green glow
            val outerGlowRadius = orbRadius * 1.6f * scale
            glowPaint.shader = RadialGradient(
                cx, cy, outerGlowRadius,
                intArrayOf(
                    withAlpha(0x7EE8C0, (0.40f * opacity * 255).toInt()),  // Light mint
                    withAlpha(0xA8F0D8, (0.25f * opacity * 255).toInt()),  // Pale mint
                    withAlpha(0xC0F8E8, (0.10f * opacity * 255).toInt()),  // Very light mint
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.4f, 0.7f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, outerGlowRadius, glowPaint)

            val innerGlowRadius = orbRadius * 1.25f * scale
            glowPaint.shader = RadialGradient(
                cx, cy, innerGlowRadius,
                intArrayOf(
                    withAlpha(0x50D8A0, (0.55f * opacity * 255).toInt()),  // Medium green
                    withAlpha(0x78E8C0, (0.30f * opacity * 255).toInt()),  // Light green
                    withAlpha(0xA0F0D8, (0.12f * opacity * 255).toInt()),  // Pale green
                    Color.TRANSPARENT
                ),
                floatArrayOf(0.5f, 0.72f, 0.88f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, innerGlowRadius, glowPaint)
        }
    }

    private fun drawSpinRing(canvas: Canvas, cx: Float, cy: Float) {
        val ringRadius = orbRadius + dp(8f)
        // Light green ring, semi-transparent
        ringPaint.color = withAlpha(0x7EE0BA, 100)
        ringPaint.strokeWidth = dp(1.5f)
        iconBounds.set(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius)
        canvas.drawArc(iconBounds, -90f + spinPhase * 360f, 280f, false, ringPaint)
    }

    private fun drawOrbBase(canvas: Canvas, cx: Float, cy: Float) {
        val isIdle = state == VpnState.Stopped
        val isError = state is VpnState.Error || state == VpnState.DailyLimitReached

        // Create gradient based on state
        val colors: IntArray
        val positions: FloatArray

        if (isIdle) {
            // Subtle, semi-transparent gradient for idle state
            colors = if (palette.isDark) {
                intArrayOf(
                    0x557EE0BA.toInt(),  // Lighter top
                    0x443FBE90.toInt(),
                    0x3835A87E.toInt(),
                    0x302B9370.toInt(),
                    0x2823845F.toInt()   // Darker bottom
                )
            } else {
                intArrayOf(
                    0x9068D8B0.toInt(),
                    0x8050C898.toInt(),
                    0x7038B080.toInt(),
                    0x6528A070.toInt(),
                    0x5818905E.toInt()
                )
            }
            positions = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        } else if (isError) {
            // Red/orange for error state
            colors = if (palette.isDark) {
                intArrayOf(
                    0xFFE07878.toInt(),
                    0xFFD05050.toInt(),
                    0xFFC04040.toInt(),
                    0xFFB03535.toInt()
                )
            } else {
                intArrayOf(
                    0xFFD05555.toInt(),
                    0xFFC04545.toInt(),
                    0xFFB03838.toInt(),
                    0xFFA02828.toInt()
                )
            }
            positions = floatArrayOf(0f, 0.35f, 0.7f, 1f)
        } else {
            // Vibrant green gradient for connected/connecting states
            colors = if (palette.isDark) {
                intArrayOf(
                    0xFF7EECC0.toInt(),  // Bright top
                    0xFF5FDAA8.toInt(),
                    0xFF45C894.toInt(),
                    0xFF3FBE90.toInt(),  // Main accent
                    0xFF35A87E.toInt(),
                    0xFF2B926C.toInt(),
                    0xFF227C5A.toInt()   // Dark bottom
                )
            } else {
                intArrayOf(
                    0xFF38C088.toInt(),
                    0xFF28B078.toInt(),
                    0xFF18A068.toInt(),
                    0xFF088858.toInt(),
                    0xFF007848.toInt(),
                    0xFF006838.toInt()
                )
            }
            positions = if (palette.isDark) {
                floatArrayOf(0f, 0.15f, 0.32f, 0.5f, 0.68f, 0.85f, 1f)
            } else {
                floatArrayOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f)
            }
        }

        // Apply gradient at 160 degree angle (matching design)
        val gradientAngle = 160f * PI.toFloat() / 180f
        val gradientLength = orbRadius * 2.2f
        orbPaint.shader = LinearGradient(
            cx - cos(gradientAngle) * gradientLength / 2,
            cy - sin(gradientAngle) * gradientLength / 2 - orbRadius * 0.3f,
            cx + cos(gradientAngle) * gradientLength / 2,
            cy + sin(gradientAngle) * gradientLength / 2 + orbRadius * 0.3f,
            colors, positions,
            Shader.TileMode.CLAMP
        )

        canvas.drawPath(orbPath, orbPaint)
    }

    private fun drawOrbEffects(canvas: Canvas, cx: Float, cy: Float) {
        canvas.save()
        canvas.clipPath(orbPath)

        // 1. Inner swirling mist (subtle rotating gradient)
        drawInnerMist(canvas, cx, cy)

        // 2. Core glow (breathing center light)
        drawCoreGlow(canvas, cx, cy)

        // 3. Edge darkening (rim shadow)
        drawEdgeShadow(canvas, cx, cy)

        // 4. Top highlight (specular reflection)
        drawTopHighlight(canvas, cx, cy)

        // 5. Bottom reflection
        drawBottomReflection(canvas, cx, cy)

        canvas.restore()
    }

    private fun drawInnerMist(canvas: Canvas, cx: Float, cy: Float) {
        val rotation = swirlPhase * 360f

        canvas.save()
        canvas.rotate(rotation, cx, cy)

        // Soft radial gradient spots that rotate
        overlayPaint.shader = RadialGradient(
            cx - orbRadius * 0.25f, cy - orbRadius * 0.2f, orbRadius * 0.5f,
            intArrayOf(
                withAlpha(0x7EE0BA, 45),
                withAlpha(0x7EE0BA, 20),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, orbRadius, overlayPaint)

        canvas.restore()
    }

    private fun drawCoreGlow(canvas: Canvas, cx: Float, cy: Float) {
        val phase = sin(corePhase * 2f * PI.toFloat())
        val scale = 0.92f + 0.12f * phase
        val opacity = 0.35f + 0.35f * phase

        val coreRadius = orbRadius * 0.6f * scale
        overlayPaint.shader = RadialGradient(
            cx, cy, coreRadius,
            intArrayOf(
                withAlpha(0xD6FCEC, (opacity * 180).toInt()),
                withAlpha(0x7EE0BA, (opacity * 80).toInt()),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, coreRadius, overlayPaint)
    }

    private fun drawEdgeShadow(canvas: Canvas, cx: Float, cy: Float) {
        // Edge shadow now follows the blob path since we're already clipped to orbPath
        overlayPaint.shader = RadialGradient(
            cx, cy, orbRadius * 1.1f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                withAlpha(0x18684C, 30),
                withAlpha(0x12503A, 80)
            ),
            floatArrayOf(0f, 0.7f, 0.88f, 1f),
            Shader.TileMode.CLAMP
        )
        // Draw on the clipped area (orbPath) not a fixed circle
        canvas.drawPaint(overlayPaint)
    }

    private fun drawTopHighlight(canvas: Canvas, cx: Float, cy: Float) {
        val highlightX = cx - orbRadius * 0.2f
        val highlightY = cy - orbRadius * 0.35f
        overlayPaint.shader = RadialGradient(
            highlightX, highlightY, orbRadius * 0.55f,
            intArrayOf(
                withAlpha(0xFFFFFF, 60),
                withAlpha(0xFFFFFF, 30),
                withAlpha(0xFFFFFF, 10),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.3f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, orbRadius, overlayPaint)
    }

    private fun drawBottomReflection(canvas: Canvas, cx: Float, cy: Float) {
        overlayPaint.shader = RadialGradient(
            cx, cy + orbRadius * 0.5f, orbRadius * 0.7f,
            intArrayOf(
                withAlpha(0x3FBE90, 50),
                withAlpha(0x3FBE90, 25),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, orbRadius, overlayPaint)
    }

    private fun drawPressShade(canvas: Canvas, cx: Float, cy: Float) {
        overlayPaint.shader = RadialGradient(
            cx, cy + orbRadius * 0.1f, orbRadius,
            intArrayOf(
                withAlpha(Color.BLACK, (0.25f * shadeOpacity * 255).toInt()),
                withAlpha(Color.BLACK, (0.10f * shadeOpacity * 255).toInt()),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(orbPath, overlayPaint)
    }

    private fun drawIcon(canvas: Canvas, cx: Float, cy: Float) {
        val isIdle = state == VpnState.Stopped
        val isConnected = state == VpnState.Started
        val isError = state is VpnState.Error || state == VpnState.DailyLimitReached

        iconPaint.color = when {
            isConnected -> palette.onAccent
            isError -> palette.textPrimary
            else -> palette.textPrimary
        }

        val size = dp(14f)

        when {
            isConnected -> {
                // Disconnect icon: power with slash
                iconBounds.set(cx - size, cy - size * 0.7f, cx + size, cy + size * 1.3f)
                canvas.drawArc(iconBounds, -55f, 290f, false, iconPaint)
                canvas.drawLine(cx, cy - size * 1.3f, cx, cy + dp(2f), iconPaint)
                canvas.drawLine(cx - size * 1.1f, cy + size * 1.1f, cx + size * 1.1f, cy - size * 1.1f, iconPaint)
            }
            isError -> {
                // Exclamation mark
                iconPaint.strokeWidth = dp(2.5f)
                canvas.drawLine(cx, cy - dp(12f), cx, cy + dp(2f), iconPaint)
                iconPaint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy + dp(10f), dp(2.5f), iconPaint)
                iconPaint.style = Paint.Style.STROKE
                iconPaint.strokeWidth = dp(2.2f)
            }
            else -> {
                // Power icon
                iconBounds.set(cx - size, cy - size * 0.7f, cx + size, cy + size * 1.3f)
                canvas.drawArc(iconBounds, -55f, 290f, false, iconPaint)
                canvas.drawLine(cx, cy - size * 1.3f, cx, cy + dp(2f), iconPaint)
            }
        }
    }

    private fun drawLabel(canvas: Canvas, cx: Float, cy: Float) {
        val label = when (state) {
            VpnState.Started -> resources.getString(R.string.connect_action_disconnect)
            VpnState.Starting -> resources.getString(R.string.connect_action_connecting)
            VpnState.Stopping -> resources.getString(R.string.connect_action_disconnecting)
            is VpnState.Error -> resources.getString(R.string.connect_action_retry)
            VpnState.DailyLimitReached -> resources.getString(R.string.connect_action_usage_limit)
            VpnState.Stopped -> resources.getString(R.string.connect_action_connect)
        }

        labelPaint.textSize = sp(15f)
        labelPaint.color = when (state) {
            VpnState.Started -> palette.onAccent
            else -> palette.textPrimary
        }

        val metrics = labelPaint.fontMetrics
        val textY = cy - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(label, cx, textY, labelPaint)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Particle System
    // ═══════════════════════════════════════════════════════════════════════════

    private fun updateParticles() {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameTime > 0) min((now - lastFrameTime).toFloat(), 40f) else 16.67f
        lastFrameTime = now

        val cx = width / 2f
        val cy = height / 2f

        // Emit circular particles when connecting/disconnecting (slower rate for performance)
        val isConnecting = state == VpnState.Starting || state == VpnState.Stopping
        if (isConnecting && now - lastParticleTime >= 80L) {
            emitCircularParticles()
            lastParticleTime = now
        }
        // Emit ambient particles when connected (slower rate for performance)
        else if (state == VpnState.Started && now - lastParticleTime >= 150L) {
            emitAmbientParticles()
            lastParticleTime = now
        }

        // Update particles
        val iterator = particles.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            p.life += dt
            if (p.life >= p.maxLife) {
                iterator.remove()
                continue
            }

            if (p.isCircular) {
                // Circular motion around the orb
                p.angle += p.angularSpeed * (dt / 16.67f)
                p.x = cx + cos(p.angle) * p.radius
                p.y = cy + sin(p.angle) * p.radius
                // Gradually expand outward
                p.radius += 0.15f * (dt / 16.67f)
            } else {
                p.x += p.vx * (dt / 16.67f)
                p.y += p.vy * (dt / 16.67f)
                if (p.slow) {
                    p.vy -= 0.003f * (dt / 16.67f)
                } else {
                    p.vx *= 0.98f
                    p.vy *= 0.98f
                    p.vy += 0.01f * (dt / 16.67f)
                }
            }
        }

        if (particles.isNotEmpty() || state == VpnState.Started || isConnecting) {
            invalidate()
        }
    }

    private fun emitAmbientParticles() {
        val cx = width / 2f
        val cy = height / 2f
        val colors = if (palette.isDark) darkParticleColors else lightParticleColors

        // More particles but with longer life (fewer emissions needed)
        repeat(4 + Random.nextInt(4)) {
            val angle = Random.nextFloat() * 2f * PI.toFloat()
            val r = orbRadius + dp(5f + Random.nextFloat() * 12f)
            val speed = 0.25f + Random.nextFloat() * 0.4f

            particles.add(Particle(
                x = cx + cos(angle) * r,
                y = cy + sin(angle) * r,
                vx = cos(angle) * speed,
                vy = sin(angle) * speed - 0.06f,
                size = dp(1f + Random.nextFloat() * 1.5f),
                life = 0f,
                maxLife = 2500f + Random.nextFloat() * 2000f,  // Longer life = more visible particles
                alpha = 0.3f + Random.nextFloat() * 0.35f,
                color = colors[Random.nextInt(colors.size)],
                slow = true
            ))
        }

        while (particles.size > 250) particles.removeAt(0)
    }

    private fun emitCircularParticles() {
        val colors = if (palette.isDark) darkParticleColors else lightParticleColors

        // More circular particles for richer effect
        repeat(6 + Random.nextInt(5)) {
            val startAngle = Random.nextFloat() * 2f * PI.toFloat()
            val radius = orbRadius + dp(6f + Random.nextFloat() * 18f)
            val direction = if (Random.nextBoolean()) 1f else -1f
            val angularSpeed = direction * (0.035f + Random.nextFloat() * 0.03f)

            particles.add(Particle(
                x = 0f, y = 0f,
                vx = 0f, vy = 0f,
                size = dp(1f + Random.nextFloat() * 1.6f),
                life = 0f,
                maxLife = 1000f + Random.nextFloat() * 800f,
                alpha = 0.45f + Random.nextFloat() * 0.35f,
                color = colors[Random.nextInt(colors.size)],
                slow = false,
                isCircular = true,
                angle = startAngle,
                radius = radius,
                angularSpeed = angularSpeed
            ))
        }

        while (particles.size > 300) particles.removeAt(0)
    }

    private fun emitDustBurst() {
        val cx = width / 2f
        val cy = height / 2f
        val colors = if (palette.isDark) darkParticleColors else lightParticleColors

        // More burst particles for impressive effect
        repeat(80) {
            val angle = Random.nextFloat() * 2f * PI.toFloat()
            val dist = dp(25f + Random.nextFloat() * 65f)
            val speed = dist / 12f

            particles.add(Particle(
                x = cx + cos(angle) * orbRadius,
                y = cy + sin(angle) * orbRadius,
                vx = cos(angle) * speed,
                vy = sin(angle) * speed,
                size = dp(0.8f + Random.nextFloat() * 1.4f),
                life = 0f,
                maxLife = 600f + Random.nextFloat() * 500f,
                alpha = 0.4f + Random.nextFloat() * 0.3f,
                color = colors[Random.nextInt(colors.size)],
                slow = false
            ))
        }

        while (particles.size > 300) particles.removeAt(0)
        invalidate()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Animation
    // ═══════════════════════════════════════════════════════════════════════════

    private fun startMasterAnimation() {
        if (masterAnimator?.isRunning == true) return

        masterAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Long.MAX_VALUE
            interpolator = LinearInterpolator()

            var lastTime = SystemClock.elapsedRealtime()
            var lastDrawTime = 0L
            val frameInterval = 33L  // ~30fps for better battery life

            addUpdateListener {
                val now = SystemClock.elapsedRealtime()
                val dt = (now - lastTime) / 1000f
                lastTime = now

                // Update all animation phases (smooth looping)
                blobPhase = (blobPhase + dt / 9f) % 1f        // 9s blob morph
                swirlPhase = (swirlPhase + dt / 20f) % 1f     // 20s mist rotation
                breathePhase = (breathePhase + dt / 4f) % 1f  // 4s outer glow
                corePhase = (corePhase + dt / 7f) % 1f        // 7s core glow

                if (state == VpnState.Starting || state == VpnState.Stopping) {
                    spinPhase = (spinPhase + dt / 1.1f) % 1f
                }

                // Throttle redraws to ~30fps for better performance
                if (now - lastDrawTime >= frameInterval) {
                    lastDrawTime = now
                    invalidate()
                }
            }
            start()
        }
    }

    private fun animatePress(targetScale: Float) {
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(pressScale, targetScale).apply {
            duration = 100
            interpolator = PathInterpolator(0.1f, 0.9f, 0.2f, 1f)
            addUpdateListener {
                pressScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun animatePressRelease() {
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(pressScale, 1.02f, 1f).apply {
            duration = 280
            interpolator = PathInterpolator(0.2f, 0f, 0.2f, 1f)
            addUpdateListener {
                pressScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun animateShade(targetOpacity: Float) {
        shadeAnimator?.cancel()
        shadeAnimator = ValueAnimator.ofFloat(shadeOpacity, targetOpacity).apply {
            duration = if (targetOpacity > 0) 100 else 250
            addUpdateListener {
                shadeOpacity = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun cancelAllAnimations() {
        transitionAnimator?.cancel()
        masterAnimator?.cancel()
        pressAnimator?.cancel()
        shadeAnimator?.cancel()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
}
