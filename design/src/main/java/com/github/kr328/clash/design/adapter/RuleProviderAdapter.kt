package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.AdapterRuleProviderBinding
import com.github.kr328.clash.design.model.RuleProviderState
import com.github.kr328.clash.design.util.elapsedIntervalString
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.service.model.RuleProviderBehavior
import com.github.kr328.clash.service.model.RuleProviderItem
import com.github.kr328.clash.service.model.RuleProviderType
import com.github.kr328.clash.service.model.RuleProviderUpdateInterval

class RuleProviderAdapter(
    private val context: Context,
    private val onClicked: (RuleProviderItem) -> Unit,
    private val onRefreshClicked: (RuleProviderItem) -> Unit,
    private val onMenuClicked: (RuleProviderItem) -> Unit,
) : RecyclerView.Adapter<RuleProviderAdapter.Holder>() {
    class Holder(val binding: AdapterRuleProviderBinding) : RecyclerView.ViewHolder(binding.root)

    var states: List<RuleProviderState> = emptyList()

    fun stateOf(id: java.util.UUID): RuleProviderState? = states.find { it.item.id == id }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(AdapterRuleProviderBinding.inflate(context.layoutInflater, parent, false))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val state = states[position]
        val binding = holder.binding

        binding.state = state
        binding.typeBadge = typeBadge(state.item)
        binding.behaviorBadge = behaviorBadge(state.item.behavior)
        binding.meta = meta(state)
        binding.setClicked { onClicked(state.item) }
        binding.setRefresh { onRefreshClicked(state.item) }
        binding.setMenu { onMenuClicked(state.item) }
    }

    override fun getItemCount(): Int = states.size

    /** 形如「HTTP · DOMAIN」（设计板 F1 的第一枚徽标）。 */
    private fun typeBadge(item: RuleProviderItem): String =
        "${item.type.literal.uppercase()} · ${item.behavior.literal.uppercase()}"

    /** 第二枚徽标是 behavior 的中文短名，避免英文字面量单独出现时不可读。 */
    private fun behaviorBadge(behavior: RuleProviderBehavior): String = context.getString(
        when (behavior) {
            RuleProviderBehavior.DOMAIN -> R.string.rule_provider_behavior_domain_short
            RuleProviderBehavior.IPCIDR -> R.string.rule_provider_behavior_ipcidr_short
            RuleProviderBehavior.CLASSICAL -> R.string.rule_provider_behavior_classical_short
        }
    )

    /**
     * 元信息行。设计板 F1 的示例里含「12,842 条」规则条数，但条数只有内核解析规则集文件后
     * 才知道，当前 IClashManager 未暴露该字段；此处不用本地估算数字冒充内核事实，
     * 只呈现有确定来源的信息：来源类型、更新间隔、以及内核报告的最后更新时间。
     */
    private fun meta(state: RuleProviderState): String {
        val parts = mutableListOf<String>()

        if (state.item.type == RuleProviderType.FILE) {
            parts += context.getString(R.string.rule_provider_meta_file)
            parts += context.getString(R.string.rule_provider_meta_no_auto_update)
        } else {
            parts += intervalLabel(state.item.updateInterval)
        }

        parts += if (state.updatedAt > 0L) {
            context.getString(
                R.string.rule_provider_meta_updated,
                (System.currentTimeMillis() - state.updatedAt).elapsedIntervalString(context)
            )
        } else {
            context.getString(R.string.rule_provider_meta_never_updated)
        }

        return parts.joinToString(" · ")
    }

    private fun intervalLabel(interval: RuleProviderUpdateInterval): String = context.getString(
        when (interval) {
            RuleProviderUpdateInterval.NEVER -> R.string.rule_provider_meta_no_auto_update
            RuleProviderUpdateInterval.HOURLY -> R.string.rule_provider_interval_hourly
            RuleProviderUpdateInterval.EVERY_6_HOURS -> R.string.rule_provider_interval_6h
            RuleProviderUpdateInterval.EVERY_12_HOURS -> R.string.rule_provider_interval_12h
            RuleProviderUpdateInterval.DAILY -> R.string.rule_provider_interval_daily
        }
    )
}
