package dev.hassglass.agent.pairing

import kotlin.test.Test
import kotlin.test.assertEquals

class PairingFlowControllerTest {
    @Test
    fun startRendersGeneratedCode() {
        val renderer = RecordingRenderer()
        val controller = PairingFlowController(
            renderer = renderer,
            codeGenerator = { "654321" },
        )

        val code = controller.start()

        assertEquals("654321", code)
        assertEquals("654321", renderer.codes.single())
    }
}

private class RecordingRenderer : PairingCodeRenderer {
    val codes = mutableListOf<String>()

    override fun showPairingCode(code: String) {
        codes += code
    }
}
