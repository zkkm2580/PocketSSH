package com.zkkm.pocketssh.ssh

import android.os.Handler
import android.util.Base64
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Owns one interactive SSH shell session.
 *
 * Networking happens on a dedicated worker thread; every callback is delivered
 * on the main thread so the UI can stay dumb about threading.
 */
class SshSession(
    private val host: SshHost,
    private val knownHosts: KnownHostsStore,
    private val mainHandler: Handler,
    private val listener: Listener
) {

    interface Listener {
        fun onConnected()
        fun onOutput(data: ByteArray, length: Int)
        fun onDisconnected(reason: String)

        /** Called on the main thread; [respond] must be invoked exactly once. */
        fun onHostKeyPrompt(endpoint: String, keyType: String, fingerprint: String, respond: (Boolean) -> Unit)
    }

    @Volatile
    private var session: Session? = null

    @Volatile
    private var channel: ChannelShell? = null

    @Volatile
    private var channelOut: OutputStream? = null

    @Volatile
    private var input: InputStream? = null

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var closing = false

    @Volatile
    private var connected = false

    @Volatile
    private var finishReported = false

    @Volatile
    var terminalCols: Int = 80
        private set

    @Volatile
    var terminalRows: Int = 24
        private set

    private val writeLock = Any()

    val isConnected: Boolean get() = connected

    private val endpoint: String
        get() = "${host.host}:${host.port}"

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    fun connect(cols: Int = 80, rows: Int = 24) {
        if (worker?.isAlive == true) return
        terminalCols = cols.coerceAtLeast(20)
        terminalRows = rows.coerceAtLeast(5)
        closing = false
        finishReported = false
        worker = Thread({ runSession() }, "pocketssh-session").apply {
            isDaemon = true
            start()
        }
    }

    fun disconnect() {
        closing = true
        connected = false
        try {
            channel?.disconnect()
        } catch (_: Exception) {
        }
        try {
            session?.disconnect()
        } catch (_: Exception) {
        }
        try {
            worker?.interrupt()
        } catch (_: Exception) {
        }
        worker = null
        channelOut = null
        input = null
    }

    fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val out = channelOut ?: return
        synchronized(writeLock) {
            try {
                out.write(bytes)
                out.flush()
            } catch (e: IOException) {
                // The read loop will notice and report the disconnect.
            }
        }
    }

    fun resize(cols: Int, rows: Int) {
        val c = cols.coerceAtLeast(20)
        val r = rows.coerceAtLeast(5)
        if (c == terminalCols && r == terminalRows) return
        terminalCols = c
        terminalRows = r
        val ch = channel ?: return
        try {
            ch.setPtySize(c, r, c * 8, r * 16)
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------------
    // Worker
    // ---------------------------------------------------------------------

    private fun runSession() {
        var failure: String? = null
        try {
            val jsch = JSch()
            if (host.authMode == SshHost.AUTH_KEY) {
                val key = host.privateKey.trim()
                if (key.isEmpty()) {
                    throw JSchException("Private key is empty")
                }
                val passphrase = if (host.passphrase.isEmpty()) null else host.passphrase.toByteArray(Charsets.UTF_8)
                jsch.addIdentity("pocketssh-${host.id}", key.toByteArray(Charsets.UTF_8), null, passphrase)
            }
            jsch.hostKeyRepository = buildHostKeyRepository()

            if (closing) return

            val newSession = jsch.getSession(host.username, host.host, host.port)
            newSession.setConfig("StrictHostKeyChecking", "yes")
            newSession.setConfig(
                "PreferredAuthentications",
                if (host.authMode == SshHost.AUTH_KEY) "publickey,keyboard-interactive,password"
                else "password,keyboard-interactive,publickey"
            )
            newSession.setConfig("HashKnownHosts", "no")
            if (host.authMode == SshHost.AUTH_PASSWORD) {
                newSession.setPassword(host.password)
            }
            newSession.userInfo = passwordUserInfo
            newSession.setServerAliveInterval(60_000)
            newSession.setServerAliveCountMax(3)
            session = newSession

            newSession.connect(20_000)
            if (closing) return

            val shell = newSession.openChannel("shell") as ChannelShell
            shell.setPtyType("xterm-256color", terminalCols, terminalRows, 0, 0)
            shell.setPtySize(terminalCols, terminalRows, terminalCols * 8, terminalRows * 16)
            try {
                shell.setEnv("TERM", "xterm-256color")
                shell.setEnv("COLORTERM", "truecolor")
                shell.setEnv("LANG", "en_US.UTF-8")
            } catch (_: Exception) {
                // Servers may refuse environment requests; that is harmless.
            }
            channel = shell

            val stream = shell.inputStream
            val out = shell.outputStream
            input = stream
            shell.connect(20_000)
            channelOut = out
            connected = true

            // The view may have been laid out while the channel was still opening;
            // push the freshest grid size now that a PTY exists.
            try {
                shell.setPtySize(terminalCols, terminalRows, terminalCols * 8, terminalRows * 16)
            } catch (_: Exception) {
            }

            post { listener.onConnected() }

            val buffer = ByteArray(32 * 1024)
            while (!closing) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                val chunk = ByteArray(read)
                System.arraycopy(buffer, 0, chunk, 0, read)
                post { listener.onOutput(chunk, chunk.size) }
            }
            if (!closing) failure = "服务器已断开连接"
        } catch (e: JSchException) {
            failure = describe(e)
        } catch (e: SocketTimeoutException) {
            failure = "连接超时，请检查网络或服务器地址"
        } catch (e: UnknownHostException) {
            failure = "无法解析主机名：${host.host}"
        } catch (e: ConnectException) {
            failure = "连接被拒绝，端口 ${host.port} 可能未开放"
        } catch (e: NoRouteToHostException) {
            failure = "无法到达主机，请检查网络"
        } catch (e: IOException) {
            failure = "网络错误：${e.message ?: "IO 异常"}"
        } catch (e: InterruptedException) {
            failure = "连接已取消"
        } catch (e: Exception) {
            failure = "连接失败：${e.message ?: e.javaClass.simpleName}"
        } finally {
            cleanup()
            val reason = failure
            if (!closing && !finishReported) {
                finishReported = true
                post { listener.onDisconnected(reason ?: "连接已关闭") }
            } else if (closing && !finishReported) {
                finishReported = true
                connected = false
            }
        }
    }

    private fun cleanup() {
        connected = false
        channelOut = null
        input = null
        try {
            channel?.disconnect()
        } catch (_: Exception) {
        }
        try {
            session?.disconnect()
        } catch (_: Exception) {
        }
        channel = null
        session = null
    }

    private fun post(block: () -> Unit) {
        mainHandler.post { block() }
    }

    private fun describe(e: JSchException): String {
        val message = e.message ?: ""
        return when {
            message.contains("Auth fail", ignoreCase = true) -> "认证失败，请检查用户名 / 密码 / 密钥"
            message.contains("Auth cancel", ignoreCase = true) -> "认证已取消"
            message.contains("HostKey has been changed", ignoreCase = true) ->
                "主机密钥已更改！可能存在中间人攻击，已阻止连接"
            message.contains("UnknownHostKey", ignoreCase = true) -> "已拒绝未知主机密钥"
            message.contains("session is down", ignoreCase = true) -> "会话已断开"
            message.contains("timeout", ignoreCase = true) -> "连接超时，请检查网络或服务器地址"
            message.contains("invalid privatekey", ignoreCase = true) -> "私钥格式无法识别"
            message.contains("Algorithm negotiation fail", ignoreCase = true) -> "算法协商失败，服务器配置不兼容"
            else -> "SSH 错误：${message.ifEmpty { "未知错误" }}"
        }
    }

    // ---------------------------------------------------------------------
    // Host key verification (trust on first use)
    // ---------------------------------------------------------------------

    private fun buildHostKeyRepository(): HostKeyRepository = object : HostKeyRepository {

        override fun check(hostName: String?, key: ByteArray?): Int {
            if (key == null) return HostKeyRepository.OK
            val id = endpoint
            val type = keyTypeOf(key)
            val encoded = Base64.encodeToString(key, Base64.NO_WRAP)
            val stored = knownHosts.get(id)
            if (stored == null) {
                val fingerprint = knownHosts.fingerprint(key)
                val accepted = askUser(id, type, fingerprint)
                return if (accepted) {
                    knownHosts.put(id, "$type $encoded")
                    HostKeyRepository.OK
                } else {
                    HostKeyRepository.NOT_INCLUDED
                }
            }
            val parts = stored.split(' ')
            return if (parts.size >= 2 && parts[1] == encoded) {
                HostKeyRepository.OK
            } else {
                HostKeyRepository.CHANGED
            }
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) {
            if (hostkey == null) return
            knownHosts.put(endpoint, "${hostkey.type} ${hostkey.key}")
        }

        override fun remove(host: String?, type: String?) {
            knownHosts.forget(endpoint)
        }

        override fun remove(host: String?, type: String?, key: ByteArray?) {
            knownHosts.forget(endpoint)
        }

        override fun getKnownHostsRepositoryID(): String = "pocketssh-known-hosts"

        override fun getHostKey(): Array<HostKey> = emptyArray()

        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    private fun keyTypeOf(key: ByteArray): String {
        if (key.size < 4) return "ssh-unknown"
        val length = ((key[0].toInt() and 0xFF) shl 24) or
            ((key[1].toInt() and 0xFF) shl 16) or
            ((key[2].toInt() and 0xFF) shl 8) or
            (key[3].toInt() and 0xFF)
        if (length <= 0 || length > key.size - 4) return "ssh-unknown"
        return String(key, 4, length, Charsets.US_ASCII)
    }

    private fun askUser(id: String, keyType: String, fingerprint: String): Boolean {
        val latch = CountDownLatch(1)
        val accepted = java.util.concurrent.atomic.AtomicBoolean(false)
        mainHandler.post {
            listener.onHostKeyPrompt(id, keyType, fingerprint) { ok ->
                accepted.set(ok)
                latch.countDown()
            }
        }
        return try {
            if (!latch.await(120, TimeUnit.SECONDS)) false else accepted.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    // ---------------------------------------------------------------------
    // Password / keyboard-interactive auth
    // ---------------------------------------------------------------------

    private val passwordUserInfo = object : UserInfo, UIKeyboardInteractive {
        override fun getPassword(): String = host.password

        override fun promptPassword(message: String?): Boolean = true

        override fun getPassphrase(): String = host.passphrase

        override fun promptPassphrase(message: String?): Boolean = true

        override fun promptYesNo(message: String?): Boolean = false

        override fun showMessage(message: String?) = Unit

        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<out String>?,
            echo: BooleanArray?
        ): Array<String> {
            val count = prompt?.size ?: 0
            return Array(count) { index ->
                if (index == 0) host.password else ""
            }
        }
    }
}
