package com.blefinder.app

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.blefinder.app.databinding.ItemDeviceBinding

class DeviceListAdapter(
    private val onClick: (MainActivity.DeviceEntry) -> Unit,
    private val onRename: (MainActivity.DeviceEntry) -> Unit
) : RecyclerView.Adapter<DeviceListAdapter.VH>() {

    private val items = mutableListOf<MainActivity.DeviceEntry>()

    fun submitList(list: List<MainActivity.DeviceEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val binding: ItemDeviceBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        val b = holder.binding
        val ctx = b.root.context

        b.deviceName.text = entry.displayName ?: ctx.getString(R.string.unknown_device)
        b.deviceMac.text = entry.mac
        b.tagClassic.isVisible = entry.classic
        b.deviceRssi.text = ctx.getString(R.string.rssi_value, entry.rssi)

        val colorRes = when {
            entry.rssi >= -55 -> R.color.signal_green
            entry.rssi >= -70 -> R.color.signal_yellow
            entry.rssi >= -82 -> R.color.signal_orange
            else -> R.color.signal_red
        }
        val color = ContextCompat.getColor(ctx, colorRes)
        b.deviceRssi.setTextColor(color)
        b.deviceBar.progressTintList = ColorStateList.valueOf(color)
        b.deviceBar.progress = ((entry.rssi + 100) * 100 / 60).coerceIn(0, 100)

        b.root.setOnClickListener { onClick(entry) }
        b.root.setOnLongClickListener {
            onRename(entry)
            true
        }
        b.btnRename.setOnClickListener { onRename(entry) }
    }
}
