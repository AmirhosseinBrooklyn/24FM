package ir.fm24.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import ir.fm24.app.R
import ir.fm24.app.network.StatusKind

/**
 * Rounded floating status message shown at the bottom of the screen: a leading icon,
 * severity colour, Persian text and an optional retry action. Showing the same kind again
 * is a no-op, so repeated failures never re-animate or stack.
 */
class StatusBanner @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val icon = ImageView(context)
    private val message = TextView(context)
    private val action = TextView(context)
    private var current: StatusKind? = null

    var onRetry: ((StatusKind) -> Unit)? = null
    var onDismissed: ((StatusKind) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = LAYOUT_DIRECTION_RTL
        minimumHeight = dp(52)
        setPaddingRelative(dp(16), dp(10), dp(12), dp(10))
        background = ContextCompat.getDrawable(context, R.drawable.bg_status_banner)
        elevation = dp(8).toFloat()
        isVisible = false
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE

        icon.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(icon, LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) })

        TextViewCompat.setTextAppearance(message, R.style.TextAppearance_FM24_Banner)
        message.textDirection = TEXT_DIRECTION_RTL
        addView(message, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        TextViewCompat.setTextAppearance(action, R.style.TextAppearance_FM24_Banner)
        action.setText(R.string.action_retry)
        action.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        action.background = ContextCompat.getDrawable(context, R.drawable.bg_banner_action)
        action.setPaddingRelative(dp(14), dp(8), dp(14), dp(8))
        action.minHeight = dp(40)
        action.gravity = Gravity.CENTER
        action.setOnClickListener { current?.let { onRetry?.invoke(it) } }
        addView(action, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })

        setOnClickListener { current?.takeIf { it.isTransient }?.let { hide() } }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = resources.getDimensionPixelSize(R.dimen.banner_max_width)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val spec = if (width > maxWidth) MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.EXACTLY) else widthMeasureSpec
        super.onMeasure(spec, heightMeasureSpec)
    }

    fun show(kind: StatusKind?) {
        if (kind == current) return
        if (kind == null) {
            hide()
            return
        }
        val wasVisible = isVisible && current != null
        current = kind
        bind(kind)
        if (wasVisible) {
            // Cross-fade the content instead of re-running the entrance animation.
            alpha = 0.6f
            animate().alpha(1f).setDuration(SWAP_MS).start()
        } else {
            animate().cancel()
            isVisible = true
            alpha = 0f
            translationY = dp(40).toFloat()
            animate().alpha(1f).translationY(0f)
                .setInterpolator(DecelerateInterpolator())
                .setDuration(ENTER_MS)
                .start()
        }
    }

    private fun hide() {
        val leaving = current ?: return
        current = null
        animate().cancel()
        animate().alpha(0f).translationY(dp(40).toFloat())
            .setInterpolator(AccelerateInterpolator())
            .setDuration(EXIT_MS)
            .withEndAction { if (current == null) isVisible = false }
            .start()
        onDismissed?.invoke(leaving)
    }

    private fun bind(kind: StatusKind) {
        val style = styleFor(kind)
        backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, style.color))
        icon.setImageResource(style.icon)
        message.setText(style.text)
        contentDescription = context.getString(style.text)
        action.isVisible = kind.retryable
    }

    private data class Style(@param:StringRes val text: Int, @param:DrawableRes val icon: Int, @param:ColorRes val color: Int)

    private fun styleFor(kind: StatusKind): Style = when (kind) {
        StatusKind.NO_INTERNET -> Style(R.string.net_no_internet, R.drawable.ic_status_offline, R.color.banner_error)
        StatusKind.SSL -> Style(R.string.net_ssl, R.drawable.ic_status_lock, R.color.banner_error)
        StatusKind.DNS -> Style(R.string.net_dns, R.drawable.ic_status_server, R.color.banner_warning)
        StatusKind.SERVER -> Style(R.string.net_server, R.drawable.ic_status_server, R.color.banner_warning)
        StatusKind.TIMEOUT -> Style(R.string.net_timeout, R.drawable.ic_status_server, R.color.banner_warning)
        StatusKind.PAGE_FAILED -> Style(R.string.net_page_failed, R.drawable.ic_status_server, R.color.banner_warning)
        StatusKind.STREAM_UNAVAILABLE -> Style(R.string.net_stream_unavailable, R.drawable.ic_status_stream, R.color.banner_stream)
        StatusKind.RETRYING -> Style(R.string.net_retrying, R.drawable.ic_status_retry, R.color.banner_info)
        StatusKind.DEGRADED -> Style(R.string.net_degraded, R.drawable.ic_status_slow, R.color.banner_degraded)
        StatusKind.RESTORED -> Style(R.string.net_restored, R.drawable.ic_status_ok, R.color.banner_success)
        StatusKind.VPN_NOTICE -> Style(R.string.vpn_notice, R.drawable.ic_status_info, R.color.banner_info)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val ENTER_MS = 280L
        const val EXIT_MS = 220L
        const val SWAP_MS = 180L
    }
}
