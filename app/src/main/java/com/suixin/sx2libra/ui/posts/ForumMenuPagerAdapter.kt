package com.suixin.sx2libra.ui.posts

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.suixin.sx2libra.model.ForumMenu
import java.nio.ByteBuffer
import java.security.MessageDigest

/** ViewPager2 adapter whose IDs remain tied to the menu ID across sorting/filtering. */
class ForumMenuPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
    private var menus: List<ForumMenu> = emptyList()
    private val assignedIds = LinkedHashMap<String, Long>()
    private val occupiedIds = HashSet<Long>()

    fun submitMenus(value: List<ForumMenu>) {
        menus = value.toList()
        notifyDataSetChanged()
    }

    fun menuAt(position: Int): ForumMenu? = menus.getOrNull(position)

    fun menus(): List<ForumMenu> = menus

    override fun getItemCount(): Int = menus.size

    override fun createFragment(position: Int): Fragment =
        ForumMenuPageFragment.newInstance(menus[position])

    override fun getItemId(position: Int): Long = stableId(menus[position].id)

    override fun containsItem(itemId: Long): Boolean = menus.any { stableId(it.id) == itemId }

    private fun stableId(menuId: String): Long {
        assignedIds[menuId]?.let { return it }
        val digest = MessageDigest.getInstance("SHA-256").digest(menuId.toByteArray(Charsets.UTF_8))
        var candidate = ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long
        if (candidate == RecyclerViewItemId.EMPTY) candidate = 1L
        while (!occupiedIds.add(candidate)) candidate++
        assignedIds[menuId] = candidate
        return candidate
    }

    private object RecyclerViewItemId {
        const val EMPTY = 0L
    }
}

