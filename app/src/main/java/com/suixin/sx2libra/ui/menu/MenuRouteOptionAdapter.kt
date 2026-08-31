package com.suixin.sx2libra.ui.menu

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.suixin.sx2libra.R
import com.suixin.sx2libra.model.SiteRoute

class MenuRouteOptionAdapter(
    private val onRouteSelected: (SiteRoute?) -> Unit,
) : RecyclerView.Adapter<MenuRouteOptionAdapter.OptionViewHolder>() {
    private sealed interface Item {
        data class Route(val value: SiteRoute) : Item
        data class Status(val message: String) : Item
        object Custom : Item
    }

    private val items = ArrayList<Item>()
    private var allRoutes: List<SiteRoute> = emptyList()
    private var query: String = ""

    fun submitRoutes(
        context: Context,
        routes: List<SiteRoute>,
    ) {
        allRoutes = routes
        rebuildItems(context)
    }

    fun setQuery(context: Context, query: String) {
        this.query = query.trim()
        rebuildItems(context)
    }

    private fun rebuildItems(context: Context) {
        val filteredRoutes = allRoutes.filter { route ->
            query.isBlank() ||
                route.name.contains(query, ignoreCase = true) ||
                route.path.contains(query, ignoreCase = true)
        }
        items.clear()
        items += Item.Custom
        if (filteredRoutes.isEmpty() && query.isNotEmpty()) {
            items += Item.Status(context.getString(R.string.menu_route_search_empty))
        }
        items += filteredRoutes.map(Item::Route)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OptionViewHolder =
        OptionViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_menu_route_option, parent, false),
        )

    override fun onBindViewHolder(holder: OptionViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item.Route -> {
                holder.name.text = item.value.name
                holder.path.text = item.value.path
                holder.itemView.isEnabled = true
                holder.itemView.setOnClickListener { onRouteSelected(item.value) }
            }
            Item.Custom -> {
                holder.name.text = holder.itemView.context.getString(R.string.menu_add_custom)
                holder.path.text = holder.itemView.context.getString(R.string.menu_add_custom_hint)
                holder.itemView.isEnabled = true
                holder.itemView.setOnClickListener { onRouteSelected(null) }
            }
            is Item.Status -> {
                holder.name.text = item.message
                holder.path.text = ""
                holder.itemView.isEnabled = false
                holder.itemView.setOnClickListener(null)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class OptionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.menu_route_name)
        val path: TextView = view.findViewById(R.id.menu_route_path)
    }
}
