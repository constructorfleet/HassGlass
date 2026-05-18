package dev.hassglass.agent.pairing

class PairingFlowController(
    private val renderer: PairingCodeRenderer,
    private val codeGenerator: () -> String = PairingCodeGenerator::generate,
) {
    fun start(): String {
        val code = codeGenerator()
        renderer.showPairingCode(code)
        return code
    }
}
