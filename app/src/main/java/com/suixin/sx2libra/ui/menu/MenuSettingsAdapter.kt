package com.suixin.sx2libra.ui.menu

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.suixin.sx2libra.R
import com.suixin.sx2libra.model.ForumMenu

class MenuSettingsAdapter(
    private val onDelete: (ForumMenu) -> Unit,
    var onDragHandleDown: ((RecyclerView.ViewHolder) -> Unit)? = null
) : RecyclerView.Adapter<MenuSettingsAdapter.MenuViewHolder>() {
    private val items = ArrayList<ForumMenu>()

    val currentMenus: List<ForumMenu>
        get() = items.toList()

    fun submitList(menus: List<ForumMenu>) {
        items.clear()
        items.addAll(menus)
        notifyDataSetChanged()
    }

    fun move(from: Int, to: Int): Boolean {
        if (from !in items.indices || to !in items.indices || from == to) return false
        val item = items.removeAt(from)
        items.add(to, item)
        notifyItemMoved(from, to)
        return true
    }

    fun currentMenuIds(): List<String> = items.map(ForumMenu::id)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MenuViewHolder =
        MenuViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_forum_menu, parent, false)
        )

    override fun onBindViewHolder(holder: MenuViewHolder, position: Int) {
        val menu = items[position]
        holder.name.text = menu.name
        holder.path.text = menu.path
        holder.delete.setOnClickListener { onDelete(menu) }
        holder.dragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                onDragHandleDown?.invoke(holder)
            }
            // ItemTouchHelper owns the gesture after startDrag().
            true
        }
        holder.dragHandle.contentDescription =
            holder.itemView.context.getString(R.string.menu_drag_handle_description, menu.name)
    }

    override fun getItemCount(): Int = items.size

    class MenuViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.menu_name)
        val path: TextView = view.findViewById(R.id.menu_path)
        val delete: ImageButton = view.findViewById(R.id.menu_delete)
        val dragHandle: ImageButton = view.findViewById(R.id.menu_drag_handle)
    }
}

