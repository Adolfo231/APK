package com.fastdtv.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.fastdtv.app.databinding.ItemChannelBinding

class ChannelAdapter(
    private var channels: List<ChannelItem>,
    private val onChannelClick: (ChannelItem) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {

    inner class ChannelViewHolder(val binding: ItemChannelBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION && position in channels.indices) {
                    onChannelClick(channels[position])
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemChannelBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ChannelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        val item = channels[position]
        holder.binding.txtChannelNumber.text = item.displayNumber
        holder.binding.txtChannelName.text = item.displayName
    }

    override fun getItemCount(): Int = channels.size

    fun updateList(newList: List<ChannelItem>) {
        channels = newList
        notifyDataSetChanged()
    }
}
