package com.github.kr328.clash.service.override

import com.github.kr328.clash.service.data.RuleOverride
import com.github.kr328.clash.service.model.RulePosition
import java.util.UUID

/**
 * 记录每个 profile 当前有哪些自定义规则「上一次配置应用失败」（Review 阻断项 B3）。
 * 应用失败时 [RuleOverrideApplier.applyToFile] 回滚 config.yaml、不落地任何变更，规则仍留在
 * 数据库里但从未真正生效；仅靠一次性 Toast 回调无法让用户在后续打开列表时分辨哪条未生效，
 * 需要一个比单次回调更持久的承载——这里用进程内存态承载（服务进程常驻，不依赖 UI 生命周期），
 * 按 profile 记录「上一次成功应用时的规则指纹」，下次失败时把与该指纹不一致（新增/被改动）的
 * 规则标记为失败；未改动、此前已生效的规则不受影响，避免把整个 profile 的旧规则一并错误标红。
 */
object RuleOverrideFailureTracker {
    private data class Fingerprint(
        val ruleType: String,
        val content: String,
        val policy: String,
        val position: RulePosition,
    )

    private fun RuleOverride.fingerprint() = Fingerprint(ruleType, content, policy, position)

    private val lastGoodByProfile = mutableMapOf<UUID, Map<UUID, Fingerprint>>()
    private val failedByProfile = mutableMapOf<UUID, Set<UUID>>()

    @Synchronized
    fun onApplySucceeded(profileUuid: UUID, overrides: List<RuleOverride>) {
        lastGoodByProfile[profileUuid] = overrides.associate { it.id to it.fingerprint() }
        failedByProfile[profileUuid] = emptySet()
    }

    @Synchronized
    fun onApplyFailed(profileUuid: UUID, overrides: List<RuleOverride>) {
        val lastGood = lastGoodByProfile[profileUuid].orEmpty()
        failedByProfile[profileUuid] = overrides
            .filter { lastGood[it.id] != it.fingerprint() }
            .map { it.id }
            .toSet()
    }

    @Synchronized
    fun queryFailedIds(profileUuid: UUID): Set<UUID> = failedByProfile[profileUuid].orEmpty()

    @Synchronized
    fun onProfileRemoved(profileUuid: UUID) {
        lastGoodByProfile.remove(profileUuid)
        failedByProfile.remove(profileUuid)
    }
}
