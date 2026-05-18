package dev.hassglass.agent

/**
 * Foreground service placeholder for the on-glasses agent. /** Foreground service for the
 * on-glasses agent runtime.
 *
 * Today it owns the WebSocket session, TTS playback path, and the Android mic source wiring. HUD
 * messages are also routed into the runtime, but rendering still stays on [LoggingHudRenderer]
 * until the service grows a real surface host for [dev.hassglass.agent.hud.AndroidCapsSurface].
 */
