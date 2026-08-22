package vn.npay.collmap

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView

/**
 * Pages a fixed set of already-built views through a ViewPager2.
 *
 * ViewPager2 wants a RecyclerView.Adapter, but these pages are not a list: they
 * are four (or five) specific panels whose controls the hosting fragment holds
 * references to and keeps in step with the camera, the board and the server. So
 * the views are inflated once, up front, and this adapter only parks each one
 * in whichever holder the pager is showing it in. Nothing is ever rebuilt, and
 * a page keeps its state whether or not it is currently on screen.
 *
 * The host sets offscreenPageLimit to the page count for the same reason.
 */
class PageAdapter(private val pages: List<View>) :
    RecyclerView.Adapter<PageAdapter.Holder>() {

    class Holder(val box: FrameLayout) : RecyclerView.ViewHolder(box)

    override fun getItemCount() = pages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val box = FrameLayout(parent.context)
        box.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        return Holder(box)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val page = pages[position]
        (page.parent as? ViewGroup)?.removeView(page)
        holder.box.removeAllViews()
        holder.box.addView(page, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}
