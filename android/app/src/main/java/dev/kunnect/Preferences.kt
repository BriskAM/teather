package dev.kunnect

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.UUID

class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("kunnect", Context.MODE_PRIVATE)
    var host: String get() = prefs.getString("host", "")!!; set(v) { prefs.edit().putString("host", v).apply() }
    val deviceId: String = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("deviceId", it).apply() }
    var enabledApps: Set<String> get() = prefs.getStringSet("apps", emptySet())!!.toSet(); set(v) { prefs.edit().putStringSet("apps", v.toSet()).apply() }
    var localOnlyApps: Set<String> get() = prefs.getStringSet("local", emptySet())!!.toSet(); set(v) { prefs.edit().putStringSet("local", v.toSet()).apply() }
    var hideContent: Boolean get() = prefs.getBoolean("private", false); set(v) { prefs.edit().putBoolean("private", v).apply() }
    var sound: Boolean get() = prefs.getBoolean("sound", false); set(v) { prefs.edit().putBoolean("sound", v).apply() }
    var dynamicColor: Boolean get() = prefs.getBoolean("dynamic", false); set(v) { prefs.edit().putBoolean("dynamic", v).apply() }
    var sharedTree: String get() = prefs.getString("sharedTree", "")!!; set(v) { prefs.edit().putString("sharedTree", v).apply() }
    var shareEnabled: Boolean get() = prefs.getBoolean("shareEnabled", false); set(v) { prefs.edit().putBoolean("shareEnabled", v).apply() }
    var shareWrite: Boolean get() = prefs.getBoolean("shareWrite", false); set(v) { prefs.edit().putBoolean("shareWrite", v).apply() }
    var batteryPromptSeen: Boolean get() = prefs.getBoolean("batteryPromptSeen", false); set(v) { prefs.edit().putBoolean("batteryPromptSeen", v).apply() }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("kunnect.pairing", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("kunnect.pairing", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    var token: String
        get() = runCatching {
            val data = prefs.getString("token", null) ?: return@runCatching ""
            val bytes = Base64.decode(data, Base64.NO_WRAP)
            Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))); String(doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8) }
        }.getOrDefault("")
        set(value) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            prefs.edit().putString("token", Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)).apply()
        }
}

object TailnetAddress {
    fun valid(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { c -> !c.isDigit() } || (it.length > 1 && it.startsWith('0')) }) return false
        val n = parts.map { it.toIntOrNull() ?: return false }
        return n[0] == 100 && n[1] in 64..127 && n[2] in 0..255 && n[3] in 0..255
    }
}
