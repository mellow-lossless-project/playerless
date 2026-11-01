package com.playerless

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SongAdapter(
    private val onClick: (position: Int) -> Unit,
    private val actionIcon: (Song) -> Int,
    private val onAction: (Song) -> Unit,
    private val onLongClick: (Song) -> Unit,
    private val subtitle: (Song) -> String,
) : RecyclerView.Adapter<SongAdapter.Holder>() {
    var songs: List<Song> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.cover)
        val title: TextView = view.findViewById(R.id.title)
        val artist: TextView = view.findViewById(R.id.artist)
        val action: ImageButton = view.findViewById(R.id.action)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        return Holder(view).also { holder ->
            view.setOnClickListener {
                val position = holder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick(position)
            }
            view.setOnLongClickListener {
                val position = holder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onLongClick(songs[position])
                true
            }
            holder.action.setOnClickListener {
                val position = holder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onAction(songs[position])
            }
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val song = songs[position]
        holder.title.text = song.title
        holder.artist.text = subtitle(song)
        holder.action.setImageResource(actionIcon(song))
        Covers.load(Downloads.imageFor(song), holder.cover, row = true)
    }

    override fun getItemCount() = songs.size
}
