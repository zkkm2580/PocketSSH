package com.zkkm.pocketssh

import android.os.Bundle
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.zkkm.pocketssh.ssh.HostStore
import com.zkkm.pocketssh.ssh.SshHost

/** Create or edit a connection profile. */
class HostEditActivity : AppCompatActivity() {

    private lateinit var store: HostStore

    private lateinit var nameLayout: TextInputLayout
    private lateinit var hostLayout: TextInputLayout
    private lateinit var portLayout: TextInputLayout
    private lateinit var userLayout: TextInputLayout
    private lateinit var passwordLayout: TextInputLayout
    private lateinit var keyLayout: TextInputLayout
    private lateinit var passphraseLayout: TextInputLayout

    private lateinit var nameInput: TextInputEditText
    private lateinit var hostInput: TextInputEditText
    private lateinit var portInput: TextInputEditText
    private lateinit var userInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText
    private lateinit var keyInput: TextInputEditText
    private lateinit var passphraseInput: TextInputEditText
    private lateinit var credentialHelp: TextView
    private lateinit var authToggle: MaterialButtonToggleGroup

    private var hostId: Long = 0L
    private var authMode: Int = SshHost.AUTH_PASSWORD

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_host_edit)

        store = HostStore(this)
        hostId = intent.getLongExtra(EXTRA_HOST_ID, 0L)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(
            if (hostId == 0L) R.string.title_host_edit else R.string.title_host_edit_existing
        )
        toolbar.setNavigationOnClickListener { finish() }

        nameLayout = findViewById(R.id.nameLayout)
        hostLayout = findViewById(R.id.hostLayout)
        portLayout = findViewById(R.id.portLayout)
        userLayout = findViewById(R.id.userLayout)
        passwordLayout = findViewById(R.id.passwordLayout)
        keyLayout = findViewById(R.id.keyLayout)
        passphraseLayout = findViewById(R.id.passphraseLayout)

        nameInput = findViewById(R.id.nameInput)
        hostInput = findViewById(R.id.hostInput)
        portInput = findViewById(R.id.portInput)
        userInput = findViewById(R.id.userInput)
        passwordInput = findViewById(R.id.passwordInput)
        keyInput = findViewById(R.id.keyInput)
        passphraseInput = findViewById(R.id.passphraseInput)
        credentialHelp = findViewById(R.id.credentialHelp)
        authToggle = findViewById(R.id.authToggle)

        authToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                applyAuthMode(if (checkedId == R.id.authKey) SshHost.AUTH_KEY else SshHost.AUTH_PASSWORD)
            }
        }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.saveButton)
            .setOnClickListener { save() }

        if (hostId == 0L) {
            portInput.setText("22")
            authToggle.check(R.id.authPassword)
            applyAuthMode(SshHost.AUTH_PASSWORD)
        } else {
            val host = store.find(hostId)
            if (host == null) {
                finish()
                return
            }
            bind(host)
        }
    }

    private fun bind(host: SshHost) {
        nameInput.setText(host.name)
        hostInput.setText(host.host)
        portInput.setText(host.port.toString())
        userInput.setText(host.username)
        passwordInput.setText(host.password)
        keyInput.setText(host.privateKey)
        passphraseInput.setText(host.passphrase)
        authMode = host.authMode
        authToggle.check(if (host.authMode == SshHost.AUTH_KEY) R.id.authKey else R.id.authPassword)
        applyAuthMode(host.authMode)
    }

    private fun applyAuthMode(mode: Int) {
        authMode = mode
        val useKey = mode == SshHost.AUTH_KEY
        passwordLayout.visibility = if (useKey) android.view.View.GONE else android.view.View.VISIBLE
        keyLayout.visibility = if (useKey) android.view.View.VISIBLE else android.view.View.GONE
        passphraseLayout.visibility = if (useKey) android.view.View.VISIBLE else android.view.View.GONE
        credentialHelp.setText(if (useKey) R.string.key_help else R.string.password_help)
    }

    private fun save() {
        nameLayout.error = null
        hostLayout.error = null
        portLayout.error = null
        userLayout.error = null
        passwordLayout.error = null
        keyLayout.error = null

        val hostName = hostInput.text?.toString()?.trim().orEmpty()
        if (hostName.isEmpty()) {
            hostLayout.error = getString(R.string.err_host_required)
            return
        }
        val port = portInput.text?.toString()?.trim()?.toIntOrNull()
        if (port == null || port !in 1..65535) {
            portLayout.error = getString(R.string.err_port_invalid)
            return
        }
        val user = userInput.text?.toString()?.trim().orEmpty()
        if (user.isEmpty()) {
            userLayout.error = getString(R.string.err_user_required)
            return
        }
        val password = passwordInput.text?.toString().orEmpty()
        val key = keyInput.text?.toString().orEmpty()
        if (authMode == SshHost.AUTH_PASSWORD && password.isEmpty()) {
            passwordLayout.error = getString(R.string.err_credential_required)
            return
        }
        if (authMode == SshHost.AUTH_KEY && key.trim().isEmpty()) {
            keyLayout.error = getString(R.string.err_credential_required)
            return
        }

        val existing = if (hostId == 0L) null else store.find(hostId)
        val host = SshHost(
            id = hostId,
            name = nameInput.text?.toString()?.trim().orEmpty(),
            host = hostName,
            port = port,
            username = user,
            authMode = authMode,
            password = password,
            privateKey = if (authMode == SshHost.AUTH_KEY) key else "",
            passphrase = passphraseInput.text?.toString().orEmpty()
        )
        val saved = store.save(host)
        if (existing != null && existing.host != saved.host) {
            // Different server on the same profile: forget the old pinned key.
            com.zkkm.pocketssh.ssh.KnownHostsStore(this).forget(existing.endpoint())
        }
        android.widget.Toast.makeText(this, R.string.toast_saved, android.widget.Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        const val EXTRA_HOST_ID = "extra_host_id"
    }
}
