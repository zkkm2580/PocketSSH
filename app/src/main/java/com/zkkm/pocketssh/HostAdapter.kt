package com.zkkm.pocketssh

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.zkkm.pocketssh.ssh.SshHost

/** Host list adapter. Small lists, so a plain adapter is plenty. */
class HostAdapter(
    private val onClick: (SshHost) -> Unit,
    private val onLongClick: (SshHost, View) -> Unit
) : RecyclerView.Adapter<HostAdapter.HostViewHolder>() {

    private val items = mutableListOf<SshHost>()

    fun submit(hosts: List<SshHost>) {
        items.clear()
        items.addAll(hosts)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HostViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_host, parent, false)
        return HostViewHolder(view)
    }

    override fun onBindViewHolder(holder: HostViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class HostViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.hostName)
        private val target: TextView = itemView.findViewById(R.id.hostTarget)
        private val badge: TextView = itemView.findViewById(R.id.authBadge)

        fun bind(host: SshHost) {
            name.text = host.displayName()
            target.text = host.subtitle()
            badge.text = host.authLabel()
            itemView.setOnClickListener { onClick(host) }
            itemView.setOnLongClickListener { view ->
                onLongClick(host, view)
                true
            }
        }
    }
}
