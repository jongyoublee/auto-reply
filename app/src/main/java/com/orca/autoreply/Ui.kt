package com.orca.autoreply

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** White theme with a single black accent; swap ACCENT/ON_ACCENT/HALO_ON to change the accent color. */
object Colors {
    val BG = 0xFFFFFFFF.toInt()
    val SURFACE = 0xFFF6F8FB.toInt()
    val CARD = 0xFFFFFFFF.toInt()
    val CHIP = 0xFFF1F4F9.toInt()
    val BORDER = 0xFFE6EAF2.toInt()
    val FIELD_BORDER = 0xFFD9DFEA.toInt()
    val DASH = 0xFFC9D1DF.toInt()

    val TEXT = 0xFF111827.toInt()
    val BODY = 0xFF1F2937.toInt()
    val MUTED = 0xFF5B6472.toInt()
    val SUBTLE = 0xFF9AA3B2.toInt()

    val ACCENT = 0xFF111827.toInt()
    val ON_ACCENT = 0xFFFFFFFF.toInt()
    val HALO_ON = 0xFFE5E7EB.toInt()

    val OFF = 0xFF8590A2.toInt()
    val OFF_FILL = 0xFFDDE2EB.toInt()
    val OFF_ICON = 0xFF5F6B7E.toInt()
    val HALO_OFF = 0xFFEDF0F5.toInt()

    val DANGER = 0xFFB91C1C.toInt()
    val DANGER_BG = 0xFFFEF2F2.toInt()
    val DANGER_BORDER = 0xFFFCA5A5.toInt()

    val GREEN_BG = 0xFFDCFCE7.toInt()
    val GREEN_FG = 0xFF15803D.toInt()
    val AMBER_BG = 0xFFFEF3C7.toInt()
    val AMBER_FG = 0xFFB45309.toInt()
    val RED_BG = 0xFFFEE2E2.toInt()
    val RED_FG = 0xFFB91C1C.toInt()
    val NEUTRAL_BG = 0xFFEEF1F6.toInt()
    val NEUTRAL_FG = 0xFF4B5563.toInt()
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.roundBg(fill: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

fun ovalBg(fill: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(fill)
}

fun Context.lp(
    w: Int = ViewGroup.LayoutParams.MATCH_PARENT,
    h: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
    top: Int = 0,
    weight: Float = 0f,
) = LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }

fun Context.column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun Context.row() = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

/** White card with a hairline border and a very light shadow. */
fun Context.card(padDp: Int = 16): LinearLayout = column().apply {
    background = roundBg(Colors.CARD, 16, Colors.BORDER)
    elevation = dp(1).toFloat()
    setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
}

fun Context.label(text: CharSequence, sp: Float = 14f, color: Int = Colors.TEXT, bold: Boolean = false) =
    TextView(this).apply {
        this.text = text
        textSize = sp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

fun Context.iconButton(
    iconRes: Int,
    desc: String,
    tint: Int = Colors.BODY,
    bg: Drawable = roundBg(Colors.CHIP, 14),
    onClick: () -> Unit,
) = ImageButton(this).apply {
    setImageResource(iconRes)
    contentDescription = desc
    setColorFilter(tint)
    background = bg
    scaleType = ImageView.ScaleType.CENTER
    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    setOnClickListener { onClick() }
}

fun Context.pill(
    text: String,
    filled: Boolean,
    color: Int = Colors.ACCENT,
    stroke: Int? = null,
    onClick: () -> Unit,
) = Button(this).apply {
    this.text = text
    isAllCaps = false
    stateListAnimator = null
    textSize = 15f
    setTypeface(typeface, Typeface.BOLD)
    minHeight = dp(52)
    minimumHeight = dp(52)
    setPadding(dp(18), 0, dp(18), 0)
    if (filled) {
        setTextColor(Colors.ON_ACCENT)
        background = roundBg(color, 26)
    } else {
        setTextColor(color)
        background = roundBg(Colors.BG, 26, stroke ?: color)
    }
    setOnClickListener { onClick() }
}

/** Equal-width action button: icon + label centered together. */
fun Context.iconLabelButton(iconRes: Int, text: String, color: Int, fill: Int, onClick: () -> Unit) =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = roundBg(fill, 12)
        isClickable = true
        isFocusable = true
        contentDescription = text
        minimumHeight = dp(44)
        addView(ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(color)
        }, LinearLayout.LayoutParams(dp(18), dp(18)))
        addView(label(text, 13f, color, true), lp(w = ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        setOnClickListener { onClick() }
    }

/**
 * Pill switch with a solid track. The platform Switch draws its track at ~30% alpha when tinted,
 * which makes the on state look washed out, so the track and thumb are drawn explicitly.
 */
fun Context.appSwitch(checked: Boolean, desc: String, onChange: (Boolean) -> Unit): FrameLayout {
    var on = checked
    val knob = View(this).apply {
        background = ovalBg(Color.WHITE)
        elevation = dp(1).toFloat()
    }
    val track = FrameLayout(this).apply {
        addView(
            knob,
            FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = dp(3) },
        )
    }
    fun render() {
        track.background = roundBg(if (on) Colors.ACCENT else Colors.OFF, 13)
        knob.translationX = if (on) dp(18).toFloat() else 0f
    }
    render()
    // 52x44dp touch target around a 44x26dp track.
    return FrameLayout(this).apply {
        isClickable = true
        isFocusable = true
        contentDescription = desc
        layoutParams = LinearLayout.LayoutParams(dp(52), dp(44))
        addView(track, FrameLayout.LayoutParams(dp(44), dp(26), Gravity.CENTER))
        setOnClickListener {
            on = !on
            render()
            onChange(on)
        }
    }
}

fun Context.field(hint: String, value: String, lines: Int = 1) = EditText(this).apply {
    setText(value)
    this.hint = hint
    setHintTextColor(Colors.SUBTLE)
    setTextColor(Colors.TEXT)
    textSize = 15f
    background = roundBg(Colors.SURFACE, 12, Colors.FIELD_BORDER)
    setPadding(dp(14), dp(12), dp(14), dp(12))
    if (lines > 1) {
        minLines = lines
        gravity = Gravity.TOP
    }
}

/** Colored badge with a direction arrow: up-right for outgoing, down-left for incoming. */
fun Context.statusBadge(s: CallStatus, sizeDp: Int = 40): FrameLayout {
    val (bg, fg) = when (s) {
        CallStatus.OUT_ANSWERED, CallStatus.IN_ANSWERED -> Colors.GREEN_BG to Colors.GREEN_FG
        CallStatus.OUT_UNANSWERED -> Colors.AMBER_BG to Colors.AMBER_FG
        CallStatus.IN_MISSED -> Colors.RED_BG to Colors.RED_FG
    }
    val incoming = s == CallStatus.IN_ANSWERED || s == CallStatus.IN_MISSED
    return FrameLayout(this).apply {
        background = roundBg(bg, if (sizeDp >= 40) 12 else 10)
        addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_arrow)
                setColorFilter(fg)
                rotation = if (incoming) 180f else 0f
            },
            FrameLayout.LayoutParams(dp(sizeDp / 2), dp(sizeDp / 2), Gravity.CENTER),
        )
    }
}
