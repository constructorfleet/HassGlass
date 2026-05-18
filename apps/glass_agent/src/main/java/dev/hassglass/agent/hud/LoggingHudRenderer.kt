package dev.hassglass.agent.hud

import android.util.Log

class LoggingHudRenderer : HudRenderer {
    override fun render(card: HudCardEnvelope?) {
        if (card == null) {
            Log.i(TAG, "HUD cleared")
        } else {
            Log.i(TAG, "HUD card ${card.id}: ${card.card.kind}")
        }
    }

    private companion object {
        const val TAG = "HassGlassHud"
    }
}
