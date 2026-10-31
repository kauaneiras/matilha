package com.matilha.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*

/** Paleta com contraste AA (texto ≥ 4.5:1) em claro e escuro. */
data class Palette(
    val bg: Int, val surface: Int, val surfaceAlt: Int, val outline: Int,
    val text: Int, val textMuted: Int, val primary: Int, val onPrimary: Int, val primarySoft: Int,
    val ok: Int, val okSoft: Int, val warn: Int, val warnSoft: Int, val danger: Int, val dangerSoft: Int,
    val info: Int, val infoSoft: Int, val accent: Int,
)

object Ui {
    val light = Palette(
        bg = 0xFFF6F4EF.toInt(), surface = Color.WHITE, surfaceAlt = 0xFFEFEBE3.toInt(), outline = 0xFFCFC8BB.toInt(),
        text = 0xFF1F1B16.toInt(), textMuted = 0xFF5B544A.toInt(), primary = 0xFF1D6B57.toInt(), onPrimary = Color.WHITE,
        primarySoft = 0xFFD5EDE5.toInt(), ok = 0xFF1E6B2E.toInt(), okSoft = 0xFFDDF1DF.toInt(), warn = 0xFF8A5100.toInt(),
        warnSoft = 0xFFFFF0CC.toInt(), danger = 0xFFB3261E.toInt(), dangerSoft = 0xFFFCE3E1.toInt(), info = 0xFF1F5FA8.toInt(),
        infoSoft = 0xFFDCE9F8.toInt(), accent = 0xFFC2551B.toInt(),
    )
    val dark = Palette(
        bg = 0xFF141311.toInt(), surface = 0xFF201E1B.toInt(), surfaceAlt = 0xFF2B2925.toInt(), outline = 0xFF4A463F.toInt(),
        text = 0xFFF1ECE4.toInt(), textMuted = 0xFFC2BBAF.toInt(), primary = 0xFF7FD6BC.toInt(), onPrimary = 0xFF00382B.toInt(),
        primarySoft = 0xFF1E3D34.toInt(), ok = 0xFF8EDB9B.toInt(), okSoft = 0xFF1D3522.toInt(), warn = 0xFFFFC56B.toInt(),
        warnSoft = 0xFF3D2E12.toInt(), danger = 0xFFFFB4AB.toInt(), dangerSoft = 0xFF4A1C18.toInt(), info = 0xFFA6C8FF.toInt(),
        infoSoft = 0xFF1B2F4A.toInt(), accent = 0xFFFFB68F.toInt(),
    )

    var c: Palette = light
        private set

    fun init(ctx: Context) {
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        c = if (night) dark else light
    }
}

val C: Palette get() = Ui.c

fun Context.dp(v: Number): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
fun View.dp(v: Number): Int = context.dp(v)

enum class TS(val sp: Float, val bold: Boolean) { H1(24f, true), H2(19f, true), H3(16.5f, true), BODY(16f, false), SMALL(14f, false), LABEL(14f, true) }

fun rounded(color: Int, radiusDp: Float, ctx: Context, strokeColor: Int? = null, strokeDp: Float = 1f): GradientDrawable =
    GradientDrawable().apply {
        setColor(color); cornerRadius = ctx.dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(ctx.dp(strokeDp), strokeColor)
    }

fun ripple(content: Drawable, ctx: Context, radiusDp: Float = 12f): Drawable =
    RippleDrawable(ColorStateList.valueOf(0x33808080), content, rounded(Color.BLACK, radiusDp, ctx))

fun Context.text(s: CharSequence, style: TS = TS.BODY, color: Int = C.text, block: TextView.() -> Unit = {}): TextView =
    TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, style.sp)
        setTextColor(color)
        if (style.bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(0f, 1.15f)
        if (style == TS.H1 || style == TS.H2 || style == TS.H3) markHeading()
        block()
    }

fun Context.vbox(padDp: Int = 0, spacingDp: Int = 0, block: LinearLayout.() -> Unit = {}): LinearLayout =
    SpacedLinearLayout(this, dp(spacingDp)).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
        block()
    }

fun Context.hbox(spacingDp: Int = 8, block: LinearLayout.() -> Unit = {}): LinearLayout =
    SpacedLinearLayout(this, dp(spacingDp)).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        block()
    }

/** LinearLayout com espaçamento automático entre filhos. */
class SpacedLinearLayout(ctx: Context, private val spacing: Int) : LinearLayout(ctx) {
    override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams) {
        val lp = (params as? LayoutParams) ?: generateLayoutParams(params)
        if (childCount > 0 && spacing > 0) {
            if (orientation == VERTICAL) lp.topMargin = maxOf(lp.topMargin, spacing) else lp.leftMargin = maxOf(lp.leftMargin, spacing)
        }
        super.addView(child, index, lp)
    }
}

fun LinearLayout.add(v: View, w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f): View {
    // Views com tamanho fixo próprio (ex.: colorDot, spacer) mantêm esse tamanho.
    val own = v.layoutParams
    if (own != null && w == ViewGroup.LayoutParams.MATCH_PARENT && h == ViewGroup.LayoutParams.WRAP_CONTENT && weight == 0f && (own.width > 0 || own.height > 0)) {
        addView(v, LinearLayout.LayoutParams(own.width, own.height))
        return v
    }
    val lp = LinearLayout.LayoutParams(if (orientation == LinearLayout.HORIZONTAL && w == ViewGroup.LayoutParams.MATCH_PARENT && weight == 0f) ViewGroup.LayoutParams.WRAP_CONTENT else w, h, weight)
    addView(v, lp)
    return v
}

fun LinearLayout.addWeighted(v: View): View = add(v, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

enum class BtnKind { PRIMARY, SECONDARY, DANGER, GHOST, WARN }

fun Context.button(label: String, kind: BtnKind = BtnKind.PRIMARY, enabled: Boolean = true, onClick: () -> Unit): Button =
    Button(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = dp(48); minimumHeight = dp(48)
        stateListAnimator = null
        setPadding(dp(16), dp(10), dp(16), dp(10))
        val (bg, fg, stroke) = when (kind) {
            BtnKind.PRIMARY -> Triple(C.primary, C.onPrimary, null)
            BtnKind.SECONDARY -> Triple(C.surface, C.primary, C.primary)
            BtnKind.DANGER -> Triple(C.dangerSoft, C.danger, C.danger)
            BtnKind.WARN -> Triple(C.warnSoft, C.warn, C.warn)
            BtnKind.GHOST -> Triple(Color.TRANSPARENT, C.primary, null)
        }
        background = ripple(rounded(if (enabled) bg else C.surfaceAlt, 12f, context, if (enabled) stroke else C.outline), context)
        setTextColor(if (enabled) fg else C.textMuted)
        isEnabled = enabled
        setOnClickListener { if (isEnabled) onClick() }
    }

/** Chip alternável: anunciado como caixa de seleção pelo TalkBack. */
fun Context.chip(label: String, selected: Boolean, onToggle: (Boolean) -> Unit): TextView =
    text(label, TS.SMALL) {
        var sel = selected
        fun paint() {
            background = ripple(rounded(if (sel) C.primarySoft else C.surface, 20f, context, if (sel) C.primary else C.outline, if (sel) 2f else 1f), context, 20f)
            setTextColor(if (sel) C.primary else C.text)
            typeface = if (sel) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
            text = if (sel) "✓ $label" else label
        }
        minHeight = dp(44); gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(8), dp(14), dp(8))
        isClickable = true; isFocusable = true
        contentDescription = label
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = CheckBox::class.java.name
                info.isCheckable = true; info.isChecked = sel
            }
        }
        paint()
        setOnClickListener { sel = !sel; paint(); onToggle(sel) }
    }

/** Grupo de escolha única em chips. */
fun <T> Context.singleChoice(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit): FlowLayout {
    val flow = FlowLayout(this, dp(8), dp(8))
    var current = selected
    val views = mutableListOf<TextView>()
    fun repaint() {
        views.forEachIndexed { i, v ->
            val sel = options[i] == current
            v.background = ripple(rounded(if (sel) C.primary else C.surface, 20f, this, if (sel) C.primary else C.outline), this, 20f)
            v.setTextColor(if (sel) C.onPrimary else C.text)
            v.text = if (sel) "● ${label(options[i])}" else label(options[i])
        }
    }
    options.forEach { o ->
        val v = text(label(o), TS.SMALL) {
            minHeight = dp(44); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isClickable = true; isFocusable = true
            contentDescription = label(o)
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = RadioButton::class.java.name
                    info.isCheckable = true; info.isChecked = o == current
                }
            }
            setOnClickListener { current = o; repaint(); onSelect(o) }
        }
        views += v
        flow.addView(v)
    }
    repaint()
    return flow
}

fun <T> Context.multiChoice(options: List<T>, selected: Set<T>, label: (T) -> String, onChange: (Set<T>) -> Unit): FlowLayout {
    val flow = FlowLayout(this, dp(8), dp(8))
    val cur = selected.toMutableSet()
    options.forEach { o -> flow.addView(chip(label(o), o in cur) { on -> if (on) cur += o else cur -= o; onChange(cur.toSet()) }) }
    return flow
}

fun Context.card(padDp: Int = 16, spacingDp: Int = 8, color: Int = C.surface, stroke: Int? = C.outline, block: LinearLayout.() -> Unit): LinearLayout =
    vbox(padDp, spacingDp) {
        background = rounded(color, 16f, context, stroke)
        block()
    }

fun Context.clickableCard(onClick: () -> Unit, padDp: Int = 16, block: LinearLayout.() -> Unit): LinearLayout =
    card(padDp) { block() }.apply {
        background = ripple(rounded(C.surface, 16f, context, C.outline), context, 16f)
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

enum class Tone { OK, WARN, DANGER, INFO, NEUTRAL, PRIMARY }

fun toneColors(t: Tone): Pair<Int, Int> = when (t) {
    Tone.OK -> C.okSoft to C.ok
    Tone.WARN -> C.warnSoft to C.warn
    Tone.DANGER -> C.dangerSoft to C.danger
    Tone.INFO -> C.infoSoft to C.info
    Tone.NEUTRAL -> C.surfaceAlt to C.textMuted
    Tone.PRIMARY -> C.primarySoft to C.primary
}

fun Context.badge(label: String, tone: Tone): TextView = text(label, TS.SMALL) {
    val (bg, fg) = toneColors(tone)
    setTextColor(fg)
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    background = rounded(bg, 8f, context)
    setPadding(dp(8), dp(3), dp(8), dp(3))
}

/** Faixa de aviso com ícone textual (nunca só cor). */
fun Context.banner(title: String, body: String?, tone: Tone, icon: String = when (tone) {
    Tone.DANGER -> "⛔"; Tone.WARN -> "⚠️"; Tone.OK -> "✅"; Tone.INFO -> "ℹ️"; else -> "•"
}): LinearLayout {
    val (bg, fg) = toneColors(tone)
    return hbox(12) {
        background = rounded(bg, 12f, context, fg, 1f)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        gravity = Gravity.TOP
        add(text(icon, TS.H3) { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        addWeighted(vbox(spacingDp = 2) {
            add(text(title, TS.LABEL, fg))
            if (!body.isNullOrBlank()) add(text(body, TS.SMALL, C.text))
        })
    }
}

fun Context.section(title: String, subtitle: String? = null): LinearLayout = vbox(spacingDp = 2) {
    setPadding(0, dp(8), 0, 0)
    add(text(title, TS.H2))
    if (subtitle != null) add(text(subtitle, TS.SMALL, C.textMuted))
}

fun Context.divider(): View = View(this).apply {
    setBackgroundColor(C.outline)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
}

fun Context.field(label: String, value: String = "", hint: String = "", type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, lines: Int = 1, onChange: (String) -> Unit = {}): LinearLayout =
    vbox(spacingDp = 4) {
        val lbl = add(text(label, TS.LABEL)) as TextView
        val et = EditText(context).apply {
            setText(value)
            this.hint = hint
            inputType = if (lines > 1) type or InputType.TYPE_TEXT_FLAG_MULTI_LINE else type
            if (lines > 1) { minLines = lines; gravity = Gravity.TOP or Gravity.START } else isSingleLine = true
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(C.text); setHintTextColor(C.textMuted)
            background = rounded(C.surface, 10f, context, C.outline)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minHeight = dp(48)
            id = View.generateViewId()
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { onChange(s?.toString() ?: "") }
            })
        }
        (lbl).labelFor = et.id
        add(et)
        tag = et
    }

fun LinearLayout.editText(): EditText = tag as EditText

fun Context.switchRow(label: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit): View = hbox(12) {
    val sw = Switch(context).apply {
        isChecked = checked
        minimumHeight = dp(48); minimumWidth = dp(48)
        contentDescription = label
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }
    addWeighted(vbox(spacingDp = 2) {
        add(text(label, TS.BODY))
        if (desc != null) add(text(desc, TS.SMALL, C.textMuted))
    })
    add(sw)
    minimumHeight = dp(56)
    isClickable = true
    setOnClickListener { sw.toggle() }
}

/** Layout que quebra linha automaticamente (chips). */
class FlowLayout(ctx: Context, private val hGap: Int, private val vGap: Int) : ViewGroup(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val unbounded = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
        var x = 0; var y = 0; var rowH = 0; var usedW = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            if (ch.visibility == GONE) continue
            ch.measure(MeasureSpec.makeMeasureSpec(if (unbounded) 0 else maxW, if (unbounded) MeasureSpec.UNSPECIFIED else MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (!unbounded && x > 0 && x + ch.measuredWidth > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            x += ch.measuredWidth + hGap
            usedW = maxOf(usedW, x - hGap)
            rowH = maxOf(rowH, ch.measuredHeight)
        }
        val w = if (unbounded) usedW + paddingLeft + paddingRight else MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            if (ch.visibility == GONE) continue
            if (x > 0 && x + ch.measuredWidth > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            ch.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + ch.measuredWidth, paddingTop + y + ch.measuredHeight)
            x += ch.measuredWidth + hGap
            rowH = maxOf(rowH, ch.measuredHeight)
        }
    }
}

fun Context.scroll(content: View): ScrollView = ScrollView(this).apply {
    isFillViewport = true
    addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
}

fun Context.spacer(hDp: Int): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(hDp)) }

fun Context.colorDot(color: Int, sizeDp: Int = 12): View = View(this).apply {
    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

/** Marca como cabeçalho para o TalkBack (API 28+; em versões antigas é ignorado). */
fun View.markHeading() { if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }

fun transparent() = ColorDrawable(Color.TRANSPARENT)

// ---- Delegates com receptor View: permitem usar os helpers dentro de blocos vbox { } / card { }.
fun View.text(s: CharSequence, style: TS = TS.BODY, color: Int = C.text, block: TextView.() -> Unit = {}) = context.text(s, style, color, block)
fun View.vbox(padDp: Int = 0, spacingDp: Int = 0, block: LinearLayout.() -> Unit = {}) = context.vbox(padDp, spacingDp, block)
fun View.hbox(spacingDp: Int = 8, block: LinearLayout.() -> Unit = {}) = context.hbox(spacingDp, block)
fun View.card(padDp: Int = 16, spacingDp: Int = 8, color: Int = C.surface, stroke: Int? = C.outline, block: LinearLayout.() -> Unit) = context.card(padDp, spacingDp, color, stroke, block)
fun View.badge(label: String, tone: Tone) = context.badge(label, tone)
fun View.banner(title: String, body: String?, tone: Tone) = context.banner(title, body, tone)
fun View.banner(title: String, body: String?, tone: Tone, icon: String) = context.banner(title, body, tone, icon)
fun View.section(title: String, subtitle: String? = null) = context.section(title, subtitle)
fun View.divider() = context.divider()
fun View.field(label: String, value: String = "", hint: String = "", type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, lines: Int = 1, onChange: (String) -> Unit = {}) =
    context.field(label, value, hint, type, lines, onChange)
fun View.spacer(hDp: Int) = context.spacer(hDp)
fun View.colorDot(color: Int, sizeDp: Int = 12) = context.colorDot(color, sizeDp)
