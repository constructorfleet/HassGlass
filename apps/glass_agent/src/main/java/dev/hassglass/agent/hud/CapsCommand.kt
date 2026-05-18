package dev.hassglass.agent.hud

/**
 * A primitive HUD operation that a Caps-backed renderer can execute.
 *
 * Splitting "what to draw" (this ADT) from "how Caps does it" (the
 * [CapsHudRenderer] adapter) keeps the per-kind layout logic in pure,
 * hermetic Kotlin — testable without a real Caps surface — and confines
 * the hardware-gated SDK calls to a single small class.
 *
 * The set of commands here is intentionally minimal; it's what the
 * monocular right-eye micro-LED panel can actually display readably.
 * If a future card kind needs something we don't have, add a new case
 * here, extend the translator, and the adapter gets one more `when` arm.
 */
sealed class CapsCommand {
    object Clear : CapsCommand()
    data class Title(val text: String) : CapsCommand()
    data class Subtitle(val text: String) : CapsCommand()
    data class Body(val text: String) : CapsCommand()
    data class Icon(val key: String) : CapsCommand()
    data class ListItem(val text: String, val ordinal: Int) : CapsCommand()
    data class TimerCountdown(val expiresAtEpochMs: Long, val label: String) : CapsCommand()
    data class Severity(val level: SeverityLevel) : CapsCommand()
    object Render : CapsCommand()

    enum class SeverityLevel { INFO, WARNING, CRITICAL }
}

/**
 * Translate a [HudCardEnvelope] into the ordered list of Caps commands
 * that draws it. Kind-shape pairs are kept in sync with the integration's
 * `custom_components/hassglass/cards.py` validation.
 */
object CapsCommandTranslator {
    private const val MAX_LIST_ITEMS = 4

    fun translate(envelope: HudCardEnvelope?): List<CapsCommand> {
        if (envelope == null) {
            return listOf(CapsCommand.Clear, CapsCommand.Render)
        }
        val card = envelope.card
        val commands = mutableListOf<CapsCommand>(CapsCommand.Clear)
        when (card.kind) {
            "toast" -> commands += toast(card)
            "icon_text" -> commands += iconText(card)
            "list" -> commands += list(card)
            "timer" -> commands += timer(card)
            "alert" -> commands += alert(card)
            "media" -> commands += media(card)
            else -> {
                // Unknown kind: render a generic body with whatever 'text' is set.
                card.fields["text"]?.let { commands += CapsCommand.Body(it) }
            }
        }
        commands += CapsCommand.Render
        return commands
    }

    private fun toast(card: HudCard): List<CapsCommand> = buildList {
        card.fields["severity"]?.let { add(CapsCommand.Severity(parseSeverity(it))) }
        card.fields["text"]?.let { add(CapsCommand.Body(it)) }
    }

    private fun iconText(card: HudCard): List<CapsCommand> = buildList {
        card.fields["icon"]?.let { add(CapsCommand.Icon(it)) }
        card.fields["title"]?.let { add(CapsCommand.Title(it)) }
        card.fields["subtitle"]?.let { add(CapsCommand.Subtitle(it)) }
    }

    private fun list(card: HudCard): List<CapsCommand> = buildList {
        card.fields["title"]?.let { add(CapsCommand.Title(it)) }
        // Items arrive as either a json array (stringified) or "items_0..n" —
        // the wire codec lowers arrays to `items_n` keys for the
        // Map<String, String> field shape used here. Translator just iterates.
        var i = 0
        while (i < MAX_LIST_ITEMS) {
            val item = card.fields["items_$i"] ?: break
            add(CapsCommand.ListItem(item, i))
            i += 1
        }
    }

    private fun timer(card: HudCard): List<CapsCommand> = buildList {
        val label = card.fields["label"] ?: "Timer"
        val expires = card.fields["expires_at_ms"]?.toLongOrNull()
        if (expires != null) {
            add(CapsCommand.TimerCountdown(expires, label))
        } else {
            add(CapsCommand.Title(label))
        }
    }

    private fun alert(card: HudCard): List<CapsCommand> = buildList {
        add(CapsCommand.Severity(CapsCommand.SeverityLevel.WARNING))
        card.fields["title"]?.let { add(CapsCommand.Title(it)) }
        card.fields["body"]?.let { add(CapsCommand.Body(it)) }
    }

    private fun media(card: HudCard): List<CapsCommand> = buildList {
        card.fields["title"]?.let { add(CapsCommand.Title(it)) }
        card.fields["artist"]?.let { add(CapsCommand.Subtitle(it)) }
    }

    private fun parseSeverity(raw: String): CapsCommand.SeverityLevel =
        when (raw.lowercase()) {
            "warning" -> CapsCommand.SeverityLevel.WARNING
            "critical" -> CapsCommand.SeverityLevel.CRITICAL
            else -> CapsCommand.SeverityLevel.INFO
        }
}

/**
 * Production HudRenderer that translates cards through
 * [CapsCommandTranslator] and feeds the resulting commands to the real
 * Caps SDK. The actual SDK call is behind a tiny abstraction so:
 *  - the per-kind layout logic stays unit-testable,
 *  - the SDK surface is one swap-point when Rokid changes their API.
 *
 * Hardware-gated: until the Caps SDK headers are checked in, [CapsSurface]
 * has no concrete implementation and instantiating this class throws.
 */
class CapsHudRenderer(private val surface: CapsSurface) : HudRenderer {
    override fun render(card: HudCardEnvelope?) {
        val commands = CapsCommandTranslator.translate(card)
        commands.forEach(surface::execute)
    }
}

/** Adapter onto the on-device Caps SDK. Implemented once hardware is available. */
interface CapsSurface {
    fun execute(command: CapsCommand)
}
