package com.zkkm.pocketssh

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MenuItem
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zkkm.pocketssh.ssh.HostStore
import com.zkkm.pocketssh.ssh.KnownHostsStore
import com.zkkm.pocketssh.ssh.SshHost
import com.zkkm.pocketssh.ssh.SshSession
import com.zkkm.pocketssh.term.TerminalView
import com.zkkm.pocketssh.term.VtEmulator

/**
 * Interactive terminal screen: owns the emulator, the SSH session and the
 * on-screen key bar.
 */
class TerminalActivity : AppCompatActivity(), TerminalView.Listener, SshSession.Listener {

    private lateinit var store: HostStore
    private lateinit var knownHosts: KnownHostsStore
    private lateinit var toolbar: MaterialToolbar
    private lateinit var terminal: TerminalView
    private lateinit var keyBar: LinearLayout
    private lateinit var emulator: VtEmulator

    private var session: SshSession? = null
    private var host: SshHost? = null
    private var gridCols = 80
    private var gridRows = 24
    private var ctrlKey: TextView? = null
    private var altKey: TextView? = null
    private var disconnectedDialogShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        store = HostStore(this)
        knownHosts = KnownHostsStore(this)

        val hostId = intent.getLongExtra(EXTRA_HOST_ID, 0L)
        host = store.find(hostId)
        if (host == null) {
            Toast.makeText(this, "连接不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        toolbar = findViewById(R.id.toolbar)
        terminal = findViewById(R.id.terminal)
        keyBar = findViewById(R.id.keyBar)

        toolbar.title = host!!.displayName()
        toolbar.subtitle = host!!.subtitle()
        toolbar.setNavigationOnClickListener { confirmExit() }
        toolbar.inflateMenu(R.menu.menu_terminal)
        toolbar.setOnMenuItemClickListener { onMenuAction(it) }

        setupTerminal()
        setupKeyBar()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmExit()
        })

        connect()
    }

    // ---------------------------------------------------------------------
    // Setup
    // ---------------------------------------------------------------------

    private fun setupTerminal() {
        emulator = VtEmulator(80, 24)
        emulator.onResponse = { text -> session?.write(text.toByteArray(Charsets.UTF_8)) }
        emulator.onTitleChanged = { title ->
            if (title.isNotBlank()) {
                toolbar.subtitle = title.take(60)
            }
        }
        emulator.onBell = {
            terminal.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        }

        terminal.emulator = emulator
        terminal.listener = this
        terminal.textSizeSp = store.terminalFontSize
        terminal.onModifierChanged = { updateModifierKeys() }
        terminal.setConnected(false)
        terminal.post { terminal.refreshGridSize() }
    }

    private class KeySpec(val label: String, val action: () -> Unit)

    private fun setupKeyBar() {
        val specs = listOf(
            KeySpec("ESC") { terminal.sendSequence("\u001b") },
            KeySpec("TAB") { terminal.sendSequence("\t") },
            KeySpec("CTRL") { terminal.toggleCtrl() },
            KeySpec("ALT") { terminal.toggleAlt() },
            KeySpec("↑") { terminal.sendArrow('A') },
            KeySpec("↓") { terminal.sendArrow('B') },
            KeySpec("←") { terminal.sendArrow('D') },
            KeySpec("→") { terminal.sendArrow('C') },
            KeySpec("HOME") { terminal.sendSequence(cursorSequence('H')) },
            KeySpec("END") { terminal.sendSequence(cursorSequence('F')) },
            KeySpec("PGUP") { terminal.sendSequence("\u001b[5~") },
            KeySpec("PGDN") { terminal.sendSequence("\u001b[6~") },
            KeySpec("DEL") { terminal.sendSequence("\u001b[3~") },
            KeySpec("/") { terminal.sendText("/") },
            KeySpec("-") { terminal.sendText("-") },
            KeySpec("|") { terminal.sendText("|") },
            KeySpec("~") { terminal.sendText("~") },
            KeySpec("^C") { terminal.sendSequence("\u0003") }
        )

        keyBar.removeAllViews()
        for (spec in specs) {
            val button = TextView(this).apply {
                text = spec.label
                setTextColor(ContextCompat.getColor(this@TerminalActivity, R.color.terminal_foreground))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(11), dp(7), dp(11), dp(7))
                background = ContextCompat.getDrawable(this@TerminalActivity, R.drawable.bg_key)
                isClickable = true
                isFocusable = false
                setOnClickListener {
                    terminal.requestFocus()
                    spec.action()
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.marginEnd = dp(3)
            button.layoutParams = params
            keyBar.addView(button)
            when (spec.label) {
                "CTRL" -> ctrlKey = button
                "ALT" -> altKey = button
            }
        }
        updateModifierKeys()
    }

    private fun updateModifierKeys() {
        val accent = ContextCompat.getColor(this, R.color.brand_primary)
        val normal = ContextCompat.getColor(this, R.color.terminal_foreground)
        ctrlKey?.let {
            it.setTextColor(if (terminal.ctrlActive) accent else normal)
            it.alpha = if (terminal.ctrlActive) 1f else 0.9f
        }
        altKey?.let {
            it.setTextColor(if (terminal.altActive) accent else normal)
            it.alpha = if (terminal.altActive) 1f else 0.9f
        }
    }

    private fun cursorSequence(final: Char): String =
        if (emulator.applicationCursorKeys) "\u001bO$final" else "\u001b[$final"

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------------
    // Connecting
    // ---------------------------------------------------------------------

    private fun connect() {
        val target = host ?: return
        session?.disconnect()
        disconnectedDialogShown = false
        val newSession = SshSession(
            target,
            knownHosts,
            Handler(Looper.getMainLooper()),
            this
        )
        session = newSession
        terminal.setConnected(false)
        updateStatus(getString(R.string.status_connecting))
        newSession.connect(gridCols, gridRows)
        store.lastUsedHostId = target.id
    }

    private fun updateStatus(text: String) {
        toolbar.subtitle = text
    }

    // ---------------------------------------------------------------------
    // TerminalView.Listener
    // ---------------------------------------------------------------------

    override fun onTerminalResize(cols: Int, rows: Int) {
        if (cols == gridCols && rows == gridRows) {
            terminal.invalidateTerminal()
            return
        }
        gridCols = cols
        gridRows = rows
        emulator.resize(cols, rows)
        session?.resize(cols, rows)
        terminal.invalidateTerminal()
    }

    override fun onTerminalInput(bytes: ByteArray) {
        session?.write(bytes)
    }

    // ---------------------------------------------------------------------
    // SshSession.Listener
    // ---------------------------------------------------------------------

    override fun onConnected() {
        if (isFinishing || isDestroyed) return
        updateStatus(getString(R.string.status_connected))
        terminal.setConnected(true)
        terminal.requestFocus()
    }

    override fun onOutput(data: ByteArray, length: Int) {
        if (isFinishing) return
        emulator.feed(data, 0, length)
        terminal.invalidateTerminal()
    }

    override fun onDisconnected(reason: String) {
        if (isFinishing || isDestroyed) return
        terminal.setConnected(false)
        updateStatus(getString(R.string.status_disconnected_reason, reason))
        if (!disconnectedDialogShown) {
            disconnectedDialogShown = true
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.status_disconnected)
                .setMessage(reason)
                .setNegativeButton(R.string.action_disconnect) { _, _ -> finish() }
                .setPositiveButton(R.string.action_reconnect) { _, _ -> connect() }
                .setCancelable(true)
                .show()
        }
    }

    override fun onHostKeyPrompt(
        endpoint: String,
        keyType: String,
        fingerprint: String,
        respond: (Boolean) -> Unit
    ) {
        if (isFinishing || isDestroyed) {
            respond(false)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.host_key_title)
            .setMessage(getString(R.string.host_key_message, endpoint, keyType, fingerprint))
            .setCancelable(false)
            .setNegativeButton(R.string.host_key_reject) { _, _ -> respond(false) }
            .setPositiveButton(R.string.host_key_accept) { _, _ -> respond(true) }
            .show()
    }

    // ---------------------------------------------------------------------
    // Menu / navigation
    // ---------------------------------------------------------------------

    private fun onMenuAction(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_reconnect -> {
            connect()
            true
        }
        R.id.action_copy_screen -> {
            copyScreen()
            true
        }
        R.id.action_clear_screen -> {
            emulator.clearScreen()
            terminal.invalidateTerminal()
            true
        }
        R.id.action_font_bigger -> {
            changeFontSize(1f)
            true
        }
        R.id.action_font_smaller -> {
            changeFontSize(-1f)
            true
        }
        R.id.action_disconnect -> {
            finish()
            true
        }
        else -> false
    }

    private fun changeFontSize(delta: Float) {
        val size = (terminal.textSizeSp + delta).coerceIn(8f, 26f)
        terminal.textSizeSp = size
        store.terminalFontSize = size
        terminal.post {
            terminal.refreshGridSize()
            terminal.invalidateTerminal()
        }
    }

    private fun copyScreen() {
        val text = emulator.snapshotText()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("terminal", text))
        Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
    }

    private fun confirmExit() {
        val active = session?.isConnected == true
        if (!active) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_disconnect_title)
            .setMessage(R.string.dialog_disconnect_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_disconnect) { _, _ -> finish() }
            .show()
    }

    override fun onDestroy() {
        session?.disconnect()
        session = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_HOST_ID = "extra_host_id"
    }
}
