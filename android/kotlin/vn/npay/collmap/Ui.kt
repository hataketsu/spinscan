package vn.npay.collmap

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Shared look for the screens. Views are built in code, so there is no layout
 * XML to compile and the whole app stays a kotlinc + d8 build.
 */
object Ui {
    val GROUND = Color.parseColor("#0B0F14")
    val PANEL = Color.parseColor("#141A22")
    val PANEL2 = Color.parseColor("#1B232D")
    val LINE = Color.parseColor("#2A3441")
    val INK = Color.parseColor("#E6EDF5")
    val DIM = Color.parseColor("#8496A8")
    val AMBER = Color.parseColor("#FFB449")
    val BLUE = Color.parseColor("#5B7FFF")
    val RED = Color.parseColor("#FF5C4D")
    val GREEN = Color.parseColor("#4ED6A1")

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun Context.dp(v: Float): Int = Math.round(
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics))

    fun box(ctx: Context, fill: Int, stroke: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = ctx.dp(radiusDp).toFloat()
            if (stroke != 0) setStroke(ctx.dp(1f), stroke)
        }

    fun text(ctx: Context, s: String, sp: Float, color: Int = INK) = TextView(ctx).apply {
        text = s
        textSize = sp
        setTextColor(color)
    }

    fun mono(ctx: Context, s: String, sp: Float, color: Int = INK) =
        text(ctx, s, sp, color).apply { typeface = android.graphics.Typeface.MONOSPACE }

    fun label(ctx: Context, s: String) = text(ctx, s, 11f, DIM).apply {
        isAllCaps = true
        letterSpacing = 0.08f
        setPadding(0, ctx.dp(14f), 0, ctx.dp(4f))
    }

    fun button(ctx: Context, s: String, accent: Int = 0) = Button(ctx).apply {
        text = s
        isAllCaps = false
        textSize = 15f
        setTextColor(if (accent == 0) INK else accent)
        background = box(ctx, PANEL2, if (accent == 0) LINE else accent, 10f)
        setPadding(ctx.dp(16f), ctx.dp(12f), ctx.dp(16f), ctx.dp(12f))
        stateListAnimator = null
    }

    fun input(ctx: Context, hint: String, value: String) = EditText(ctx).apply {
        this.hint = hint
        setText(value)
        isSingleLine = true
        textSize = 15f
        setTextColor(INK)
        setHintTextColor(DIM)
        background = box(ctx, PANEL2, LINE, 10f)
        setPadding(ctx.dp(14f), ctx.dp(12f), ctx.dp(14f), ctx.dp(12f))
    }

    fun column(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
    }

    fun row(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun lp(w: Int, h: Int, weight: Float = 0f, topDp: Int = 0, ctx: Context? = null) =
        LinearLayout.LayoutParams(w, h, weight).apply {
            if (ctx != null && topDp != 0) setMargins(0, ctx.dp(topDp.toFloat()), 0, 0)
        }

    fun gap(ctx: Context, widthDp: Float) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ctx.dp(widthDp), 1)
    }
}
