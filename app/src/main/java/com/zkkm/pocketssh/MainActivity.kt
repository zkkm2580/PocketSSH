package com.zkkm.pocketssh

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zkkm.pocketssh.ssh.HostStore
import com.zkkm.pocketssh.ssh.KnownHostsStore
import com.zkkm.pocketssh.ssh.SshHost

/** Saved connection list: the app's launch screen. */
class MainActivity : AppCompatActivity() {

    private lateinit var store: HostStore
    private lateinit var knownHosts: KnownHostsStore
    private lateinit var adapter: HostAdapter
    private lateinit var hostList: RecyclerView
    private lateinit var emptyState: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        store = HostStore(this)
        knownHosts = KnownHostsStore(this)

        hostList = findViewById(R.id.hostList)
        emptyState = findViewById(R.id.emptyState)
        adapter = HostAdapter(
            onClick = { openTerminal(it) },
            onLongClick = { host, anchor -> showHostActions(host, anchor) }
        )
        hostList.layoutManager = LinearLayoutManager(this)
        hostList.adapter = adapter

        findViewById<View>(R.id.addHost).setOnClickListener {
            startActivity(Intent(this, HostEditActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        reloadHosts()
    }

    private fun reloadHosts() {
        val hosts = store.all()
        adapter.submit(hosts)
        emptyState.isVisible = hosts.isEmpty()
        hostList.isVisible = hosts.isNotEmpty()
    }

    private fun openTerminal(host: SshHost) {
        startActivity(
            Intent(this, TerminalActivity::class.java)
                .putExtra(TerminalActivity.EXTRA_HOST_ID, host.id)
        )
    }

    private fun showHostActions(host: SshHost, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(Menu.NONE, MENU_EDIT, 0, R.string.action_edit)
        popup.menu.add(Menu.NONE, MENU_DUPLICATE, 1, R.string.action_duplicate)
        popup.menu.add(Menu.NONE, MENU_FORGET_KEY, 2, R.string.action_forget_key)
        popup.menu.add(Menu.NONE, MENU_DELETE, 3, R.string.action_delete)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_EDIT -> {
                    startActivity(
                        Intent(this, HostEditActivity::class.java)
                            .putExtra(HostEditActivity.EXTRA_HOST_ID, host.id)
                    )
                    true
                }
                MENU_DUPLICATE -> {
                    store.duplicate(host)
                    reloadHosts()
                    true
                }
                MENU_FORGET_KEY -> {
                    knownHosts.forget(host.endpoint())
                    true
                }
                MENU_DELETE -> {
                    confirmDelete(host)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun confirmDelete(host: SshHost) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(getString(R.string.dialog_delete_message, host.displayName()))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                store.delete(host.id)
                reloadHosts()
            }
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_about -> {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.about_title)
                .setMessage(R.string.about_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            true
        }
        R.id.action_forget_all_keys -> {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_forget_keys_title)
                .setMessage(R.string.dialog_forget_keys_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    knownHosts.forgetAll()
                    toast(getString(R.string.toast_keys_cleared))
                }
                .show()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val MENU_EDIT = 1
        private const val MENU_DUPLICATE = 2
        private const val MENU_FORGET_KEY = 3
        private const val MENU_DELETE = 4
    }
}
