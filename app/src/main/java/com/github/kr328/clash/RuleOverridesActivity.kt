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
                            withProfile { deleteRuleOverride(it.item.id) }
                            withProfile { update(uuid) }
                            design.fetch()

                            if (design.showUndoableDeleteToast()) {
                                withProfile {
                                    addRuleOverride(
                                        uuid,
                                        it.item.ruleType,
                                        it.item.content,
                                        it.item.policy,
                                        it.item.position,
                                    )
                                }
                                withProfile { update(uuid) }
                                design.fetch()
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
        }
    }
}
