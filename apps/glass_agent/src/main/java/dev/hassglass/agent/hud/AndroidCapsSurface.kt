package dev.hassglass.agent.hud

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.SurfaceHolder
import kotlin.math.max

/**
 * Canvas-backed [CapsSurface] for current Android builds.
 *
 * The command stream remains the stable contract. This class stages command state until
 * [CapsCommand.Render], then paints a compact HUD card onto a regular Android [Canvas]. When the
 * real Rokid Caps SDK is available, this class remains a useful fallback renderer and the
 * higher-level command pipeline does not need to change.
 */
class AndroidCapsSurface
internal constructor(
        private val sink: CanvasOpSink,
        private val timeProvider: () -> Long = System::currentTimeMillis,
) : CapsSurface {

    constructor(
            surfaceHolder: SurfaceHolder,
            timeProvider: () -> Long = System::currentTimeMillis,
    ) : this(
            sink = AndroidCanvasOpSink(surfaceHolder),
            timeProvider = timeProvider,
    )

    private var pendingFrame = PendingHudFrame()

    override fun execute(command: CapsCommand) {
        when (command) {
            CapsCommand.Clear -> pendingFrame = PendingHudFrame()
            is CapsCommand.Title -> pendingFrame = pendingFrame.copy(title = command.text)
            is CapsCommand.Subtitle -> pendingFrame = pendingFrame.copy(subtitle = command.text)
            is CapsCommand.Body -> pendingFrame = pendingFrame.copy(body = command.text)
            is CapsCommand.Icon -> pendingFrame = pendingFrame.copy(iconKey = command.key)
            is CapsCommand.TimerCountdown ->
                    pendingFrame =
                            pendingFrame.copy(
                                    timer = TimerModel(command.expiresAtEpochMs, command.label),
                            )
            is CapsCommand.Severity -> pendingFrame = pendingFrame.copy(severity = command.level)
            is CapsCommand.ListItem -> {
                val items = pendingFrame.listItems.toMutableMap()
                items[command.ordinal] = command.text
                pendingFrame = pendingFrame.copy(listItems = items.toMap())
            }
            CapsCommand.Render ->
                    sink.render(CanvasHudPainter.buildOps(pendingFrame, timeProvider()))
        }
    }
}

private data class PendingHudFrame(
        val title: String? = null,
        val subtitle: String? = null,
        val body: String? = null,
        val iconKey: String? = null,
        val listItems: Map<Int, String> = emptyMap(),
        val timer: TimerModel? = null,
        val severity: CapsCommand.SeverityLevel = CapsCommand.SeverityLevel.INFO,
)

private data class TimerModel(
        val expiresAtEpochMs: Long,
        val label: String,
)

internal sealed class CanvasOp {
    object Clear : CanvasOp()

    data class Panel(
            val severity: CapsCommand.SeverityLevel,
            val iconKey: String?,
            val title: String?,
            val subtitle: String?,
            val lines: List<String>,
    ) : CanvasOp()
}

internal interface CanvasOpSink {
    fun render(ops: List<CanvasOp>)
}

private object CanvasHudPainter {
    fun buildOps(frame: PendingHudFrame, nowMs: Long): List<CanvasOp> {
        val lines = mutableListOf<String>()
        frame.body?.let(lines::add)
        frame.listItems.toSortedMap().forEach { (ordinal, text) ->
            lines += "${ordinal + 1}. $text"
        }
        frame.timer?.let { timer ->
            lines += "${timer.label}: ${formatRemaining(timer.expiresAtEpochMs - nowMs)}"
        }

        if (frame.title == null &&
                        frame.subtitle == null &&
                        frame.iconKey == null &&
                        lines.isEmpty()
        ) {
            return listOf(CanvasOp.Clear)
        }

        return listOf(
                CanvasOp.Clear,
                CanvasOp.Panel(
                        severity = frame.severity,
                        iconKey = frame.iconKey,
                        title = frame.title,
                        subtitle = frame.subtitle,
                        lines = lines,
                ),
        )
    }

    private fun formatRemaining(remainingMs: Long): String {
        val totalSeconds = max(0L, (remainingMs + 999L) / 1000L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%d:%02d".format(minutes, seconds)
    }
}

internal class AndroidCanvasOpSink(
        private val surfaceHolder: SurfaceHolder,
) : CanvasOpSink {

    override fun render(ops: List<CanvasOp>) {
        val canvas = surfaceHolder.lockCanvas() ?: return
        try {
            for (op in ops) {
                when (op) {
                    CanvasOp.Clear -> canvas.drawColor(Color.TRANSPARENT)
                    is CanvasOp.Panel -> drawPanel(canvas, op)
                }
            }
        } finally {
            surfaceHolder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawPanel(canvas: Canvas, panel: CanvasOp.Panel) {
        val bounds = RectF(24f, 24f, canvas.width - 24f, canvas.height - 24f)
        canvas.drawRoundRect(bounds, 28f, 28f, backgroundPaint)

        val accentRight = bounds.left + 14f
        canvas.drawRoundRect(
                RectF(bounds.left, bounds.top, accentRight, bounds.bottom),
                28f,
                28f,
                accentPaint(panel.severity),
        )

        var y = bounds.top + 56f
        val left = bounds.left + 34f

        panel.iconKey?.let {
            canvas.drawText(it.uppercase().take(12), left, y, iconPaint)
            y += 40f
        }
        panel.title?.let {
            canvas.drawText(it, left, y, titlePaint)
            y += 46f
        }
        panel.subtitle?.let {
            canvas.drawText(it, left, y, subtitlePaint)
            y += 38f
        }
        panel.lines.forEach { line ->
            canvas.drawText(line, left, y, bodyPaint)
            y += 34f
        }
    }

    private fun accentPaint(level: CapsCommand.SeverityLevel): Paint =
            Paint().apply {
                style = Paint.Style.FILL
                color =
                        when (level) {
                            CapsCommand.SeverityLevel.INFO -> Color.parseColor("#4FC3F7")
                            CapsCommand.SeverityLevel.WARNING -> Color.parseColor("#FFB300")
                            CapsCommand.SeverityLevel.CRITICAL -> Color.parseColor("#E53935")
                        }
                isAntiAlias = true
            }

    private val backgroundPaint =
            Paint().apply {
                style = Paint.Style.FILL
                color = Color.argb(200, 9, 15, 24)
                isAntiAlias = true
            }

    private val titlePaint =
            Paint().apply {
                color = Color.WHITE
                textSize = 34f
                isFakeBoldText = true
                isAntiAlias = true
            }

    private val subtitlePaint =
            Paint().apply {
                color = Color.argb(235, 214, 224, 235)
                textSize = 26f
                isAntiAlias = true
            }

    private val bodyPaint =
            Paint().apply {
                color = Color.argb(235, 235, 240, 245)
                textSize = 24f
                isAntiAlias = true
            }

    private val iconPaint =
            Paint().apply {
                color = Color.argb(220, 148, 210, 255)
                textSize = 20f
                isFakeBoldText = true
                isAntiAlias = true
            }
}
