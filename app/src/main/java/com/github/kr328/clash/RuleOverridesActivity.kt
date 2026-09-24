package com.github.kr328.clash

import com.github.kr328.clash.common.util.uuid
import com.github.kr328.clash.design.RuleOverridesDesign
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.util.UUID
import com.github.kr328.clash.design.R

class RuleOverridesActivity : BaseActivity<RuleOverridesDesign>() {
    private val uuid: UUID
        get() = intent.uuid ?: throw IllegalArgumentException("missing profile uuid")

    override suspend fun main() {
        val design = RuleOverridesDesign(this)

        setContentDesign(design)

        design.fetch()

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart, Event.ProfileChanged -> design.fetch()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        RuleOverridesDesign.Request.Add -> {
                            val candidates = fetchPolicyCandidates()
                            val rule = design.showRuleEditor(null, candidates)

                            if (rule != null) {
                                withProfile { addRuleOverride(uuid, rule.ruleType, rule.content, rule.policy, rule.position) }
                                withProfile { update(uuid) }
                                design.fetch()
                            }
                        }
                        is RuleOverridesDesign.Request.Edit -> {
                            val candidates = fetchPolicyCandidates()
                            val rule = design.showRuleEditor(it.item, candidates)

                            if (rule != null) {
                                withProfile {
                                    updateRuleOverride(
                                        it.item.copy(
                                            ruleType = rule.ruleType,
                                            content = rule.content,
                                            policy = rule.policy,
                                            position = rule.position,
                                        )
                                    )
                                }
                                withProfile { update(uuid) }
                                design.fetch()
                            }
                        }
                        is RuleOverridesDesign.Request.Delete -> {
                            val deleted = it.item

                            withProfile { deleteRuleOverride(deleted.id) }
                            design.fetch()

                            if (design.showUndoableDeleteToast()) {
                                // 撤销：按原 id、原 sortOrder 精确还原（RuleOverridesActivity 与
                                // ProfileManager.restoreRuleOverride），不走 addRuleOverride 的
                                // 「追加到组末尾」语义，避免撤销后规则顺序被改变（Review 阻断项 B1）。
                                // 因为删除时未触发配置重应用，撤销后内核配置与删除前完全一致，
                                // 无需重复调用 update(uuid)——避免连续两次真实下载订阅。
                                withProfile { restoreRuleOverride(deleted) }
                                design.fetch()
                            } else {
                                // 未撤销才是本次真正生效的变更，此时才触发唯一一次配置重应用。
                                withProfile { update(uuid) }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun fetchPolicyCandidates(): List<String> {
        return try {
            withClash { queryProxyGroupNames(false) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun RuleOverridesDesign.fetch() {
        withProfile {
            patchItems(queryRuleOverrides(uuid))
        }
    }

    override fun onProfileUpdateFailed(uuid: UUID?, reason: String?) {
        if (uuid != this.uuid)
            return

        launch {
            val name = withProfile { queryByUUID(uuid)?.name }

            design?.showToast(
                getString(R.string.toast_profile_updated_failed, name, reason),
                com.github.kr328.clash.design.ui.ToastDuration.Long
            )

            // Toast 是一次性的，失效规则的标记需要能在 Toast 消失后仍留在列表里
            // （Review 阻断项 B3）：这里刷新列表，让 queryRuleOverrides 从
            // RuleOverrideFailureTracker 取到的最新失效状态渲染为顶部 banner 和条目徽标。
            design?.fetch()
        }
    }
}
