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
            
            // 动态设置颜色
            if (isSelected) {
                // 选中状态：白色文字，紫色背景
                binding.root.setTextColor(binding.root.context.getColor(R.color.white))
                binding.root.setChipBackgroundColorResource(R.color.purple_500)
                binding.root.setChipStrokeColorResource(R.color.purple_500)
                binding.root.chipStrokeWidth = 2f
            } else {
                // 未选中状态：白色文字，黑色背景
                binding.root.setTextColor(binding.root.context.getColor(R.color.white))
                binding.root.setChipBackgroundColorResource(R.color.black)
                binding.root.setChipStrokeColorResource(R.color.white)
                binding.root.chipStrokeWidth = 1f
            }
            
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
