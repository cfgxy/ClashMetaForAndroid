package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.databinding.AdapterRuleOverrideBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.service.model.RuleOverrideItem

class RuleOverrideAdapter(
    private val context: Context,
    private val onClicked: (RuleOverrideItem) -> Unit,
    private val onMenuClicked: (RuleOverrideItem) -> Unit,
) : RecyclerView.Adapter<RuleOverrideAdapter.Holder>() {
    class Holder(val binding: AdapterRuleOverrideBinding) : RecyclerView.ViewHolder(binding.root)

    var items: List<RuleOverrideItem> = emptyList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterRuleOverrideBinding
                .inflate(context.layoutInflater, parent, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val current = items[position]
        val binding = holder.binding

        if (current === binding.item)
            return

        binding.item = current
        binding.setClicked {
            onClicked(current)
        }
        binding.setMenu {
            onMenuClicked(current)
        }
    }

    override fun getItemCount(): Int {
        return items.size
    }
}
