package io.github.realkhfan.lumen.ui

import android.app.Activity
import android.content.Context
import android.content.res.AssetManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.realkhfan.lumen.R
import io.github.realkhfan.lumen.compat.Compat
import io.github.realkhfan.lumen.lumen

/** The display face for dates and titles. Body text stays in the phone's own font. */
object Fonts {
    lateinit var display: Typeface
        private set
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val body: Typeface = Typeface.DEFAULT

    fun init(assets: AssetManager) {
        display = build(assets, "'wght' 560, 'opsz' 24, 'wdth' 100") ?: Typeface.DEFAULT_BOLD
    }

    private fun build(assets: AssetManager, axes: String): Typeface? = try {
        Typeface.Builder(assets, "fonts/BricolageGrotesque.ttf").setFontVariationSettings(axes).build()
    } catch (e: RuntimeException) {
        null
    }
}

/** Colours for the current light/dark mode, from the wallpaper (Material You). */
class Palette(private val context: Context) {
    val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    private val pureBlack = isDark && context.lumen.prefs.pureBlack
    private fun c(id: Int) = context.getColor(id)

    val surface = if (pureBlack) Color.BLACK else c(R.color.surface)
    val surfaceContainer = c(R.color.surface_container)
    val surfaceContainerHigh = c(R.color.surface_container_high)
    val onSurface = c(R.color.on_surface)
    val onSurfaceVariant = c(R.color.on_surface_variant)
    val primary = c(R.color.primary)
    val onPrimary = c(R.color.on_primary)
    val primaryContainer = c(R.color.primary_container)
    val onPrimaryContainer = c(R.color.on_primary_container)
    val secondaryContainer = c(R.color.secondary_container)
    val onSecondaryContainer = c(R.color.on_secondary_container)
    val outline = c(R.color.outline)
    val outlineVariant = c(R.color.outline_variant)
    val error = c(R.color.error)
    val placeholder = if (pureBlack) c(R.color.surface_container) else surfaceContainer
}

object Motion {
    /** Material "emphasized decelerate": things arriving on screen. */
    val ENTER = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    /** Material "emphasized accelerate": things leaving. */
    val EXIT = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    val STANDARD = PathInterpolator(0.2f, 0f, 0f, 1f)
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(value: Float): Float = value * resources.displayMetrics.density
fun View.dp(value: Int): Int = context.dp(value)
fun View.dpf(value: Float): Float = context.dpf(value)

fun withAlpha(color: Int, alpha: Float): Int =
    (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)

/** Current status/navigation bar sizes, or null before the window is shown. */
fun systemInsets(view: View): Insets? =
    view.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())

fun View.onSystemInsets(block: (Insets) -> Unit) {
    setOnApplyWindowInsetsListener { _, insets ->
        block(insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()))
        insets
    }
    requestApplyInsets()
}

object Ui {
    /**
     * Controller for the status and navigation bars. Goes through the decor view, which Android
     * creates on first access: Window.getInsetsController() crashes on Android 17 when it's
     * called before setContentView.
     */
    fun bars(activity: Activity): WindowInsetsController? = activity.window.decorView.windowInsetsController

    /** Edge-to-edge window with status bar icons that match the background. */
    fun setupWindow(activity: Activity, darkBackground: Boolean) {
        Compat.edgeToEdge(activity.window)
        val light = if (darkBackground) 0 else
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        bars(activity)?.setSystemBarsAppearance(
            light,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
    }

    fun text(context: Context, sizeSp: Float, color: Int, face: Typeface = Fonts.body, value: CharSequence? = null) =
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            typeface = face
            if (value != null) text = value
        }

    fun icon(context: Context, res: Int, tint: Int, sizeDp: Int = 24) = ImageView(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        layoutParams = ViewGroup.LayoutParams(context.dp(sizeDp), context.dp(sizeDp))
    }

    /** A round, borderless icon button with a 48dp touch target. */
    fun iconButton(context: Context, res: Int, tint: Int, description: String, onClick: (View) -> Unit) =
        ImageButton(context).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(tint)
            contentDescription = description
            tooltipText = description
            scaleType = ImageView.ScaleType.CENTER
            background = RippleDrawable(ColorStateList.valueOf(withAlpha(tint, 0.16f)), null, null)
            layoutParams = ViewGroup.LayoutParams(context.dp(48), context.dp(48))
            setOnClickListener { onClick(it) }
        }

    fun rounded(color: Int, radiusPx: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
    }

    fun pressable(content: Drawable?, rippleColor: Int, radiusPx: Float): Drawable =
        RippleDrawable(ColorStateList.valueOf(rippleColor), content, rounded(Color.WHITE, radiusPx))

    /** A filled pill button: the one main action on a surface. */
    fun filledButton(context: Context, label: String, bg: Int, fg: Int, onClick: (View) -> Unit) =
        text(context, 15f, fg, Fonts.medium, label).apply {
            gravity = Gravity.CENTER
            minHeight = context.dp(48)
            setPadding(context.dp(24), 0, context.dp(24), 0)
            background = pressable(rounded(bg, context.dpf(24f)), withAlpha(fg, 0.18f), context.dpf(24f))
            setOnClickListener { onClick(it) }
        }

    fun textButton(context: Context, label: String, fg: Int, onClick: (View) -> Unit) =
        text(context, 15f, fg, Fonts.medium, label).apply {
            gravity = Gravity.CENTER
            minHeight = context.dp(48)
            setPadding(context.dp(14), 0, context.dp(14), 0)
            background = pressable(null, withAlpha(fg, 0.16f), context.dpf(24f))
            setOnClickListener { onClick(it) }
        }

    /** Centered message for empty screens: a title, a line of help, and an optional action. */
    fun emptyState(
        context: Context, palette: Palette, iconRes: Int, title: String, body: String,
        action: String? = null, onAction: (() -> Unit)? = null,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(context.dp(40), 0, context.dp(40), 0)
        addView(FrameLayout(context).apply {
            background = rounded(palette.secondaryContainer, context.dpf(36f))
            addView(icon(context, iconRes, palette.onSecondaryContainer, 32),
                FrameLayout.LayoutParams(context.dp(32), context.dp(32), Gravity.CENTER))
        }, LinearLayout.LayoutParams(context.dp(72), context.dp(72)).apply { bottomMargin = context.dp(20) })
        addView(text(context, 22f, palette.onSurface, Fonts.display, title).apply { gravity = Gravity.CENTER })
        addView(text(context, 15f, palette.onSurfaceVariant, Fonts.body, body).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.25f)
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dp(8) })
        if (action != null && onAction != null) {
            addView(filledButton(context, action, palette.primary, palette.onPrimary) { onAction() },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dp(24) })
        }
    }

    fun divider(context: Context, color: Int) = View(context).apply {
        background = ColorDrawable(color)
        layoutParams = LinearLayout.LayoutParams(-1, maxOf(1, context.dp(1) / 2))
    }
}
