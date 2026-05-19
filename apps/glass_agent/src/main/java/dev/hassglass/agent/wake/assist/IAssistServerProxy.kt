package dev.hassglass.agent.wake.assist

import android.os.IBinder
import android.os.Parcel

/**
 * Hand-written proxy for IAssistServer, mirroring the generated Proxy inner class.
 *
 * DESCRIPTOR and transaction codes match the decompiled RokidSpriteAssistServer APK exactly:
 * DESCRIPTOR = "com.rokid.os.sprite.assist.server.IAssistServer" transaction 1 = registerClient
 * transaction 2 = unRegisterClient transaction 3 = controlMsgJson
 */
class IAssistServerProxy(private val binder: IBinder) {

    companion object {
        const val DESCRIPTOR = "com.rokid.os.sprite.assist.server.IAssistServer"
        private const val TX_REGISTER_CLIENT = 1
        private const val TX_UN_REGISTER_CLIENT = 2
    }

    fun registerClient(packageName: String, client: IBinder) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(packageName)
            data.writeStrongBinder(client)
            binder.transact(TX_REGISTER_CLIENT, data, reply, 0)
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun unregisterClient(packageName: String) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(packageName)
            binder.transact(TX_UN_REGISTER_CLIENT, data, reply, 0)
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
