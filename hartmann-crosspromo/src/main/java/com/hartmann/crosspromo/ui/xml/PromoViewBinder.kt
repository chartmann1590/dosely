package com.hartmann.crosspromo.ui.xml

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.hartmann.crosspromo.HartmannCrossPromo
import com.hartmann.crosspromo.launcher.PlayStoreLauncher
import com.hartmann.crosspromo.model.PromoApp
import com.hartmann.crosspromo.repository.CrossPromoRepository
import com.hartmann.crosspromo.ui.countImpressionOnce
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * XML/View-based adapter for host apps that do not use Compose.
 *
 * Usage (no Compose required anywhere in the host):
 * ```
 * val frame = findViewById<FrameLayout>(R.id.promo_frame)
 * HartmannPromoViewBinder.bind("settings", frame)
 * ```
 *
 * The binder shows at most [maxCards] Material-styled cards inside the given
 * frame, or leaves the frame GONE when there is nothing to promote.
 * Everything runs off the main thread except the final view updates.
 */
object HartmannPromoViewBinder {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repo by lazy { CrossPromoRepository() }

    fun bind(
        placement: String,
        frame: ViewGroup,
        maxCards: Int = 3,
        title: String = "More from Hartmann Studios",
    ) {
        val context = frame.context
        val source = HartmannCrossPromo.sourcePackage()
        val session = HartmannCrossPromo.sessionId()

        scope.launch {
            // Force: every bind() re-rolls the pick set (server varies per
            // request); the previously cached set still renders instantly.
            repo.refreshIfNeeded(source, placement, maxCards, session, force = true)
            val cached = runCatching { repo.getCached(source, placement) }.getOrNull()
            val apps = cached?.apps?.take(maxCards) ?: emptyList()
            withContext(Dispatchers.Main) {
                frame.removeAllViews()
                if (apps.isEmpty()) {
                    frame.visibility = View.GONE
                    return@withContext
                }
                frame.visibility = View.VISIBLE
                frame.addView(buildSection(context, placement, apps, title))
            }
        }
    }

    private fun buildSection(
        context: Context,
        placement: String,
        apps: List<PromoApp>,
        title: String,
    ): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val heading = TextView(context).apply {
            text = title
            textSize = 16f
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
        }
        column.addView(heading)
        apps.forEachIndexed { index, app ->
            column.addView(buildCard(context, placement, app, index + 1))
        }
        return column
    }

    private fun buildCard(
        context: Context,
        placement: String,
        app: PromoApp,
        rank: Int,
    ): View {
        val source = HartmannCrossPromo.sourcePackage()
        val session = HartmannCrossPromo.sessionId()
        val analytics = HartmannCrossPromo.analyticsSink()
        val density = context.resources.displayMetrics.density

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
            background = GradientDrawable().apply {
                cornerRadius = 16f * density
                setColor(0x0F000000 or (Color.parseColor("#6750A4") and 0x00FFFFFF).let { 0x12000000 })
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 0, dp(context, 8)) }

            // One impression per (placement, package) per process lifetime.
            if (countImpressionOnce(placement, app.packageName)) {
                analytics.impression(
                    sourcePackage = source,
                    targetPackage = app.packageName,
                    placement = placement,
                    rankPosition = rank,
                    selectionType = app.selectionType,
                    sessionId = session,
                    recommendationRequestId = null,
                    sdkVersion = HartmannCrossPromo.SDK_VERSION,
                )
            }
            setOnClickListener {
                analytics.click(
                    sourcePackage = source,
                    targetPackage = app.packageName,
                    placement = placement,
                    rankPosition = rank,
                    selectionType = app.selectionType,
                    sessionId = session,
                    recommendationRequestId = null,
                    sdkVersion = HartmannCrossPromo.SDK_VERSION,
                )
                PlayStoreLauncher.openPlayStore(
                    context,
                    app.packageName,
                    com.hartmann.crosspromo.attribution.HartmannInstallAttribution
                        .buildReferrerValue(source, app.packageName)
                )
            }
            contentDescription = buildString {
                append(app.name ?: app.packageName)
                app.shortDescription?.let { append(". ").append(it) }
                append(". View App")
            }
            isClickable = true
            isFocusable = true
        }

        val icon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 44), dp(context, 44)).apply {
                marginEnd = dp(context, 12)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        if (app.iconUrl != null) {
            loadImageInto(icon, app.iconUrl)
        } else {
            icon.setImageDrawable(null)
        }

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(
            TextView(context).apply {
                text = app.name ?: app.packageName
                textSize = 15f
                maxLines = 1
            }
        )
        if (!app.shortDescription.isNullOrBlank()) {
            textColumn.addView(
                TextView(context).apply {
                    text = app.shortDescription
                    textSize = 12f
                    maxLines = 2
                }
            )
        }
        if (app.rating != null || !app.installText.isNullOrBlank()) {
            textColumn.addView(
                TextView(context).apply {
                    text = buildString {
                        app.rating?.let { r ->
                            append("★ ").append(com.hartmann.crosspromo.ui.formatRating(r))
                            app.ratingCount?.let { c ->
                                if (c > 0) append(" (").append(com.hartmann.crosspromo.ui.compactCount(c)).append(")")
                            }
                        }
                        if (!app.installText.isNullOrBlank()) {
                            if (isNotEmpty()) append("  ·  ")
                            append(com.hartmann.crosspromo.ui.compactInstalls(app.installText))
                        }
                    }
                    textSize = 12f
                    setTextColor(0x8A000000.toInt())
                }
            )
        }

        val cta = TextView(context).apply {
            text = "View App"
            textSize = 14f
            setPadding(dp(context, 12), dp(context, 8), dp(context, 4), dp(context, 8))
        }

        row.addView(icon)
        row.addView(textColumn)
        row.addView(cta)
        return row
    }

    /** Icon loading without external image libraries (host-agnostic). */
    private fun loadImageInto(view: ImageView, url: String) {
        scope.launch {
            val bitmap = runCatching {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 8000
                try {
                    android.graphics.BitmapFactory.decodeStream(conn.inputStream)
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (bitmap != null) view.setImageBitmap(bitmap)
            }
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
