package dev.hassglass.agent.settings

import android.content.SharedPreferences

data class PairedAgentSettings(
    val haBaseUrl: String,
    val deviceId: String,
    val serial: String,
    val firmware: String,
    val agentVersion: String,
    val token: String,
)

interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(vararg keys: String)
}

class AgentSettingsStore(
    private val keyValueStore: KeyValueStore,
) {
    fun loadPairedSettings(): PairedAgentSettings? {
        val haBaseUrl = keyValueStore.getString(KEY_HA_BASE_URL) ?: return null
        val deviceId = keyValueStore.getString(KEY_DEVICE_ID) ?: return null
        val serial = keyValueStore.getString(KEY_SERIAL) ?: return null
        val firmware = keyValueStore.getString(KEY_FIRMWARE) ?: return null
        val agentVersion = keyValueStore.getString(KEY_AGENT_VERSION) ?: return null
        val token = keyValueStore.getString(KEY_TOKEN) ?: return null

        return PairedAgentSettings(
            haBaseUrl = haBaseUrl,
            deviceId = deviceId,
            serial = serial,
            firmware = firmware,
            agentVersion = agentVersion,
            token = token,
        )
    }

    fun savePairedSettings(settings: PairedAgentSettings) {
        keyValueStore.putString(KEY_HA_BASE_URL, settings.haBaseUrl)
        keyValueStore.putString(KEY_DEVICE_ID, settings.deviceId)
        keyValueStore.putString(KEY_SERIAL, settings.serial)
        keyValueStore.putString(KEY_FIRMWARE, settings.firmware)
        keyValueStore.putString(KEY_AGENT_VERSION, settings.agentVersion)
        keyValueStore.putString(KEY_TOKEN, settings.token)
    }

    fun clearPairing() {
        keyValueStore.remove(
            KEY_HA_BASE_URL,
            KEY_DEVICE_ID,
            KEY_SERIAL,
            KEY_FIRMWARE,
            KEY_AGENT_VERSION,
            KEY_TOKEN,
        )
    }

    private companion object {
        const val KEY_HA_BASE_URL = "ha_base_url"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_SERIAL = "serial"
        const val KEY_FIRMWARE = "firmware"
        const val KEY_AGENT_VERSION = "agent_version"
        const val KEY_TOKEN = "token"
    }
}

class SharedPreferencesKeyValueStore(
    private val preferences: SharedPreferences,
) : KeyValueStore {
    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    override fun remove(vararg keys: String) {
        val editor = preferences.edit()
        keys.forEach(editor::remove)
        editor.apply()
    }
}
