package com.playerless

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

// A row above the songs that leads somewhere else: the artist a search names.
class Lead(val title: String, val subtitle: String, val image: String, val onClick: () -> Unit)

class SongAdapter(
    private val onClick: (position: Int) -> Unit,
    private val actionIcon: (Song) -> Int,
    private val onAction: (Song) -> Unit,
    private val onLongClick: (Song) -> Unit,
    private val subtitle: (Song) -> String,
) : RecyclerView.Adapter<SongAdapter.Holder>() {
    var songs: List<Song> = emptyList()
        private set
    var lead: Lead? = null
        private set

    fun show(songs: List<Song>, lead: Lead?) {
        this.songs = songs
        this.lead = lead
        notifyDataSetChanged()
    }

    // Songs are counted from 0 below the lead row, as the callbacks above count them.
    private val offset get() = if (lead != null) 1 else 0

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.cover)
        val title: TextView = view.findViewById(R.id.title)
        val artist: TextView = view.findViewById(R.id.artist)
        val action: ImageButton = view.findViewById(R.id.action)
    }

    // Songs keep type 0, which the recycled pool and the poster rules count on.
    override fun getItemViewType(position: Int) = if (position < offset) LEAD else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        val holder = Holder(view)
        if (viewType == LEAD) {
            holder.action.visibility = View.GONE
            view.setOnClickListener { lead?.onClick?.invoke() }
            return holder
        }
        view.setOnClickListener {
            val position = holder.bindingAdapterPosition - offset
            if (position in songs.indices) onClick(position)
        }
        view.setOnLongClickListener {
            val position = holder.bindingAdapterPosition - offset
            if (position in songs.indices) onLongClick(songs[position])
            true
        }
        holder.action.setOnClickListener {
            val position = holder.bindingAdapterPosition - offset
            if (position in songs.indices) onAction(songs[position])
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val lead = lead
        if (lead != null && position == 0) {
            holder.title.text = lead.title
            holder.artist.text = lead.subtitle
            Covers.load(lead.image, holder.cover, row = true)
            return
        }
        val song = songs[position - offset]
        holder.title.text = song.title
        holder.artist.text = subtitle(song)
        holder.action.setImageResource(actionIcon(song))
        Covers.load(Downloads.imageFor(song), holder.cover, row = true)
    }

    override fun getItemCount() = songs.size + offset

    companion object {
        // Any id that isn't 0 or one of HomeAdapter's layouts.
        const val LEAD = R.layout.item_song
    }
}
