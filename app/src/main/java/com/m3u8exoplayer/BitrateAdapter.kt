package com.m3u8exoplayer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.m3u8exoplayer.databinding.ItemBitrateChipBinding

class BitrateAdapter(
    private val bitrates: List<BitrateInfo>,
    private val onBitrateSelected: (BitrateInfo) -> Unit
) : RecyclerView.Adapter<BitrateAdapter.BitrateViewHolder>() {

    private var selectedPosition = 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BitrateViewHolder {
        val binding = ItemBitrateChipBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return BitrateViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BitrateViewHolder, position: Int) {
        holder.bind(bitrates[position], position == selectedPosition)
    }

    override fun getItemCount(): Int = bitrates.size

    inner class BitrateViewHolder(
        private val binding: ItemBitrateChipBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(bitrate: BitrateInfo, isSelected: Boolean) {
            binding.root.text = bitrate.name
            binding.root.isChecked = isSelected
            
            // 设置点击事件
            binding.root.setOnClickListener {
                val previousPosition = selectedPosition
                selectedPosition = adapterPosition
                
                // 更新UI
                notifyItemChanged(previousPosition)
                notifyItemChanged(selectedPosition)
                
                // 回调选择事件
                onBitrateSelected(bitrate)
            }
        }
    }
}
