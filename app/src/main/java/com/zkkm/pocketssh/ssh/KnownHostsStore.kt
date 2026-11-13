package com.zkkm.pocketssh.ssh

import android.content.Context
import android.util.Base64
import java.security.MessageDigest

/**
 * Trust-on-first-use host key store. A server key is remembered the first time
 * the user accepts it; any later change is reported as a mismatch so a MITM
 * cannot silently take over an existing profile.
 */
class KnownHostsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(endpoint: String): String? = prefs.getString(key(endpoint), null)

    fun put(endpoint: String, value: String) {
        prefs.edit().putString(key(endpoint), value).apply()
    }

    fun forget(endpoint: String) {
        prefs.edit().remove(key(endpoint)).apply()
    }

    fun forgetAll() {
        prefs.edit().clear().apply()
    }

    fun entryCount(): Int = prefs.all.keys.count { it.startsWith("hk:") }

    fun encodeKey(type: String, key: ByteArray): String =
        type + " " + Base64.encodeToString(key, Base64.NO_WRAP)

    fun fingerprint(key: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP).trimEnd('=')
    }

    private fun key(endpoint: String) = "hk:$endpoint"

    companion object {
        private const val PREFS_NAME = "pocketssh_known_hosts"
    }
}
