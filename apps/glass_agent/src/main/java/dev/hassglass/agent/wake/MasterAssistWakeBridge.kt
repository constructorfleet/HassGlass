package dev.hassglass.agent.wake

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import dev.hassglass.agent.wake.assist.AssistMessage
import dev.hassglass.agent.wake.assist.IAssistClientStub
import dev.hassglass.agent.wake.assist.IAssistServerProxy
import dev.hassglass.agent.wake.assist.RegisterResult
import org.json.JSONException
import org.json.JSONObject

/**
 * Binds to the Rokid system [MasterAssistService] and forwards wake-word events to
 * [RokidWakeBridge].
 *
 * When the "Hi Rokid" wake-word fires the system dispatches an [AssistMessage] with: infoType =
 * "cmd_control_scene_or_app" message = JSON {"type":"scene","cmd":"open","name":"ai_assist",...}
 *
 * We intercept this message, return `true` to suppress the native AI assistant, and invoke the
 * registered listener with a [WakeWordEvent].
 *
 * Binding is deferred until [start] — the service may not be running at construction time.
 */
class MasterAssistWakeBridge(private val context: Context) : RokidWakeBridge {

    private var listener: ((WakeWordEvent) -> Unit)? = null
    private var server: IAssistServerProxy? = null

    private val clientStub =
            object : IAssistClientStub() {
                override fun onRegisterResult(result: RegisterResult) {
                    Log.d(TAG, "Registered with MasterAssistService")
                }

                override fun onMessageReceive(message: AssistMessage): Boolean {
                    if (message.infoType != CMD_CONTROL_SCENE_OR_APP) return false
                    return tryHandleSceneOpen(message.message)
                }

                override fun onDataReceive(type: String, key: String, data: ByteArray) = Unit
            }

    private fun tryHandleSceneOpen(json: String?): Boolean {
        if (json == null) return false
        return try {
            val obj = JSONObject(json)
            val type = obj.optString("type")
            val cmd = obj.optString("cmd")
            val name = obj.optString("name")
            if (type == "scene" && cmd == "open" && name == SCENE_AI_ASSIST) {
                Log.d(TAG, "Wake-word detected (\"hi rokid\"), intercepting scene open")
                listener?.invoke(WakeWordEvent(phrase = "hi rokid"))
                true // consumed — suppresses the native ai_assist scene
            } else {
                false
            }
        } catch (e: JSONException) {
            Log.w(TAG, "Failed to parse AssistMessage.message: $json", e)
            false
        }
    }

    private val connection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    Log.d(TAG, "MasterAssistService connected")
                    val proxy = IAssistServerProxy(binder)
                    server = proxy
                    try {
                        proxy.registerClient(context.packageName, clientStub)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to register with MasterAssistService", e)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    Log.w(TAG, "MasterAssistService disconnected")
                    server = null
                }
            }

    override fun setListener(listener: (WakeWordEvent) -> Unit) {
        this.listener = listener
    }

    override fun start() {
        val intent = Intent(SERVICE_ACTION).apply { setPackage(SERVICE_PACKAGE) }
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            Log.w(
                    TAG,
                    "bindService returned false — MasterAssistService unavailable on this device"
            )
        }
    }

    override fun stop() {
        try {
            server?.unregisterClient(context.packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister from MasterAssistService", e)
        }
        try {
            context.unbindService(connection)
        } catch (e: IllegalArgumentException) {
            // not bound — ignore
        }
        server = null
        listener = null
    }

    companion object {
        private const val TAG = "MasterAssistWakeBridge"
        private const val SERVICE_ACTION = "com.rokid.os.sprite.assist.MasterAssistService"
        private const val SERVICE_PACKAGE = "com.rokid.os.sprite.assistserver"
        private const val CMD_CONTROL_SCENE_OR_APP = "cmd_control_scene_or_app"
        private const val SCENE_AI_ASSIST = "ai_assist"
    }
}
