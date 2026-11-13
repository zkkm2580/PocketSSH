package com.zkkm.pocketssh.ssh

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Persists connection profiles and app preferences in a single SharedPreferences
 * file. Passwords and keys are stored on-device only (private app storage).
 */
class HostStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val idSequence = AtomicLong(0)

    init {
        idSequence.set(System.currentTimeMillis())
    }

    fun all(): List<SshHost> {
        val raw = prefs.getString(KEY_HOSTS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val result = ArrayList<SshHost>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                result.add(SshHost.fromJson(obj))
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun find(id: Long): SshHost? = all().firstOrNull { it.id == id }

    fun save(host: SshHost): SshHost {
        val hosts = all().toMutableList()
        if (host.id == 0L) {
            host.id = idSequence.incrementAndGet()
            hosts.add(host)
        } else {
            val index = hosts.indexOfFirst { it.id == host.id }
            if (index >= 0) hosts[index] = host else hosts.add(host)
        }
        writeAll(hosts)
        return host
    }

    fun delete(id: Long) {
        val hosts = all().filterNot { it.id == id }
        writeAll(hosts)
    }

    fun duplicate(host: SshHost): SshHost =
        save(host.copy(id = 0L, name = host.displayName() + " 副本"))

    private fun writeAll(hosts: List<SshHost>) {
        val array = JSONArray()
        hosts.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_HOSTS, array.toString()).apply()
    }

    var terminalFontSize: Float
        get() = prefs.getFloat(KEY_FONT_SIZE, 14f)
        set(value) = prefs.edit().putFloat(KEY_FONT_SIZE, value.coerceIn(7f, 30f)).apply()

    var lastUsedHostId: Long
        get() = prefs.getLong(KEY_LAST_HOST, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_HOST, value).apply()

    companion object {
        private const val PREFS_NAME = "pocketssh"
        private const val KEY_HOSTS = "hosts"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_LAST_HOST = "last_host"
    }
}
