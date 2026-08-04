package com.whitedns.vpn

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import java.util.Locale

internal lateinit var WhiteDnsDisplayTypeface: Typeface
    private set
internal lateinit var WhiteDnsBodyTypeface: Typeface
    private set
internal lateinit var WhiteDnsBodyBoldTypeface: Typeface
    private set
internal lateinit var WhiteDnsDataTypeface: Typeface
    private set

internal fun initializeWhiteDnsTypefaces(context: Context) {
    val family = ResourcesCompat.getFont(context, R.font.vazirmatn) ?: return
    WhiteDnsDisplayTypeface = Typeface.create(family, Typeface.BOLD)
    WhiteDnsBodyTypeface = Typeface.create(family, Typeface.NORMAL)
    WhiteDnsBodyBoldTypeface = Typeface.create(family, Typeface.BOLD)
    WhiteDnsDataTypeface = Typeface.create(family, Typeface.BOLD)
}

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
    val onStateFill: Int,
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
        background = 0xFFF1F9F5.toInt(),
        surface = 0xFFF8FDFB.toInt(),
        surfaceElevated1 = 0xFFECF6F1.toInt(),
        surfaceElevated2 = 0xFFE0EDE7.toInt(),
        surfaceVariant = 0xFFD3E2DB.toInt(),
        textPrimary = 0xFF0A1410.toInt(),
        textSecondary = 0xFF495650.toInt(),
        textTertiary = 0xFF717E78.toInt(),
        neutral = 0xFF394640.toInt(),
        outline = 0xFFB9C8C1.toInt(),
        teal = 0xFF007E50.toInt(),
        amber = 0xFFD08B00.toInt(),
        red = 0xFFCF4040.toInt(),
        onAccent = 0xFFF4FAF7.toInt(),
        onProminent = 0xFFF4FAF7.toInt(),
        onStateFill = 0xFFF4FAF7.toInt(),
        brandPillBackground = 0xFFD6EFE3.toInt(),
        brandPillOutline = 0xFF73B598.toInt(),
        amberTrack = 0xFFFCE4C4.toInt(),
        redTrack = 0xFFFFDEDB.toInt(),
        idleRing = 0xFF97AAA1.toInt(),
        majorTick = 0xFF738D81.toInt(),
        tealGradientStart = 0xFF007E50.toInt(),
        tealGradientEnd = 0xFF4BAE87.toInt(),
        amberGradientStart = 0xFFE8AA4E.toInt(),
        amberGradientEnd = 0xFFC37F00.toInt(),
        redGradientStart = 0xFFE97871.toInt(),
        redGradientEnd = 0xFFCF4040.toInt(),
    )

    private val Dark = WhiteDnsPalette(
        isDark = true,
        background = 0xFF050E09.toInt(),
        surface = 0xFF09140F.toInt(),
        surfaceElevated1 = 0xFF0F1B16.toInt(),
        surfaceElevated2 = 0xFF15231D.toInt(),
        surfaceVariant = 0xFF1B2A23.toInt(),
        textPrimary = 0xFFE4EEE9.toInt(),
        textSecondary = 0xFF9BA8A2.toInt(),
        textTertiary = 0xFF717E78.toInt(),
        neutral = 0xFF89968F.toInt(),
        outline = 0xFF273730.toInt(),
        teal = 0xFF3FBE90.toInt(),
        amber = 0xFFE8AA4E.toInt(),
        red = 0xFFF07F77.toInt(),
        onAccent = 0xFF02130C.toInt(),
        onProminent = 0xFF020705.toInt(),
        onStateFill = 0xFFF4FAF7.toInt(),
        brandPillBackground = 0xFF09251A.toInt(),
        brandPillOutline = 0xFF1F634A.toInt(),
        amberTrack = 0xFF3F2903.toInt(),
        redTrack = 0xFF442321.toInt(),
        idleRing = 0xFF273730.toInt(),
        majorTick = 0xFF3B5348.toInt(),
        tealGradientStart = 0xFF3FBE90.toInt(),
        tealGradientEnd = 0xFF71CDA7.toInt(),
        amberGradientStart = 0xFFF0B96B.toInt(),
        amberGradientEnd = 0xFFD99A35.toInt(),
        redGradientStart = 0xFFFF9E96.toInt(),
        redGradientEnd = 0xFFF07F77.toInt(),
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
    private val evaluator = ArgbEvaluator()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = WhiteDnsDisplayTypeface
        textLocale = resources.configuration.locales[0]
    }
    private val fieldPath = Path()
    private val markPath = Path()
    private val iconBounds = RectF()
    private var state: VpnState = VpnState.Stopped
    private var fromAccent = stateAccent(state)
    private var toAccent = fromAccent
    private var transitionProgress = 1f
    private var motionPhase = 0f
    private var transitionAnimator: ValueAnimator? = null
    private var motionAnimator: ValueAnimator? = null

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    fun setVpnState(state: VpnState) {
        if (this.state == state) return
        fromAccent = currentAccent()
        this.state = state
        toAccent = stateAccent(state)
        transitionAnimator?.cancel()
        if (ValueAnimator.areAnimatorsEnabled()) {
            transitionProgress = 0f
            transitionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 320L
                interpolator = PathInterpolator(0.16f, 1f, 0.3f, 1f)
                addUpdateListener {
                    transitionProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            transitionProgress = 1f
        }
        syncMotion(restart = true)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncMotion(restart = true)
    }

    override fun onDetachedFromWindow() {
        transitionAnimator?.cancel()
        motionAnimator?.cancel()
        transitionAnimator = null
        motionAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) syncMotion(restart = false) else motionAnimator?.cancel()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(328f).toInt(), widthMeasureSpec),
            resolveSize(dp(188f).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val alpha = if (isEnabled) 255 else 112
        val scale = if (isPressed) 0.985f else 1f
        val inset = if (isPressed) dp(2f) else dp(1f)
        val right = width - inset
        val bottom = height - inset
        val accent = currentAccent()

        canvas.save()
        canvas.scale(scale, scale, width / 2f, height / 2f)
        setFieldPath(inset, inset, right, bottom)
        fillPaint.color = if (usesStateFill()) {
            alphaColor(accent, if (palette.isDark) 163 * alpha / 255 else alpha)
        } else {
            alphaColor(
                if (isHovered || isPressed) palette.surfaceElevated2 else palette.surfaceElevated1,
                alpha,
            )
        }
        canvas.drawPath(fieldPath, fillPaint)
        strokePaint.strokeWidth = dp(if (isFocused) 2f else 1f)
        strokePaint.color = alphaColor(
            when {
                usesStateFill() -> palette.onStateFill
                isFocused || isHovered -> accent
                else -> palette.outline
            },
            if (usesStateFill() && !isFocused) 112 * alpha / 255 else alpha,
        )
        canvas.drawPath(fieldPath, strokePaint)

        val isRtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val nodeX = if (isRtl) dp(64f) else width - dp(64f)
        val labelLeft = if (isRtl) nodeX + dp(56f) else dp(24f)
        val labelRight = if (isRtl) width - dp(24f) else nodeX - dp(56f)
        val labelCenterX = (labelLeft + labelRight) / 2f
        drawActionNode(canvas, nodeX, height / 2f, accent, alpha)
        drawActionLabel(
            canvas,
            labelCenterX,
            height / 2f,
            (labelRight - labelLeft).coerceAtLeast(dp(72f)),
            alpha,
        )
        canvas.restore()
    }

    private fun setFieldPath(left: Float, top: Float, right: Float, bottom: Float) {
        fieldPath.reset()
        fieldPath.addRoundRect(
            left,
            top,
            right,
            bottom,
            floatArrayOf(
                dp(32f), dp(32f),
                dp(8f), dp(8f),
                dp(32f), dp(32f),
                dp(8f), dp(8f),
            ),
            Path.Direction.CW,
        )
    }

    private fun drawActionLabel(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        maxWidth: Float,
        alpha: Int,
    ) {
        val label = actionLabel()
        val preferredSize = sp(28f)
        titlePaint.textSize = preferredSize
        val measuredWidth = titlePaint.measureText(label)
        if (measuredWidth > maxWidth) {
            titlePaint.textSize = preferredSize * (maxWidth / measuredWidth)
        }
        titlePaint.textAlign = Paint.Align.CENTER
        titlePaint.color = alphaColor(
            if (usesStateFill()) palette.onStateFill else palette.textPrimary,
            alpha,
        )
        val metrics = titlePaint.fontMetrics
        val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
        canvas.drawTextRun(
            label,
            0,
            label.length,
            0,
            label.length,
            centerX,
            baseline,
            layoutDirection == View.LAYOUT_DIRECTION_RTL,
            titlePaint,
        )
    }

    private fun drawActionNode(canvas: Canvas, cx: Float, cy: Float, accent: Int, alpha: Int) {
        val nodeColor = if (isEnabled) accent else palette.surfaceVariant
        fillPaint.color = if (usesStateFill()) {
            alphaColor(palette.onStateFill, 42 * alpha / 255)
        } else {
            alphaColor(nodeColor, alpha)
        }
        canvas.drawCircle(cx, cy, dp(34f), fillPaint)
        strokePaint.strokeWidth = dp(2.8f)
        strokePaint.color = alphaColor(
            if (usesStateFill()) palette.onStateFill else markColor(),
            alpha,
        )
        when (state) {
            VpnState.Started -> drawCheck(canvas, cx, cy)
            VpnState.Starting, VpnState.Stopping -> drawLoader(canvas, cx, cy)
            is VpnState.Error, VpnState.DailyLimitReached -> drawExclamation(canvas, cx, cy)
            VpnState.Stopped -> drawPower(canvas, cx, cy)
        }
    }

    private fun drawCheck(canvas: Canvas, cx: Float, cy: Float) {
        markPath.reset()
        markPath.moveTo(cx - dp(10f), cy)
        markPath.lineTo(cx - dp(3f), cy + dp(7f))
        markPath.lineTo(cx + dp(11f), cy - dp(8f))
        canvas.drawPath(markPath, strokePaint)
    }

    private fun drawLoader(canvas: Canvas, cx: Float, cy: Float) {
        iconBounds.set(cx - dp(11f), cy - dp(11f), cx + dp(11f), cy + dp(11f))
        canvas.drawArc(iconBounds, -90f + motionPhase * 360f, 235f, false, strokePaint)
    }

    private fun drawExclamation(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawLine(cx, cy - dp(8f), cx, cy + dp(3f), strokePaint)
        fillPaint.color = strokePaint.color
        canvas.drawCircle(cx, cy + dp(10f), dp(1.6f), fillPaint)
    }

    private fun drawPower(canvas: Canvas, cx: Float, cy: Float) {
        iconBounds.set(cx - dp(11f), cy - dp(9f), cx + dp(11f), cy + dp(13f))
        canvas.drawArc(iconBounds, -40f, 260f, false, strokePaint)
        canvas.drawLine(cx, cy - dp(13f), cx, cy, strokePaint)
    }

    private fun syncMotion(restart: Boolean) {
        if (!isAttachedToWindow || !isShown || !ValueAnimator.areAnimatorsEnabled()) return
        if (!hasLoadingMotion()) {
            motionAnimator?.cancel()
            motionAnimator = null
            motionPhase = 0f
            return
        }
        if (!restart && motionAnimator?.isRunning == true) return
        motionAnimator?.cancel()
        motionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_100L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                motionPhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun hasLoadingMotion(): Boolean =
        state == VpnState.Starting || state == VpnState.Stopping

    private fun usesStateFill(): Boolean =
        state == VpnState.Started || state is VpnState.Error || state == VpnState.DailyLimitReached

    private fun stateAccent(state: VpnState): Int = when (state) {
        VpnState.Started -> palette.teal
        VpnState.Starting, VpnState.Stopping -> palette.amber
        is VpnState.Error, VpnState.DailyLimitReached -> palette.red
        VpnState.Stopped -> palette.textPrimary
    }

    private fun actionLabel(): String = when (state) {
        VpnState.Started -> resources.getString(R.string.connect_action_disconnect)
        VpnState.Starting -> resources.getString(R.string.connect_action_connecting)
        VpnState.Stopping -> resources.getString(R.string.connect_action_disconnecting)
        is VpnState.Error -> resources.getString(R.string.connect_action_retry)
        VpnState.DailyLimitReached -> resources.getString(R.string.connect_action_usage_limit)
        VpnState.Stopped -> resources.getString(R.string.connect_action_connect)
    }

    private fun markColor(): Int = when (state) {
        VpnState.Starting, VpnState.Stopping ->
            if (palette.isDark) palette.onAccent else palette.textPrimary
        VpnState.Stopped -> palette.onProminent
        else -> palette.onAccent
    }

    private fun currentAccent(): Int =
        evaluator.evaluate(transitionProgress.coerceIn(0f, 1f), fromAccent, toAccent) as Int

    private fun alphaColor(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
}

class DashboardDataRowView(context: Context) : LinearLayout(context) {
    private val palette = WhiteDnsDesignTokens.forContext(context)
    private val labelText = TextView(context).apply {
        textSize = 12f
        typeface = WhiteDnsBodyBoldTypeface
        setTextColor(palette.textSecondary)
        letterSpacing = 0.08f
        includeFontPadding = false
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.START
    }
    private val valueText = TextView(context).apply {
        textSize = 14f
        typeface = WhiteDnsBodyBoldTypeface
        setTextColor(palette.textPrimary)
        includeFontPadding = false
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.START
    }
    private val chevronText = TextView(context).apply {
        setText(R.string.chevron_forward)
        textSize = 22f
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        typeface = WhiteDnsBodyBoldTypeface
        setTextColor(palette.teal)
        includeFontPadding = false
        isSingleLine = true
        gravity = Gravity.CENTER
    }

    init {
        orientation = HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(72)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        val selectableBackground = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, selectableBackground, true)) {
            setBackgroundResource(selectableBackground.resourceId)
        }
        addView(
            LinearLayout(context).apply {
                orientation = VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                addView(labelText, LayoutParams(-1, -2))
                addView(valueText, LayoutParams(-1, -2).apply { topMargin = dp(8) })
            },
            LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            chevronText,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginStart = dp(12)
            },
        )
    }

    fun setRow(label: String, value: CharSequence) {
        labelText.text = label.uppercase()
        setValue(value)
    }

    fun setValue(value: CharSequence) {
        valueText.text = value
    }

    override fun getAccessibilityClassName(): CharSequence =
        if (isClickable) Button::class.java.name else super.getAccessibilityClassName()

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
        chevronText.isEnabled = enabled
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
