package dev.hassglass.agent.wake.assist

import android.os.Parcel

/**
 * Mirror of com.rokid.os.sprite.assist.basic.AssistMessage (from RokidSpriteAssistServer).
 *
 * Parcel read/write order derived from decompiled source: writeLong(messageId),
 * writeString(packageName), writeString(infoType), writeLong(time), writeString(message) notifyAll
 * is NOT in the parcel.
 */
data class AssistMessage(
        val messageId: Long,
        val packageName: String?,
        val infoType: String?,
        val time: Long,
        val message: String?,
) {
    constructor(
            parcel: Parcel
    ) : this(
            messageId = parcel.readLong(),
            packageName = parcel.readString(),
            infoType = parcel.readString(),
            time = parcel.readLong(),
            message = parcel.readString(),
    )
}
