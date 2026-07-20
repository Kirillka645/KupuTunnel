package com.kuputunnel.app

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class ConfigAdapter(
    private val context: Context,
    private val configs: List<ConfigWithPing>
) : RecyclerView.Adapter<ConfigAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_config, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(configs[position], position)
    }

    override fun getItemCount(): Int = configs.size

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: MaterialCardView = itemView.findViewById(R.id.cardConfig)
        private val tvIndex: TextView = itemView.findViewById(R.id.tvIndex)
        private val tvHost: TextView = itemView.findViewById(R.id.tvHost)
        private val tvRemark: TextView = itemView.findViewById(R.id.tvRemark)
        private val tvProtocol: TextView = itemView.findViewById(R.id.tvProtocol)
        private val tvPing: TextView = itemView.findViewById(R.id.tvPing)
        private val btnCopy: MaterialButton = itemView.findViewById(R.id.btnCopy)
        private val btnConnect: MaterialButton = itemView.findViewById(R.id.btnConnect)
        private val btnFavorite: ImageView = itemView.findViewById(R.id.btnFavorite)

        fun bind(config: ConfigWithPing, position: Int) {
            tvIndex.text = (position + 1).toString()
            val hostLabel = when {
                config.host.isNotBlank() && config.port > 0 -> "${config.host}:${config.port}"
                else -> {
                    val n = ConfigManager.parseNode(config.url)
                    if (n != null) "${n.host}:${n.port}" else config.url.take(36)
                }
            }
            tvHost.text = hostLabel

            val remark = config.remark.ifBlank {
                ConfigManager.parseNode(config.url)?.remark.orEmpty()
            }
            if (remark.isNotBlank()) {
                tvRemark.text = remark
                tvRemark.visibility = View.VISIBLE
            } else {
                tvRemark.visibility = View.GONE
            }

            val proto = config.protocol.ifBlank {
                ConfigManager.parseNode(config.url)?.protocol?.uppercase().orEmpty()
            }
            tvProtocol.text = proto.ifBlank { "?" }

            if (config.pingMs > 0) {
                tvPing.text = context.getString(R.string.ping_format, config.pingMs)
                val pingColor = when {
                    config.pingMs < 120 -> R.color.ping_excellent
                    config.pingMs < 350 -> R.color.ping_good
                    else -> R.color.ping_slow
                }
                tvPing.setTextColor(ContextCompat.getColor(context, pingColor))
                tvPing.visibility = View.VISIBLE
            } else {
                tvPing.visibility = View.GONE
            }

            refreshStar(config.url)

            btnFavorite.setOnClickListener {
                val added = ConfigCache.toggleFavorite(context, config.url)
                refreshStar(config.url)
                Toast.makeText(
                    context,
                    if (added) "В избранном" else "Убрано из избранного",
                    Toast.LENGTH_SHORT
                ).show()
            }

            btnCopy.setOnClickListener {
                ConfigLauncher.copy(context, config.url)
                Toast.makeText(context, R.string.config_copied, Toast.LENGTH_SHORT).show()
            }

            val connect = { ConfigLauncher.launch(context, config.url) }
            btnConnect.setOnClickListener { connect() }
            card.setOnClickListener { connect() }
        }

        private fun refreshStar(url: String) {
            val fav = ConfigCache.isFavorite(context, url)
            btnFavorite.setImageResource(
                if (fav) R.drawable.ic_star else R.drawable.ic_star_outline
            )
        }
    }
}
