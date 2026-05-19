package dev.hassglass.agent.network

import org.junit.Test
import org.junit.Assert.*

class NetworkMonitorTest {
    private class FakeRegistrar : NetworkRegistrar {
        var cb: NetworkRegistrar.Callback? = null
        override fun register(cb: NetworkRegistrar.Callback) { this.cb = cb }
        override fun unregister(cb: NetworkRegistrar.Callback) { if (this.cb === cb) this.cb = null }
        fun simulateAvailable() { cb?.onAvailable() }
        fun simulateLost() { cb?.onLost() }
    }

    @Test
    fun `observer receives available and lost events`() {
        val events = mutableListOf<NetworkEvent>()
        val fake = FakeRegistrar()
        val monitor = NetworkMonitor(
            context = null,
            observer = NetworkObserver { events.add(it) },
            registrar = fake,
        )

        monitor.start()
        fake.simulateAvailable()
        fake.simulateLost()
        monitor.stop()

        assertEquals(2, events.size)
        assertTrue(events[0] is NetworkEvent.Available)
        assertTrue(events[1] is NetworkEvent.Lost)
    }
}
