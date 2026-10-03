package com.whitedns.vpn

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog as MaterialDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout

/** The same Vazirmatn typography, green palette and rounded surfaces as MainActivity. */
internal class EngineFormStyle(private val context: Context) {
    val palette = WhiteDnsDesignTokens.palette(when (AppThemePreferenceStore(context).read()) {
        AppThemeMode.Dark -> true
        AppThemeMode.Light -> false
        AppThemeMode.System -> WhiteDnsDesignTokens.forContext(context).isDark
    })
    fun dp(value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()
    fun column() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(dp(20), dp(8), dp(20), dp(8))
    }
    fun detail(text: CharSequence) = TextView(context).apply {
        this.text = text; textSize = 12f; typeface = WhiteDnsBodyTypeface
        setTextColor(palette.textSecondary); includeFontPadding = false
        setLineSpacing(dp(2).toFloat(), 1f); gravity = Gravity.START
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
    }
    fun field(hint: CharSequence) = TextInputLayout(context).apply {
        this.hint = hint; typeface = WhiteDnsBodyTypeface
        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        boxBackgroundColor = (palette.surface and 0x00ffffff) or ((if (palette.isDark) 232 else 246) shl 24)
        setBoxStrokeColorStateList(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(palette.outline, palette.teal, palette.outline)))
        boxStrokeWidth = dp(1); boxStrokeWidthFocused = dp(1)
        defaultHintTextColor = ColorStateList.valueOf(palette.textSecondary)
        hintTextColor = ColorStateList.valueOf(palette.teal)
        setHelperTextColor(ColorStateList.valueOf(palette.textSecondary))
        setErrorTextColor(ColorStateList.valueOf(palette.red))
        setEndIconTintList(ColorStateList.valueOf(palette.textSecondary))
        setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
    }
    fun button(label: Int, action: () -> Unit) = MaterialButton(context,
        null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        setText(label); isAllCaps = false; typeface = WhiteDnsBodyBoldTypeface; textSize = 14f
        setTextColor(palette.teal); strokeColor = ColorStateList.valueOf(palette.outline)
        backgroundTintList = ColorStateList.valueOf(palette.surface)
        rippleColor = ColorStateList.valueOf(palette.surfaceElevated2)
        cornerRadius = dp(8); minHeight = dp(48)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    }
    fun scroll(body: View) = object : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val cap = (resources.displayMetrics.heightPixels * 0.65f).toInt()
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val bounded = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) cap else minOf(size, cap)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(bounded, MeasureSpec.AT_MOST))
        }
    }.apply { isFillViewport = false; addView(body); clipToPadding = false }

    fun adapter(items: List<String>, dropdown: Boolean = false) = object : ArrayAdapter<String>(
        context, android.R.layout.simple_list_item_1, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = row(position, convertView)
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = row(position, convertView)
        private fun row(position: Int, convertView: View?) = (convertView as? TextView ?: TextView(context)).apply {
            text = getItem(position); textSize = 14f; typeface = WhiteDnsBodyTypeface
            setTextColor(palette.textPrimary); gravity = Gravity.START or Gravity.CENTER_VERTICAL
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            setPaddingRelative(dp(20), dp(12), dp(20), dp(12)); minHeight = dp(48)
            if (dropdown) setBackgroundColor(palette.surface)
        }
    }
}

internal fun MaterialAlertDialogBuilder.setWhiteDnsItems(items: List<String>, listener: DialogInterface.OnClickListener): MaterialAlertDialogBuilder =
    setAdapter(EngineFormStyle(context).adapter(items), listener)

internal fun MaterialAlertDialogBuilder.showWhiteDnsEngineDialog(): MaterialDialog = create().also { it.showWhiteDnsEngineDialog() }

internal fun MaterialDialog.showWhiteDnsEngineDialog() {
    show()
    val style = EngineFormStyle(context); val colors = style.palette
    window?.apply {
        setBackgroundDrawable(GradientDrawable().apply { cornerRadius = style.dp(24).toFloat(); setColor(colors.surface) })
        setLayout(minOf(context.resources.displayMetrics.widthPixels - style.dp(48), style.dp(560)), WindowManager.LayoutParams.WRAP_CONTENT)
    }
    findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.apply {
        typeface = WhiteDnsBodyBoldTypeface; setTextColor(colors.textPrimary)
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
    }
    findViewById<TextView>(android.R.id.message)?.apply {
        typeface = WhiteDnsBodyTypeface; textSize = 14f; setTextColor(colors.textSecondary)
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
    }
    listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach {
        getButton(it)?.apply {
            isAllCaps = false; typeface = WhiteDnsBodyBoldTypeface
            setTextColor(if (it == AlertDialog.BUTTON_POSITIVE) colors.teal else colors.textSecondary)
        }
    }
    listView?.apply { isFocusable = true; requestFocus() }
}
