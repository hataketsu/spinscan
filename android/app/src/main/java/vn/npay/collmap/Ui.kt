package vn.npay.collmap

import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.fragment.app.Fragment
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

/**
 * What is left of the old hand-rolled look: two conversions and a Snackbar.
 *
 * Everything else that used to live here -- the palette, the button background,
 * the input box, the section label -- is now theme attributes and styles under
 * res/values. A view that needs a colour asks the theme for it, which is the
 * whole point: there is one place to change the app's appearance and it is not
 * a Kotlin object.
 */
object Ui {
    fun Context.dp(v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).roundToInt()

    /** Resolves a theme colour attribute, e.g. [com.google.android.material.R.attr.colorPrimary]. */
    fun Context.themeColor(@AttrRes attr: Int): Int {
        val out = TypedValue()
        theme.resolveAttribute(attr, out, true)
        return out.data
    }

    /**
     * Material's Slider defends itself against a value that is not a multiple of
     * its step and throws rather than rounding. Everything the app feeds a
     * slider comes from a continuous stored setting, so it is snapped here.
     */
    fun Slider.setSnapped(raw: Float) {
        val step = if (stepSize > 0f) stepSize else 0f
        val clamped = raw.coerceIn(valueFrom, valueTo)
        value = if (step <= 0f) clamped
        else (valueFrom + ((clamped - valueFrom) / step).roundToInt() * step)
            .coerceIn(valueFrom, valueTo)
    }
}

/** Shows a message on the shell's Snackbar, above the bottom navigation bar. */
fun Fragment.snack(message: String) {
    (activity as? MainActivity)?.snack(message)
}

fun Fragment.snack(resId: Int, vararg args: Any) {
    snack(getString(resId, *args))
}
