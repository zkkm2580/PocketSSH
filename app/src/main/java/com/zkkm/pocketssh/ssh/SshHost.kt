package com.zkkm.pocketssh.ssh

import org.json.JSONObject

/** A saved connection profile. */
data class SshHost(
    var id: Long = 0L,
    var name: String = "",
    var host: String = "",
    var port: Int = 22,
    var username: String = "",
    var authMode: Int = AUTH_PASSWORD,
    var password: String = "",
    var privateKey: String = "",
    var passphrase: String = ""
) {

    fun displayName(): String = name.ifBlank { host }.ifBlank { "未命名主机" }

    fun subtitle(): String = "${username.ifBlank { "user" }}@${host.ifBlank { "host" }}:$port"

    fun authLabel(): String = if (authMode == AUTH_KEY) "密钥" else "密码"

    fun endpoint(): String = "$host:$port"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("host", host)
        put("port", port)
        put("username", username)
        put("authMode", authMode)
        put("password", password)
        put("privateKey", privateKey)
        put("passphrase", passphrase)
    }

    companion object {
        const val AUTH_PASSWORD = 0
        const val AUTH_KEY = 1

        fun fromJson(json: JSONObject): SshHost = SshHost(
            id = json.optLong("id"),
            name = json.optString("name"),
            host = json.optString("host"),
            port = json.optInt("port", 22),
            username = json.optString("username"),
            authMode = json.optInt("authMode", AUTH_PASSWORD),
            password = json.optString("password"),
            privateKey = json.optString("privateKey"),
            passphrase = json.optString("passphrase")
        )
    }
}
