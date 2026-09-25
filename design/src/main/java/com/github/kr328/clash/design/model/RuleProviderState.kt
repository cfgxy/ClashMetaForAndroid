package com.github.kr328.clash.design.model

import androidx.databinding.BaseObservable
import androidx.databinding.Bindable
import com.github.kr328.clash.design.BR
import com.github.kr328.clash.service.model.RuleProviderItem

/**
 * 规则集列表的行状态：持久数据来自 [item]，其余字段是仅存在于本次界面会话的运行时状态
 * （刷新中、上一次刷新失败、排队中、内核报告的最后更新时间），不落库——与 [ProviderState]
 * 同样的分工，同样用 BaseObservable 让「刷新中↔刷新按钮」的切换直接走 databinding，
 * 不需要整行 notifyItemChanged。
 */
class RuleProviderState(
    val item: RuleProviderItem,
    updating: Boolean = false,
    updateFailed: Boolean = false,
    queued: Boolean = false,
    updatedAt: Long = 0L,
) : BaseObservable() {
    var updating: Boolean = updating
        @Bindable get
        set(value) {
            field = value

            notifyPropertyChanged(BR.updating)
        }

    var updateFailed: Boolean = updateFailed
        @Bindable get
        set(value) {
            field = value

            notifyPropertyChanged(BR.updateFailed)
        }

    /** 「更新全部」已排队但尚未轮到的行，按设计板 F4 以 45% 透明度呈现。 */
    var queued: Boolean = queued
        @Bindable get
        set(value) {
            field = value

            notifyPropertyChanged(BR.queued)
        }

    /**
     * 内核侧报告的该规则集最后一次成功加载时间（毫秒）。0 表示内核未运行或未提供，
     * 此时行内不显示「更新于 …」——不以本地时间伪造内核状态。
     */
    var updatedAt: Long = updatedAt
        @Bindable get
        set(value) {
            field = value

            notifyPropertyChanged(BR.updatedAt)
        }
}
