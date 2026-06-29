package com.whitedns.vpn

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

internal fun formatTransferSpeed(bytesPerSecond: Long): String {
    val speed = bytesPerSecond.coerceAtLeast(0L)
    return when {
        speed < 1_024L -> "$speed B/s"
        speed < 1_024L * 1_024L -> String.format(Locale.US, "%.0f KB/s", speed / 1_024.0)
        else -> String.format(Locale.US, "%.1f MB/s", speed / (1_024.0 * 1_024.0))
    }
}

data class WhiteDnsPalette(
    val isDark: Boolean,
    val background: Int,
    val surface: Int,
    val surfaceElevated1: Int,
    val surfaceElevated2: Int,
    val surfaceVariant: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val textTertiary: Int,
    val neutral: Int,
    val outline: Int,
    val teal: Int,
    val amber: Int,
    val red: Int,
    val onAccent: Int,
    val onProminent: Int,
    val brandPillBackground: Int,
    val brandPillOutline: Int,
    val amberTrack: Int,
    val redTrack: Int,
    val idleRing: Int,
    val majorTick: Int,
    val tealGradientStart: Int,
    val tealGradientEnd: Int,
    val amberGradientStart: Int,
    val amberGradientEnd: Int,
    val redGradientStart: Int,
    val redGradientEnd: Int,
)

object WhiteDnsDesignTokens {
    private val Light = WhiteDnsPalette(
        isDark = false,
        background = 0xFFF8FAFB.toInt(),           // Softer off-white
        surface = 0xFFFFFFFF.toInt(),              // Pure white for cards
        surfaceElevated1 = 0xFFFFFFFF.toInt(),     // White cards
        surfaceElevated2 = 0xFFF7F9FB.toInt(),     // Subtle tinted cards
        surfaceVariant = 0xFFF0F4F8.toInt(),       // Stronger variant
        textPrimary = 0xFF0B1117.toInt(),          // Darker, richer black
        textSecondary = 0xFF64748B.toInt(),        // Modern slate gray
        textTertiary = 0xFF94A3B8.toInt(),         // Lighter tertiary
        neutral = 0xFF475569.toInt(),              // Richer neutral
        outline = 0xFFE2E8F0.toInt(),              // Softer outline
        teal = 0xFF06D6A0.toInt(),                 // Brighter, more modern teal
        amber = 0xFFFF9F1C.toInt(),                // Warmer amber
        red = 0xFFEF476F.toInt(),                  // Modern red
        onAccent = 0xFFFFFFFF.toInt(),
        onProminent = 0xFFFFFFFF.toInt(),
        brandPillBackground = 0xFFE4F6EE.toInt(),
        brandPillOutline = 0xFFA2DCBF.toInt(),
        amberTrack = 0xFFFFEBCC.toInt(),
        redTrack = 0xFFFFD6D3.toInt(),
        idleRing = 0xFFC0CBD6.toInt(),
        majorTick = 0xFFA4BCC9.toInt(),
        tealGradientStart = 0xFF06D6A0.toInt(),
        tealGradientEnd = 0xFF00B4D8.toInt(),
        amberGradientStart = 0xFFFFBE0B.toInt(),
        amberGradientEnd = 0xFFFF9F1C.toInt(),
        redGradientStart = 0xFFFF006E.toInt(),
        redGradientEnd = 0xFFEF476F.toInt(),
    )

    private val Dark = WhiteDnsPalette(
        isDark = true,
        background = 0xFF000000.toInt(),           // Pure black for OLED
        surface = 0xFF0F1419.toInt(),              // Very dark blue-gray for cards
        surfaceElevated1 = 0xFF171D25.toInt(),     // Slightly elevated cards
        surfaceElevated2 = 0xFF1E252D.toInt(),     // More elevated cards
        surfaceVariant = 0xFF1A2128.toInt(),       // Dark variant
        textPrimary = 0xFFF1F5F9.toInt(),          // Bright white
        textSecondary = 0xFF94A3B8.toInt(),        // Consistent slate
        textTertiary = 0xFF64748B.toInt(),         // Darker tertiary
        neutral = 0xFF94A3B8.toInt(),              // Lighter neutral
        outline = 0xFF334155.toInt(),              // Visible in dark mode
        teal = 0xFF34D399.toInt(),                 // Lighter teal for dark bg (emerald)
        amber = 0xFFFBBF24.toInt(),                // Lighter amber
        red = 0xFFF87171.toInt(),                  // Lighter red
        onAccent = 0xFFFFFFFF.toInt(),
        onProminent = 0xFF000000.toInt(),          // Update to match pure black background
        brandPillBackground = 0xFF123625.toInt(),
        brandPillOutline = 0xFF1E6847.toInt(),
        amberTrack = 0xFF4A351E.toInt(),
        redTrack = 0xFF4B2529.toInt(),
        idleRing = 0xFF2A3746.toInt(),
        majorTick = 0xFF3A4A5C.toInt(),
        tealGradientStart = 0xFF34D399.toInt(),
        tealGradientEnd = 0xFF10B981.toInt(),
        amberGradientStart = 0xFFFCD34D.toInt(),
        amberGradientEnd = 0xFFFBBF24.toInt(),
        redGradientStart = 0xFFFCA5A5.toInt(),
        redGradientEnd = 0xFFF87171.toInt(),
    )

    fun palette(isNight: Boolean): WhiteDnsPalette = if (isNight) Dark else Light

    fun forContext(context: Context): WhiteDnsPalette {
        val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return palette(nightMode == Configuration.UI_MODE_NIGHT_YES)
    }
}

class MaxWidthLinearLayout(context: Context) : LinearLayout(context) {
    var maxWidthPx: Int = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidthSpec = if (maxWidthPx > 0) {
            val widthMode = MeasureSpec.getMode(widthMeasureSpec)
            val widthSize = MeasureSpec.getSize(widthMeasureSpec)
            if (widthMode != MeasureSpec.UNSPECIFIED && widthSize > maxWidthPx) {
                MeasureSpec.makeMeasureSpec(maxWidthPx, MeasureSpec.EXACTLY)
            } else {
                widthMeasureSpec
            }
        } else {
            widthMeasureSpec
        }
        super.onMeasure(measuredWidthSpec, heightMeasureSpec)
    }
}

class SignalArcView(context: Context) : View(context) {
    private val palette = WhiteDnsDesignTokens.forContext(context)
    private var state: VpnState = VpnState.Stopped
    private var rotationDegrees = -90f
    private var spinAnimator: ValueAnimator? = null
    private var downloadSpeedLabel: String? = null
    private var uploadSpeedLabel: String? = null

    private val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.outline
        style = Paint.Style.STROKE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val centerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val arcBounds = RectF()
    private val markPath = Path()

    fun setVpnState(state: VpnState) {
        if (this.state == state) return
        this.state = state
        if (state != VpnState.Started) {
            downloadSpeedLabel = null
            uploadSpeedLabel = null
        }
        syncSpin()
        invalidate()
    }

    fun setTransferSpeeds(downloadLabel: String?, uploadLabel: String?) {
        if (downloadSpeedLabel == downloadLabel && uploadSpeedLabel == uploadLabel) return
        downloadSpeedLabel = downloadLabel
        uploadSpeedLabel = uploadLabel
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncSpin()
    }

    override fun onDetachedFromWindow() {
        spinAnimator?.cancel()
        spinAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredSize = dp(220f).toInt()
        val width = resolveSize(desiredSize, widthMeasureSpec)
        val height = resolveSize(desiredSize, heightMeasureSpec)
        val size = min(width, height)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val radius = size * 0.32f
        val outerRadius = radius + dp(26f)
        val accent = accentForState()

        val ringColor = if (state == VpnState.Stopped || state == VpnState.DailyLimitReached) {
            palette.idleRing
        } else {
            palette.outline
        }

        rulePaint.color = ringColor
        rulePaint.strokeWidth = dp(1f)
        canvas.drawCircle(cx, cy, outerRadius, rulePaint)

        for (index in 0 until TICK_COUNT) {
            val angle = (index / TICK_COUNT.toFloat()) * 360f
            val radians = Math.toRadians((angle - 90f).toDouble())
            val startRadius = radius + dp(14f)
            val endRadius = if (index % 9 == 0) radius + dp(22f) else radius + dp(18f)
            tickPaint.color = if (index % 9 == 0) palette.majorTick else ringColor
            tickPaint.strokeWidth = if (index % 9 == 0) dp(2f) else dp(1f)
            canvas.drawLine(
                cx + startRadius * cos(radians).toFloat(),
                cy + startRadius * sin(radians).toFloat(),
                cx + endRadius * cos(radians).toFloat(),
                cy + endRadius * sin(radians).toFloat(),
                tickPaint,
            )
        }

        fillPaint.color = accent
        canvas.drawCircle(cx, cy, radius, fillPaint)

        rulePaint.color = ringColor
        rulePaint.strokeWidth = dp(2f)
        canvas.drawCircle(cx, cy, radius, rulePaint)

        arcPaint.color = palette.onAccent
        arcPaint.strokeWidth = dp(3f)
        arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
        if (state == VpnState.Started) {
            canvas.drawCircle(cx, cy, radius, arcPaint)
            drawCheckmark(canvas, cx, cy, palette.onAccent)
            drawTransferSpeeds(canvas, cx, cy, palette.onAccent)
        } else {
            canvas.drawArc(arcBounds, rotationDegrees, 360f * 0.22f, false, arcPaint)
            drawCenterLabel(canvas, cx, cy, palette.onAccent)
        }
    }

    private fun syncSpin() {
        if (!isAttachedToWindow) return
        if (state == VpnState.Started) {
            spinAnimator?.cancel()
            spinAnimator = null
            rotationDegrees = -90f
            return
        }
        if (spinAnimator?.isStarted == true) return
        spinAnimator = ValueAnimator.ofFloat(-90f, 270f).apply {
            duration = 2_200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                rotationDegrees = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun drawCenterLabel(canvas: Canvas, cx: Float, cy: Float, accent: Int) {
        val label = when (state) {
            VpnState.Starting,
            VpnState.Stopping,
            -> "SCAN"
            is VpnState.Error -> "ERR"
            VpnState.Started -> ""
            VpnState.DailyLimitReached,
            VpnState.Stopped,
            -> "IDLE"
        }
        centerTextPaint.color = accent
        centerTextPaint.textSize = sp(if (label == "SCAN") 12f else 14f)
        val metrics = centerTextPaint.fontMetrics
        canvas.drawText(label, cx, cy - ((metrics.ascent + metrics.descent) / 2f), centerTextPaint)
    }

    private fun drawCheckmark(canvas: Canvas, cx: Float, cy: Float, accent: Int) {
        markPaint.color = accent
        markPaint.strokeWidth = dp(5f)
        markPath.reset()
        markPath.moveTo(cx - dp(25f), cy + dp(2f))
        markPath.lineTo(cx - dp(8f), cy + dp(19f))
        markPath.lineTo(cx + dp(29f), cy - dp(23f))
        canvas.drawPath(markPath, markPaint)
    }

    private fun drawTransferSpeeds(canvas: Canvas, cx: Float, cy: Float, accent: Int) {
        val down = downloadSpeedLabel ?: return
        val up = uploadSpeedLabel ?: return
        centerTextPaint.color = accent
        centerTextPaint.textSize = sp(8f)
        canvas.drawText("DL $down", cx, cy + dp(34f), centerTextPaint)
        canvas.drawText("UL $up", cx, cy + dp(46f), centerTextPaint)
    }

    private fun accentForState(): Int = when (state) {
        VpnState.Started -> palette.teal
        VpnState.Starting,
        VpnState.Stopping,
        -> palette.amber
        is VpnState.Error -> palette.red
        VpnState.DailyLimitReached,
        VpnState.Stopped,
        -> palette.neutral
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
    }

    private companion object {
        const val TICK_COUNT = 36
    }
}

class DashboardDataRowView(context: Context) : LinearLayout(context) {
    private val palette = WhiteDnsDesignTokens.forContext(context)
    private val labelText = TextView(context).apply {
        textSize = 13f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(palette.textSecondary)
        letterSpacing = 0.04f
        includeFontPadding = false
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }
    private val valueText = TextView(context).apply {
        textSize = 16f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(palette.textPrimary)
        includeFontPadding = false
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.END
    }
    private val subText = TextView(context).apply {
        textSize = 12f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        setTextColor(palette.textSecondary)
        includeFontPadding = false
        isSingleLine = true
        gravity = Gravity.END
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(68)
        setPadding(dp(20), dp(20), dp(20), dp(20))
        background = borderDrawable()

        addView(
            labelText,
            LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f),
        )

        val valueColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.END
            addView(
                valueText,
                LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                subText,
                LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(4)
                },
            )
        }
        addView(
            valueColumn,
            LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f),
        )
    }

    fun setRow(label: String, value: CharSequence, sub: CharSequence?) {
        labelText.text = label.uppercase()
        setValue(value, sub)
    }

    fun setValue(value: CharSequence, sub: CharSequence?) {
        valueText.text = value
        subText.text = sub?.toString().orEmpty()
        subText.visibility = if (sub.isNullOrBlank()) GONE else VISIBLE
    }

    fun setSubColor(color: Int) {
        subText.setTextColor(color)
    }

    fun setOnRowClickListener(listener: OnClickListener?) {
        isClickable = listener != null
        isFocusable = listener != null
        setOnClickListener(listener)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.45f
        isClickable = enabled && hasOnClickListeners()
        labelText.isEnabled = enabled
        valueText.isEnabled = enabled
        subText.isEnabled = enabled
    }

    private fun borderDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(palette.surfaceVariant)
            setStroke(dp(1), palette.outline)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

class StatusIndicatorView(context: Context) : View(context) {
    private val palette = WhiteDnsDesignTokens.forContext(context)
    private var state: VpnState = VpnState.Stopped
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.onAccent
        strokeWidth = dp(1.8f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val shieldPath = Path()
    private val markPath = Path()

    fun setVpnState(state: VpnState) {
        this.state = state
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        fillPaint.color = when (state) {
            VpnState.Started -> palette.teal
            VpnState.Starting,
            VpnState.Stopping,
            -> palette.amber
            is VpnState.Error -> palette.red
            VpnState.DailyLimitReached,
            VpnState.Stopped -> palette.neutral
        }

        shieldPath.reset()
        shieldPath.moveTo(w * 0.5f, h * 0.08f)
        shieldPath.cubicTo(w * 0.64f, h * 0.18f, w * 0.8f, h * 0.18f, w * 0.86f, h * 0.23f)
        shieldPath.lineTo(w * 0.8f, h * 0.62f)
        shieldPath.cubicTo(w * 0.77f, h * 0.8f, w * 0.62f, h * 0.9f, w * 0.5f, h * 0.96f)
        shieldPath.cubicTo(w * 0.38f, h * 0.9f, w * 0.23f, h * 0.8f, w * 0.2f, h * 0.62f)
        shieldPath.lineTo(w * 0.14f, h * 0.23f)
        shieldPath.cubicTo(w * 0.2f, h * 0.18f, w * 0.36f, h * 0.18f, w * 0.5f, h * 0.08f)
        shieldPath.close()
        canvas.drawPath(shieldPath, fillPaint)

        markPath.reset()
        if (state is VpnState.Error) {
            canvas.drawLine(w * 0.5f, h * 0.32f, w * 0.5f, h * 0.6f, markPaint)
            canvas.drawPoint(w * 0.5f, h * 0.74f, markPaint)
        } else {
            markPath.moveTo(w * 0.32f, h * 0.53f)
            markPath.lineTo(w * 0.45f, h * 0.66f)
            markPath.lineTo(w * 0.7f, h * 0.38f)
            canvas.drawPath(markPath, markPaint)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
