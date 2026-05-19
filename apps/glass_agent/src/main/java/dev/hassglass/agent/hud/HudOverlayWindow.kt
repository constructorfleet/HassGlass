package dev.hassglass.agent.hud

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

private const val TAG = "HudOverlay"

/**
 * System overlay window that renders the top HUD card on top of any foreground app.
 *
 * Requires the SYSTEM_ALERT_WINDOW permission, which the user grants once via
 * [Settings.ACTION_MANAGE_OVERLAY_PERMISSION]. If the permission has not been granted the overlay
 * is silently skipped — cards remain visible in MainActivity while it is in the foreground.
 *
 * Usage: val detach = HudOverlayWindow(context).attach() // later: detach()
 */
class HudOverlayWindow(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayView: View? = null
    private var detachStore: (() -> Unit)? = null

    /** Subscribe to [SharedHudDisplayStore] and begin rendering cards as an overlay. */
    fun attach(): () -> Unit {
        detachStore =
                SharedHudDisplayStore.addListener { card ->
                    mainHandler.post { updateOverlay(card) }
                }
        return ::detach
    }

    private fun detach() {
        detachStore?.invoke()
        detachStore = null
        mainHandler.post { removeOverlay() }
    }

    private fun updateOverlay(card: HudCardEnvelope?) {
        if (card == null) {
            removeOverlay()
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            Log.d(TAG, "overlay permission not granted — skipping HUD render")
            return
        }
        showOverlay(card)
    }

    private fun showOverlay(card: HudCardEnvelope) {
        removeOverlay()

        val container =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(28, 24, 28, 24)
                    background =
                            GradientDrawable().apply {
                                setColor(Color.parseColor("#E6090F18"))
                                setStroke(3, Color.parseColor("#4FC3F7"))
                                cornerRadius = 18f
                            }
                }

        val fields = card.card.fields
        val title =
                fields["title"]
                        ?: fields["text"] ?: fields["label"] ?: card.card.kind.replace('_', ' ')

        val titleView =
                TextView(context).apply {
                    text = title
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                }
        container.addView(titleView)

        val body =
                listOfNotNull(
                                fields["subtitle"],
                                fields["body"],
                                fields["artist"],
                                fields["items_0"],
                                fields["items_1"],
                                fields["items_2"],
                                fields["items_3"],
                        )
                        .joinToString("\n")

        if (body.isNotBlank()) {
            val bodyView =
                    TextView(context).apply {
                        text = body
                        setTextColor(Color.parseColor("#D6E0EB"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    }
            container.addView(
                    bodyView,
                    LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                            )
                            .apply { topMargin = 8 },
            )
        }

        @Suppress("DEPRECATION")
        val overlayType =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                }

        val params =
                WindowManager.LayoutParams(
                                WindowManager.LayoutParams.WRAP_CONTENT,
                                WindowManager.LayoutParams.WRAP_CONTENT,
                                overlayType,
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                                PixelFormat.TRANSLUCENT,
                        )
                        .apply {
                            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                            y = 80
                        }

        windowManager.addView(container, params)
        overlayView = container
        Log.d(TAG, "overlay shown: ${card.card.kind} / $title")
    }

    private fun removeOverlay() {
        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.d(TAG, "removeView ignored: ${e.message}")
            }
            overlayView = null
        }
    }
}
