# Direct Wi-Fi Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make HassGlass work as a direct Wi-Fi Home Assistant satellite on Rokid glasses without CXR-S/CXR-M, with reliable service startup, reconnects, and a visible HUD fallback.

**Architecture:** The glasses app remains a sideloaded Android/YodaOS app that connects directly to Home Assistant over LAN WebSocket. CXR-S is explicitly out of scope because it is a glasses-to-mobile bridge; direct Wi-Fi needs a local foreground service supervisor, an allowed user-start path, network-aware reconnects, and later CXR-L/native display/audio adapters.

**Tech Stack:** Kotlin Android app, OkHttp WebSocket, Android foreground service, Android connectivity callbacks, Home Assistant custom integration WebSocket protocol, pytest for HA tests, Gradle unit/lint checks.

---

## References

- Rokid CXR-S docs describe `CXRServiceBridge`, `Caps`, `subscribe(...)`, and `sendMessage(...)` for CXR-M/mobile communication, not direct glasses-to-HA LAN transport: <https://github.com/buildwithfenna/rokid-docs/blob/main/cxr-s/brief.md>
- Android foreground service docs require a declared foreground service type on API 34+ and warn that `microphone` services are blocked from background starts because `RECORD_AUDIO` is while-in-use: <https://developer.android.com/develop/background-work/services/fgs/service-types>
- Android background-start docs say foreground services started from background are restricted, and services needing while-in-use permissions cannot be created from background except narrow exemptions: <https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start>

## Scope

In scope:
- Direct WebSocket connection from glasses to HA over Wi-Fi.
- Reliable reconnect after socket close, HA restart, Wi-Fi loss, and initial connection failure.
- A user-initiated start path that YodaOS accepts.
- Boot/user-present/network receivers as best-effort hints only.
- Activity-hosted HUD fallback so inbound HUD cards are visible while the app is open.
- Documentation that CXR-S is intentionally not used in this architecture.

Out of scope:
- CXR-S `CXRServiceBridge` integration.
- CXR-M mobile companion relay.
- CXR-L native AI service and display/audio implementation. Add adapter interfaces now, but implement CXR-L in a later hardware-specific plan.

## File Structure

- Modify `apps/glass_agent/build.gradle.kts`: target YodaOS-compatible Android behavior instead of target API 35 behavior for this sideloaded device app.
- Modify `docs/ARCHITECTURE.md`: state that the direct Wi-Fi architecture does not use CXR-S and explain why.
- Modify `docs/ROADMAP.md`: replace stale HUD/service claims with the direct Wi-Fi milestone list.
- Modify `apps/glass_agent/src/main/java/dev/hassglass/agent/AgentController.kt`: make connection supervision a first-class loop with connection-state callbacks.
- Modify `apps/glass_agent/src/main/java/dev/hassglass/agent/ws/WsModels.kt`: keep `WsConnection.awaitClose()` in the interface.
- Modify `apps/glass_agent/src/main/java/dev/hassglass/agent/ws/OkHttpWsTransport.kt`: make close/failure observable with a latch.
- Modify `apps/glass_agent/src/main/java/dev/hassglass/agent/HassGlassAgentService.kt`: run the supervisor loop once, use `dataSync` foreground type, register network callbacks while service is running.
- Modify `apps/glass_agent/src/main/java/dev/hassglass/agent/MainActivity.kt`: make Start Agent explicit and visible; do not depend on automatic `onResume` foreground service launch.
- Modify `apps/glass_agent/src/main/java/dev/hassglass/agent/BootReceiver.kt`: stop treating receivers as guaranteed foreground-service launch points.
- Create `apps/glass_agent/src/main/java/dev/hassglass/agent/AgentServiceStarter.kt`: centralize service-start attempts and status results.
- Create `apps/glass_agent/src/main/java/dev/hassglass/agent/network/NetworkMonitor.kt`: thin wrapper around `ConnectivityManager.NetworkCallback`.
- Create `apps/glass_agent/src/main/java/dev/hassglass/agent/hud/HudDisplayStore.kt`: latest-card store for activity HUD fallback.
- Modify `apps/glass_agent/src/main/java/dev/hassglass/agent/AgentRuntime.kt`: publish HUD cards to the display store by default.
- Modify `apps/glass_agent/src/test/java/dev/hassglass/agent/AgentControllerTest.kt`: reconnect-loop tests.
- Create `apps/glass_agent/src/test/java/dev/hassglass/agent/AgentServiceStarterTest.kt`: service-start result tests.
- Create `apps/glass_agent/src/test/java/dev/hassglass/agent/network/NetworkMonitorTest.kt`: pure callback behavior test using a fake observer, not Android framework.
- Create `apps/glass_agent/src/test/java/dev/hassglass/agent/hud/HudDisplayStoreTest.kt`: HUD store/listener tests.

---

### Task 1: Lock the Direct Wi-Fi Architecture

**Files:**
- Modify: `apps/glass_agent/build.gradle.kts`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/ROADMAP.md`

- [ ] **Step 1: Update Android target SDK for YodaOS sideloading**

Change `apps/glass_agent/build.gradle.kts`:

```kotlin
defaultConfig {
    applicationId = "dev.hassglass.agent"
    minSdk = 26
    targetSdk = 32
    versionCode = 1
    versionName = "0.1.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
}
```

Rationale: the current hardware reports YodaOS/Android 12 behavior. Targeting 35 pulls in newer foreground-service policy interactions that are not useful for a private sideloaded appliance app. Keep `compileSdk = 35` for toolchain compatibility.

- [ ] **Step 2: Run build sanity check**

Run:

```bash
gradle :apps:glass_agent:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Update architecture docs**

In `docs/ARCHITECTURE.md`, replace the CXR table rows with:

```markdown
| SDK | Where it runs | HassGlass use |
| --- | --- | --- |
| **Direct Android/YodaOS app** | On the glasses | **Primary v1 path.** The agent is a sideloaded Android app that connects directly to Home Assistant over the home LAN via WebSocket. |
| **CXR-L** | On the glasses | Future native adapter for Rokid AI service, wake-word, AEC microphone, and display integration. |
| **CXR-M** | Phone companion | Optional future relay/onboarding path when the glasses are away from home Wi-Fi. |
| **CXR-S** | On the glasses | Not used for direct Wi-Fi. CXR-S is a bridge to CXR-M/mobile using `CXRServiceBridge`, `Caps`, and mobile message channels. |
```

- [ ] **Step 4: Update roadmap**

In `docs/ROADMAP.md`, replace stale statements that say the service uses `LoggingHudRenderer` or that CXR-L is the only remaining adapter work with:

```markdown
Current direct-Wi-Fi runtime gaps:

1. Foreground service must be started through an allowed user-initiated path on YodaOS.
2. The WebSocket supervisor must reconnect after close/failure and after network loss.
3. The activity-hosted HUD fallback is visible only while the app is open.
4. CXR-L native display/audio/wake hooks remain a separate hardware-specific adapter milestone.
```

- [ ] **Step 5: Verify docs and build**

Run:

```bash
gradle :apps:glass_agent:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add apps/glass_agent/build.gradle.kts docs/ARCHITECTURE.md docs/ROADMAP.md
git commit -m "docs: clarify direct wifi agent architecture"
```

---

### Task 2: Make WebSocket Supervision Durable

**Files:**
- Modify: `apps/glass_agent/src/main/java/dev/hassglass/agent/AgentController.kt`
- Modify: `apps/glass_agent/src/main/java/dev/hassglass/agent/ws/WsModels.kt`
- Modify: `apps/glass_agent/src/main/java/dev/hassglass/agent/ws/OkHttpWsTransport.kt`
- Test: `apps/glass_agent/src/test/java/dev/hassglass/agent/AgentControllerTest.kt`

- [ ] **Step 1: Write failing reconnect tests**

Add these tests to `AgentControllerTest`:

```kotlin
@Test
fun runUntilStoppedReconnectsAfterConnectionCloses() {
    val settingsStore = AgentSettingsStore(InMemoryKeyValueStore())
    settingsStore.savePairedSettings(pairedSettings())
    val connector = RecordingConnector()
    lateinit var controller: AgentController
    var disconnects = 0
    controller =
            AgentController(
                    settingsStore = settingsStore,
                    connector = connector,
                    sleeper = {},
                    onDisconnected = {
                        disconnects += 1
                        if (disconnects == 2) controller.stop()
                    },
            )

    assertEquals(AgentStartResult.CONNECTED, controller.runUntilStopped())

    assertEquals(2, connector.configs.size)
    assertEquals(2, disconnects)
}

@Test
fun runUntilStoppedKeepsTryingAfterConnectRetryBudgetFails() {
    val settingsStore = AgentSettingsStore(InMemoryKeyValueStore())
    settingsStore.savePairedSettings(pairedSettings())
    val connector = RecordingConnector(failuresBeforeSuccess = 1)
    lateinit var controller: AgentController
    controller =
            AgentController(
                    settingsStore = settingsStore,
                    connector = connector,
                    sleeper = {},
                    onDisconnected = { controller.stop() },
            )

    assertEquals(AgentStartResult.CONNECTED, controller.runUntilStopped())

    assertEquals(2, connector.configs.size)
}

private fun pairedSettings(): PairedAgentSettings =
        PairedAgentSettings(
                haBaseUrl = "https://ha.example",
                deviceId = "rokid-1",
                serial = "SN123",
                firmware = "1.2.3",
                agentVersion = "0.1.0",
                token = "device-token",
        )
```

Update `RecordingConnector` in the same file:

```kotlin
private class RecordingConnector(
        private var failuresBeforeSuccess: Int = 0,
) : AgentConnector {
    val configs = mutableListOf<AgentConnectionConfig>()
    val connections = mutableListOf<RecordingWsConnection>()
    val connection: RecordingWsConnection
        get() = connections.last()

    override fun connectWithRetry(config: AgentConnectionConfig, maxAttempts: Int): WsConnection {
        configs += config
        if (failuresBeforeSuccess > 0) {
            failuresBeforeSuccess -= 1
            throw RuntimeException("connect failed")
        }
        val connection = RecordingWsConnection()
        connections += connection
        return connection
    }
}

private class RecordingWsConnection : WsConnection {
    var closed = false

    override fun sendText(text: String) = Unit
    override fun sendBytes(bytes: ByteArray) = Unit

    override fun close() {
        closed = true
    }

    override fun awaitClose() {
        closed = true
    }
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
gradle :apps:glass_agent:testDebugUnitTest --tests dev.hassglass.agent.AgentControllerTest
```

Expected: fail because `runUntilStopped`, `sleeper`, and `awaitClose` are missing or incomplete.

- [ ] **Step 3: Add `awaitClose` to the connection contract**

Change `apps/glass_agent/src/main/java/dev/hassglass/agent/ws/WsModels.kt`:

```kotlin
interface WsConnection {
    fun sendText(text: String)
    fun sendBytes(bytes: ByteArray)
    fun close()
    fun awaitClose() = Unit
}
```

- [ ] **Step 4: Make OkHttp close/failure observable**

Change `apps/glass_agent/src/main/java/dev/hassglass/agent/ws/OkHttpWsTransport.kt`:

```kotlin
import java.util.concurrent.CountDownLatch

class OkHttpWsTransport(
    private val client: OkHttpClient = OkHttpClient(),
) : WsTransport {
    override fun connect(request: WsRequest, listener: WsListener): WsConnection {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.addHeader(name, value) }
        val closed = CountDownLatch(1)
        val webSocket = client.newWebSocket(builder.build(), listener.asOkHttpListener(closed))
        return OkHttpWsConnection(webSocket, closed)
    }

    private fun WsListener.asOkHttpListener(closed: CountDownLatch): WebSocketListener =
        object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                onText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onBinary(bytes.toByteArray())
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                try {
                    onClosed()
                } finally {
                    closed.countDown()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                try {
                    onFailure(t)
                } finally {
                    closed.countDown()
                }
            }
        }
}

private class OkHttpWsConnection(
    private val webSocket: WebSocket,
    private val closed: CountDownLatch,
) : WsConnection {
    override fun sendText(text: String) {
        if (!webSocket.send(text)) {
            throw WsException("failed to enqueue WebSocket text frame")
        }
    }

    override fun sendBytes(bytes: ByteArray) {
        if (!webSocket.send(bytes.toByteString())) {
            throw WsException("failed to enqueue WebSocket binary frame")
        }
    }

    override fun close() {
        webSocket.close(1000, "closed")
        closed.countDown()
    }

    override fun awaitClose() {
        closed.await()
    }
}
```

- [ ] **Step 5: Add supervisor loop**

Change `AgentController` constructor and body:

```kotlin
class AgentController(
        private val settingsStore: AgentSettingsStore,
        private val connector: AgentConnector,
        private val telemetryProvider: () -> TelemetrySnapshot? = { null },
        private val reconnectDelayMs: Long = 1_000,
        private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
        private val onConnected: (WsConnection) -> Unit = {},
        private val onDisconnected: () -> Unit = {},
) {
    @Volatile private var stopped = false
    private var connection: WsConnection? = null

    fun start(): AgentStartResult {
        val settings =
                settingsStore.loadPairedSettings() ?: return AgentStartResult.PAIRING_REQUIRED
        stopped = false
        connection = connector.connectWithRetry(settings.toConnectionConfig(telemetryProvider()))
        connection?.let(onConnected)
        return AgentStartResult.CONNECTED
    }

    fun runUntilStopped(): AgentStartResult {
        val settings =
                settingsStore.loadPairedSettings() ?: return AgentStartResult.PAIRING_REQUIRED
        stopped = false
        while (!stopped) {
            try {
                connection = connector.connectWithRetry(settings.toConnectionConfig(telemetryProvider()))
                val active = connection
                active?.let(onConnected)
                active?.awaitClose()
                if (connection === active) {
                    connection = null
                    onDisconnected()
                }
            } catch (_: Throwable) {
                connection = null
            }
            if (!stopped) {
                sleeper(reconnectDelayMs)
            }
        }
        return AgentStartResult.CONNECTED
    }

    fun stop() {
        stopped = true
        val active = connection
        active?.close()
        connection = null
        if (active != null) {
            onDisconnected()
        }
    }
}
```

Keep the existing `toConnectionConfig(...)` helper below this body.

- [ ] **Step 6: Run tests**

Run:

```bash
gradle :apps:glass_agent:testDebugUnitTest --tests dev.hassglass.agent.AgentControllerTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add apps/glass_agent/src/main/java/dev/hassglass/agent/AgentController.kt apps/glass_agent/src/main/java/dev/hassglass/agent/ws/WsModels.kt apps/glass_agent/src/main/java/dev/hassglass/agent/ws/OkHttpWsTransport.kt apps/glass_agent/src/test/java/dev/hassglass/agent/AgentControllerTest.kt
git commit -m "fix: supervise glass agent websocket reconnects"
```

---

### Task 3: Make Service Startup Explicit and Testable

**Files:**
- Create: `apps/glass_agent/src/main/java/dev/hassglass/agent/AgentServiceStarter.kt`
- Modify: `apps/glass_agent/src/main/java/dev/hassglass/agent/MainActivity.kt`
- Modify: `apps/glass_agent/src/main/java/dev/hassglass/agent/HassGlassAgentService.kt`
- Modify: `apps/glass_agent/src/main/AndroidManifest.xml`
- Test: `apps/glass_agent/src/test/java/dev/hassglass/agent/AgentServiceStarterTest.kt`

- [ ] **Step 1: Write service starter tests**

Create `AgentServiceStarterTest.kt`:

```kotlin
package dev.hassglass.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentServiceStarterTest {
    @Test
    fun startReturnsStartedWhenPlatformAcceptsRequest() {
        val starter = AgentServiceStarter { AgentServiceStartResult.STARTED }

        assertEquals(AgentServiceStartResult.STARTED, starter.start())
    }

    @Test
    fun startReturnsRejectedWhenPlatformThrowsRuntimeException() {
        val starter = AgentServiceStarter { throw RuntimeException("background start not allowed") }

        assertEquals(AgentServiceStartResult.REJECTED, starter.start())
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run:

```bash
gradle :apps:glass_agent:testDebugUnitTest --tests dev.hassglass.agent.AgentServiceStarterTest
```

Expected: fail because `AgentServiceStarter` and `AgentServiceStartResult` do not exist.

- [ ] **Step 3: Implement service starter**

Create `AgentServiceStarter.kt`:

```kotlin
package dev.hassglass.agent

enum class AgentServiceStartResult {
    STARTED,
    REJECTED,
}

class AgentServiceStarter(
        private val startAction: () -> AgentServiceStartResult,
) {
    fun start(): AgentServiceStartResult =
            try {
                startAction()
            } catch (_: RuntimeException) {
                AgentServiceStartResult.REJECTED
            }
}
```

- [ ] **Step 4: Use `dataSync` foreground service type**

In `AndroidManifest.xml`, set:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

<service
    android:name=".HassGlassAgentService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```

Do not declare `FOREGROUND_SERVICE_MICROPHONE` on the always-running connection service.

- [ ] **Step 5: Promote service as `dataSync`**

In `HassGlassAgentService.promoteToForeground()`:

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
} else {
    startForeground(NOTIFICATION_ID, notification)
}
```

- [ ] **Step 6: Run supervisor loop once**

In `HassGlassAgentService.onStartCommand(...)`:

```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    promoteToForeground()
    if (worker?.isAlive != true) {
        worker = Thread {
            controller?.runUntilStopped()
        }.also { it.start() }
    }
    return START_STICKY
}
```

- [ ] **Step 7: Add explicit Start Agent button**

In `MainActivity`, add a paired-mode button:

```kotlin
private lateinit var startAgentButton: Button
```

In `buildLayout()` before `clearPairingButton`:

```kotlin
startAgentButton = focusableButton("Start agent") { maybeStartAgent() }
root.addView(startAgentButton, lp(MATCH_PARENT, bottom = 16))
```

In the unpaired branch of `renderState()`:

```kotlin
startAgentButton.visibility = View.GONE
```

In the paired branch:

```kotlin
startAgentButton.visibility = View.VISIBLE
clearPairingButton.visibility = View.VISIBLE
initialFocus = startAgentButton
```

- [ ] **Step 8: Catch rejected starts and show useful status**

In `MainActivity.maybeStartAgent()`:

```kotlin
Log.i(TAG, "starting agent service (paired, online, mic permission granted)")
val starter =
        AgentServiceStarter {
            startForegroundService(Intent(this, HassGlassAgentService::class.java))
            AgentServiceStartResult.STARTED
        }
when (starter.start()) {
    AgentServiceStartResult.STARTED -> setStatus("Agent service starting.")
    AgentServiceStartResult.REJECTED ->
            setStatus("Tap Start agent to grant Android's foreground-start window.")
}
```

- [ ] **Step 9: Run tests and lint**

Run:

```bash
gradle :apps:glass_agent:testDebugUnitTest
gradle :apps:glass_agent:lintDebug
```

Expected: both `BUILD SUCCESSFUL`.

- [ ] **Step 10: Commit**

```bash
git add apps/glass_agent/src/main/AndroidManifest.xml apps/glass_agent/src/main/java/dev/hassglass/agent/AgentServiceStarter.kt apps/glass_agent/src/main/java/dev/hassglass/agent/MainActivity.kt apps/glass_agent/src/main/java/dev/hassglass/agent/HassGlassAgentService.kt apps/glass_agent/src/test/java/dev/hassglass/agent/AgentServiceStarterTest.kt
git commit -m "fix: make glass agent service startup explicit"
```

---

### Task 4: Add Network-Aware Reconnect Hints

**Files:**
- Create: `apps/glass_agent/src/main/java/dev/hassglass/agent/network/NetworkMonitor.kt`
- Modify: `apps/glass_agent/src/main/java/dev/hassglass/agent/HassGlassAgentService.kt`
- Test: `apps/glass_agent/src/test/java/dev/hassglass/agent/network/NetworkMonitorTest.kt`

- [ ] **Step 1: Write pure callback test**

Create `NetworkMonitorTest.kt`:

```kotlin
package dev.hassglass.agent.network

import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkMonitorTest {
    @Test
    fun observerReceivesAvailableAndLostEvents() {
        val events = mutableListOf<NetworkEvent>()
        val observer = NetworkObserver(events::add)

        observer.onAvailable()
        observer.onLost()

        assertEquals(listOf(NetworkEvent.AVAILABLE, NetworkEvent.LOST), events)
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run:

```bash
gradle :apps:glass_agent:testDebugUnitTest --tests dev.hassglass.agent.network.NetworkMonitorTest
```

Expected: fail because network classes do not exist.

- [ ] **Step 3: Implement network observer and Android monitor**

Create `NetworkMonitor.kt`:

```kotlin
package dev.hassglass.agent.network

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest

enum class NetworkEvent {
    AVAILABLE,
    LOST,
}

class NetworkObserver(
        private val onEvent: (NetworkEvent) -> Unit,
) {
    fun onAvailable() {
        onEvent(NetworkEvent.AVAILABLE)
    }

    fun onLost() {
        onEvent(NetworkEvent.LOST)
    }
}

class NetworkMonitor(
        private val connectivityManager: ConnectivityManager,
        private val observer: NetworkObserver,
) {
    private val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    observer.onAvailable()
                }

                override fun onLost(network: Network) {
                    observer.onLost()
                }
            }

    fun start() {
        connectivityManager.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
    }

    fun stop() {
        connectivityManager.unregisterNetworkCallback(callback)
    }
}
```

- [ ] **Step 4: Wire monitor into running service**

In `HassGlassAgentService`, add:

```kotlin
private var networkMonitor: NetworkMonitor? = null
```

In `onCreate()` after controller creation:

```kotlin
val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
networkMonitor =
        NetworkMonitor(
                connectivityManager,
                NetworkObserver {
                    controller?.stop()
                },
        )
```

Import:

```kotlin
import android.net.ConnectivityManager
import dev.hassglass.agent.network.NetworkMonitor
import dev.hassglass.agent.network.NetworkObserver
```

In `onStartCommand(...)`, before starting the worker:

```kotlin
networkMonitor?.start()
```

In `onDestroy()`:

```kotlin
networkMonitor?.stop()
networkMonitor = null
```

Behavior: a network transition closes the active socket and lets `runUntilStopped()` immediately build a fresh connection rather than waiting for TCP timeout.

- [ ] **Step 5: Run tests**

Run:

```bash
gradle :apps:glass_agent:testDebugUnitTest --tests dev.hassglass.agent.network.NetworkMonitorTest
gradle :apps:glass_agent:testDebugUnitTest
```

Expected: both `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add apps/glass_agent/src/main/java/dev/hassglass/agent/network/NetworkMonitor.kt apps/glass_agent/src/main/java/dev/hassglass/agent/HassGlassAgentService.kt apps/glass_agent/src/test/java/dev/hassglass/agent/network/NetworkMonitorTest.kt
git commit -m "fix: reconnect glass agent on network changes"
```

---

### Task 5: Make HUD Cards Visible in the Direct Wi-Fi App

**Files:**
- Create: `apps/glass_agent/src/main/java/dev/hassglass/agent/hud/HudDisplayStore.kt`
- Modify: `apps/glass_agent/src/main/java/dev/hassglass/agent/AgentRuntime.kt`
- Modify: `apps/glass_agent/src/main/java/dev/hassglass/agent/MainActivity.kt`
- Test: `apps/glass_agent/src/test/java/dev/hassglass/agent/hud/HudDisplayStoreTest.kt`

- [ ] **Step 1: Write HUD store tests**

Create `HudDisplayStoreTest.kt`:

```kotlin
package dev.hassglass.agent.hud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HudDisplayStoreTest {
    @Test
    fun rendererStoresLatestHudCardForVisibleUi() {
        val store = HudDisplayStore()
        val renderer = HudDisplayRenderer(store)
        val card =
                HudCardEnvelope(
                        id = "doorbell",
                        card =
                                HudCard(
                                        kind = "icon_text",
                                        fields =
                                                mapOf(
                                                        "title" to "Doorbell",
                                                        "subtitle" to "Front door",
                                                ),
                                ),
                )

        renderer.render(card)

        assertEquals(card, store.current)
    }

    @Test
    fun rendererClearsVisibleHudCard() {
        val store = HudDisplayStore()
        val renderer = HudDisplayRenderer(store)
        renderer.render(HudCardEnvelope("notify", HudCard("toast", mapOf("text" to "Hi"))))

        renderer.render(null)

        assertNull(store.current)
    }

    @Test
    fun listenersReceiveImmediateAndFutureCards() {
        val store = HudDisplayStore()
        val first = HudCardEnvelope("first", HudCard("toast", mapOf("text" to "One")))
        val second = HudCardEnvelope("second", HudCard("toast", mapOf("text" to "Two")))
        store.render(first)
        val received = mutableListOf<HudCardEnvelope?>()

        val unsubscribe = store.addListener { received += it }
        store.render(second)
        unsubscribe()
        store.render(null)

        assertEquals(listOf<HudCardEnvelope?>(first, second), received)
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run:

```bash
gradle :apps:glass_agent:testDebugUnitTest --tests dev.hassglass.agent.hud.HudDisplayStoreTest
```

Expected: fail because `HudDisplayStore` and `HudDisplayRenderer` do not exist.

- [ ] **Step 3: Implement HUD display store**

Create `HudDisplayStore.kt`:

```kotlin
package dev.hassglass.agent.hud

open class HudDisplayStore : HudRenderer {
    private val listeners = mutableSetOf<(HudCardEnvelope?) -> Unit>()

    @Volatile var current: HudCardEnvelope? = null
        private set

    @Synchronized
    override fun render(card: HudCardEnvelope?) {
        current = card
        listeners.toList().forEach { it(card) }
    }

    @Synchronized
    fun addListener(listener: (HudCardEnvelope?) -> Unit): () -> Unit {
        listeners += listener
        listener(current)
        return {
            synchronized(this) {
                listeners -= listener
            }
        }
    }
}

object SharedHudDisplayStore : HudDisplayStore()

class HudDisplayRenderer(
        private val store: HudDisplayStore = SharedHudDisplayStore,
) : HudRenderer {
    override fun render(card: HudCardEnvelope?) {
        store.render(card)
    }
}
```

- [ ] **Step 4: Make runtime use display store by default**

In `AgentRuntime.kt`, replace the default renderer:

```kotlin
import dev.hassglass.agent.hud.HudDisplayRenderer
import dev.hassglass.agent.hud.HudRenderer

class AgentRuntime(
        hudRenderer: HudRenderer = HudDisplayRenderer(),
        private val micSourceFactory: () -> MicSource = { AndroidMicSource() },
        private val playbackSink: AudioTrackPlaybackSink =
                AudioTrackPlaybackSink(AndroidAudioTrackWriter()),
) {
    private val hudController = HudController(hudRenderer)
}
```

- [ ] **Step 5: Render latest HUD card in activity**

In `MainActivity`, add fields:

```kotlin
private var hudUnsubscribe: (() -> Unit)? = null
private lateinit var hudContainer: LinearLayout
private lateinit var hudTitleView: TextView
private lateinit var hudBodyView: TextView
```

In `onResume()`:

```kotlin
hudUnsubscribe = SharedHudDisplayStore.addListener { card ->
    mainHandler.post { renderHud(card) }
}
```

In `onPause()`:

```kotlin
hudUnsubscribe?.invoke()
hudUnsubscribe = null
super.onPause()
```

In `buildLayout()` after `statusView`:

```kotlin
hudContainer = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    visibility = View.GONE
    setPadding(28, 24, 28, 24)
    background = GradientDrawable().apply {
        setColor(Color.parseColor("#E6090F18"))
        setStroke(3, Color.parseColor("#4FC3F7"))
        cornerRadius = 18f
    }
}
hudTitleView = TextView(this).apply {
    setTextColor(Color.WHITE)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
}
hudBodyView = TextView(this).apply {
    setTextColor(Color.parseColor("#D6E0EB"))
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
}
hudContainer.addView(hudTitleView, lp(MATCH_PARENT, bottom = 8))
hudContainer.addView(hudBodyView, lp(MATCH_PARENT))
root.addView(hudContainer, lp(MATCH_PARENT, bottom = 24))
```

Add helper:

```kotlin
private fun renderHud(card: HudCardEnvelope?) {
    if (card == null) {
        hudContainer.visibility = View.GONE
        return
    }
    val fields = card.card.fields
    hudTitleView.text =
        fields["title"]
            ?: fields["text"]
            ?: fields["label"]
            ?: card.card.kind.replace('_', ' ')
    hudBodyView.text =
        listOfNotNull(
                fields["subtitle"],
                fields["body"],
                fields["artist"],
                fields["items_0"],
                fields["items_1"],
                fields["items_2"],
                fields["items_3"],
        ).joinToString("\n")
    hudBodyView.visibility = if (hudBodyView.text.isNullOrBlank()) View.GONE else View.VISIBLE
    hudContainer.visibility = View.VISIBLE
}
```

Add imports:

```kotlin
import dev.hassglass.agent.hud.HudCardEnvelope
import dev.hassglass.agent.hud.SharedHudDisplayStore
```

- [ ] **Step 6: Run tests**

Run:

```bash
gradle :apps:glass_agent:testDebugUnitTest --tests dev.hassglass.agent.hud.HudDisplayStoreTest
gradle :apps:glass_agent:testDebugUnitTest
```

Expected: both `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add apps/glass_agent/src/main/java/dev/hassglass/agent/hud/HudDisplayStore.kt apps/glass_agent/src/main/java/dev/hassglass/agent/AgentRuntime.kt apps/glass_agent/src/main/java/dev/hassglass/agent/MainActivity.kt apps/glass_agent/src/test/java/dev/hassglass/agent/hud/HudDisplayStoreTest.kt
git commit -m "feat: show hud cards in glass agent activity"
```

---

### Task 6: End-to-End Device Verification

**Files:**
- No source files unless verification exposes a defect.

- [ ] **Step 1: Run complete checks**

Run:

```bash
.venv/bin/python -m pytest -q
gradle :apps:glass_agent:testDebugUnitTest
gradle :apps:glass_agent:lintDebug
gradle :apps:glass_agent:assembleDebug
```

Expected:
- Python: all tests pass.
- Android unit tests: `BUILD SUCCESSFUL`.
- Android lint: `BUILD SUCCESSFUL`.
- APK build: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Install APK**

Run:

```bash
adb devices -l
adb -s 1901092545028751 install -r apps/glass_agent/build/outputs/apk/debug/glass_agent-debug.apk
adb -s 1901092545028751 shell am start -n dev.hassglass.agent/.MainActivity
```

Expected:
- Device list shows one `RG_glasses` device.
- Install prints `Success`.
- Activity starts.

- [ ] **Step 3: Start service from glasses UI**

On the glasses, tap `Start agent`.

Run:

```bash
adb -s 1901092545028751 logcat -d -t 200 | rg 'HassGlass|ActivityManager: Background start|ForegroundService|FATAL EXCEPTION|AndroidRuntime'
```

Expected:
- `HassGlass` logs show service start and no fatal exception.
- No new `Background start not allowed` after the explicit tap.

- [ ] **Step 4: Verify HA connected state**

In Home Assistant, check `binary_sensor.<glasses>_connected`.

Expected:
- It changes to connected within 10 seconds of the agent service start.
- If HA is restarted, it returns to connected within 30 seconds after HA is back online.

- [ ] **Step 5: Verify HUD card path**

From Home Assistant, call a HUD/notify action that sends an `icon_text` or `toast` card to the glasses.

Expected:
- With the agent activity visible, the latest card appears in the activity HUD panel.
- Logcat does not show `FATAL EXCEPTION`.

- [ ] **Step 6: Commit verification notes**

If manual verification required code or doc updates, commit them:

```bash
git add docs/ROADMAP.md docs/ARCHITECTURE.md
git commit -m "docs: record direct wifi agent verification"
```

If no files changed during verification, do not create an empty commit.

---

## Self-Review

Spec coverage:
- Direct Wi-Fi instead of CXR-S: Task 1.
- Service startup reliability: Task 3.
- Reconnect after disconnect: Task 2.
- Network transition behavior: Task 4.
- HUD visibility: Task 5.
- Device installation and HA verification: Task 6.

Placeholder scan:
- No task uses `TBD`, `TODO`, or “add appropriate handling.”
- Every code-changing step includes exact code or exact replacement snippets.

Type consistency:
- `AgentServiceStartResult`, `AgentServiceStarter`, `NetworkEvent`, `NetworkObserver`, `HudDisplayStore`, and `HudDisplayRenderer` are defined before use.
- `WsConnection.awaitClose()` is added before fake and OkHttp implementations rely on it.
