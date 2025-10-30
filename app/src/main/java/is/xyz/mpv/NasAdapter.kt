package `is`.xyz.mpv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import `is`.xyz.mpv.databinding.ItemNasFileBinding
import jcifs.smb.SmbFile

data class NasItem(val name: String, val isDirectory: Boolean, val smbFile: SmbFile)

class NasAdapter(
    private val items: MutableList<NasItem>,
    private val onItemClick: (NasItem) -> Unit
) : RecyclerView.Adapter<NasAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemNasFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    fun updateData(newItems: List<NasItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    inner class ViewHolder(private val binding: ItemNasFileBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: NasItem) {
            binding.fileNameTextView.text = item.name
            binding.fileTypeImageView.setImageResource(
                if (item.isDirectory) R.drawable.ic_folder_24dp
                else R.drawable.ic_file_24dp
            )
            binding.root.setOnClickListener { onItemClick(item) }
        }
    }
}
