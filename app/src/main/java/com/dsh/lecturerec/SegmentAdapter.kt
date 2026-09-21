package com.dsh.lecturerec

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dsh.lecturerec.databinding.ItemSegmentBinding

class SegmentAdapter(
    private var timestampMode: String,
    private val onLongClick: (Segment) -> Unit
) : RecyclerView.Adapter<SegmentAdapter.VH>() {

    private val items = ArrayList<Segment>()

    class VH(val b: ItemSegmentBinding) : RecyclerView.ViewHolder(b.root)

    /** 设置页改完时间戳模式回来后调用。 */
    fun setTimestampMode(mode: String) {
        if (mode == timestampMode) return
        timestampMode = mode
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemSegmentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val seg = items[position]
        val prevStart = if (position > 0) items[position - 1].startMs else null
        val stamped = Timestamps.shouldStamp(timestampMode, seg.startMs, prevStart)
        val state = stateLabel(seg.state)

        val header = buildString {
            if (stamped) append(Timestamps.label(timestampMode, seg.startMs))
            if (state.isNotBlank()) {
                if (isNotEmpty()) append("  ")
                append(state)
            }
        }

        holder.b.tsText.visibility = if (header.isEmpty()) View.GONE else View.VISIBLE
        holder.b.tsText.text = header

        // 没有时间戳的行收紧上边距，让连续的文字读起来是一片而不是一行一行的
        val d = holder.itemView.resources.displayMetrics.density
        val root = holder.b.root
        root.setPadding(
            root.paddingLeft,
            ((if (header.isEmpty()) 2 else 8) * d).toInt(),
            root.paddingRight,
            root.paddingBottom
        )

        holder.b.textView.text = when {
            seg.text.isNotBlank() -> seg.polished ?: seg.text
            seg.state == SegState.FAILED -> "（这段上传失败，可从菜单「重传失败片段」再试）"
            else -> "…"
        }
        holder.b.root.setOnLongClickListener {
            onLongClick(seg)
            true
        }
    }

    private fun stateLabel(s: SegState): String = when (s) {
        SegState.PENDING -> "· 排队"
        SegState.UPLOADING -> "· 转写中"
        SegState.DONE -> ""
        SegState.FAILED -> "· 失败"
    }

    /**
     * 以追加为主、逐项比对，避免整表刷新 —— 两小时的课会有上千条，notifyDataSetChanged 会明显卡顿。
     */
    fun submit(newList: List<Segment>) {
        val samePrefix = newList.size >= items.size &&
            items.indices.all { items[it].id == newList[it].id }

        if (!samePrefix) {
            items.clear()
            items.addAll(newList)
            notifyDataSetChanged()
            return
        }

        for (i in items.indices) {
            if (items[i] != newList[i]) {
                items[i] = newList[i]
                notifyItemChanged(i)
            }
        }
        if (newList.size > items.size) {
            val from = items.size
            items.addAll(newList.subList(from, newList.size))
            notifyItemRangeInserted(from, newList.size - from)
        }
    }
}
