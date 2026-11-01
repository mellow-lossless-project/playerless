package com.playerless

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

sealed class HomeItem {
    class Header(val title: String) : HomeItem()

    class Tile(
        val title: String,
        val image: String,
        val onLongClick: (() -> Unit)? = null,
        val onClick: () -> Unit,
    ) : HomeItem()

    class Row(
        val title: String,
        val subtitle: String?,
        val onClick: (() -> Unit)? = null,
    ) : HomeItem()
}

// Home and settings: square tiles under section headers, plus plain rows.
class HomeAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var items: List<HomeItem> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    private class Holder(view: View) : RecyclerView.ViewHolder(view)

    override fun getItemViewType(position: Int) = when (items[position]) {
        is HomeItem.Header -> R.layout.item_header
        is HomeItem.Tile -> R.layout.item_tile
        is HomeItem.Row -> R.layout.item_home
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(viewType, parent, false)
        return Holder(view).also { holder ->
            view.setOnClickListener {
                when (val item = items.getOrNull(holder.bindingAdapterPosition)) {
                    is HomeItem.Tile -> item.onClick()
                    is HomeItem.Row -> item.onClick?.invoke()
                    else -> Unit
                }
            }
            view.setOnLongClickListener {
                val onLongClick = (items.getOrNull(holder.bindingAdapterPosition) as? HomeItem.Tile)?.onLongClick
                onLongClick?.invoke()
                onLongClick != null
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val view = holder.itemView
        when (val item = items[position]) {
            is HomeItem.Header -> (view as TextView).text = item.title
            is HomeItem.Row -> {
                view.findViewById<TextView>(R.id.title).text = item.title
                view.findViewById<TextView>(R.id.subtitle).run {
                    text = item.subtitle
                    visibility = if (item.subtitle == null) View.GONE else View.VISIBLE
                }
            }
            is HomeItem.Tile -> {
                Covers.load(item.image, view.findViewById<ImageView>(R.id.cover))
                view.findViewById<TextView>(R.id.label).run {
                    text = item.title
                    // Over a cover the name needs a band to stay readable.
                    val hasCover = item.image.isNotEmpty()
                    setBackgroundColor(if (hasCover) 0xB3000000.toInt() else 0)
                    layoutParams = (layoutParams as FrameLayout.LayoutParams).apply {
                        gravity = if (hasCover) Gravity.BOTTOM else Gravity.CENTER
                    }
                }
            }
        }
    }

    override fun getItemCount() = items.size
}
