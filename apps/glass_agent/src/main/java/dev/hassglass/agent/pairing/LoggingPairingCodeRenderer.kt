package dev.hassglass.agent.pairing

import android.util.Log

class LoggingPairingCodeRenderer : PairingCodeRenderer {
    override fun showPairingCode(code: String) {
        Log.i(TAG, "Pairing code: $code")
    }

    private companion object {
        const val TAG = "HassGlassPairing"
    }
}
