package dev.hassglass.agent.wake.assist

import android.os.Binder
import android.os.IBinder
import android.os.Parcel

/**
 * Hand-written Binder stub mirroring the generated code for IAssistClient.
 *
 * DESCRIPTOR and transaction codes match the decompiled RokidSpriteAssistServer APK exactly:
 * DESCRIPTOR = "com.rokid.os.sprite.assist.client.IAssistClient" transaction 1 = onRegisterResult
 * transaction 2 = onMessageReceive (returns boolean) transaction 3 = onDataReceive
 *
 * The MasterAssistService will call these via transact() when it dispatches events.
 */
abstract class IAssistClientStub : Binder() {

    companion object {
        const val DESCRIPTOR = "com.rokid.os.sprite.assist.client.IAssistClient"
        private const val TX_ON_REGISTER_RESULT = 1
        private const val TX_ON_MESSAGE_RECEIVE = 2
        private const val TX_ON_DATA_RECEIVE = 3
    }

    init {
        attachInterface(null, DESCRIPTOR)
    }

    abstract fun onRegisterResult(result: RegisterResult)

    /** Return true to mark the event consumed (prevents the built-in assistant from opening). */
    abstract fun onMessageReceive(message: AssistMessage): Boolean

    abstract fun onDataReceive(type: String, key: String, data: ByteArray)

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        return when (code) {
            IBinder.INTERFACE_TRANSACTION -> {
                reply?.writeString(DESCRIPTOR)
                true
            }
            TX_ON_REGISTER_RESULT -> {
                data.enforceInterface(DESCRIPTOR)
                val result = if (data.readInt() != 0) RegisterResult(data) else null
                if (result != null) onRegisterResult(result)
                reply?.writeNoException()
                true
            }
            TX_ON_MESSAGE_RECEIVE -> {
                data.enforceInterface(DESCRIPTOR)
                val message = if (data.readInt() != 0) AssistMessage(data) else null
                val handled = if (message != null) onMessageReceive(message) else false
                reply?.writeNoException()
                reply?.writeInt(if (handled) 1 else 0)
                true
            }
            TX_ON_DATA_RECEIVE -> {
                data.enforceInterface(DESCRIPTOR)
                val type = data.readString() ?: ""
                val key = data.readString() ?: ""
                val bytes = data.createByteArray() ?: byteArrayOf()
                onDataReceive(type, key, bytes)
                reply?.writeNoException()
                true
            }
            else -> super.onTransact(code, data, reply, flags)
        }
    }
}
