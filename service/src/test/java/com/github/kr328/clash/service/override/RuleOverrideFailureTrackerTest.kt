package com.github.kr328.clash.service.override

import com.github.kr328.clash.service.data.RuleOverride
import com.github.kr328.clash.service.model.RulePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * B3（Review 阻断项）：内核拒绝规则、config 回滚后，失败的规则需要能在后续查询中
 * 持续标记为「未通过校验」，而不是只靠一次性 Toast——这里验证 Tracker 的内存态判定逻辑。
 * 是 object 单例，各用例用独立 profileUuid 隔离，避免相互污染。
 */
class RuleOverrideFailureTrackerTest {
    private fun rule(id: UUID, profileUuid: UUID, content: String, policy: String = "DIRECT") = RuleOverride(
        id = id,
        profileUuid = profileUuid,
        position = RulePosition.APPEND,
        ruleType = "DOMAIN",
        content = content,
        policy = policy,
        sortOrder = 0L,
    )

    @Test
    fun `no failures recorded before any apply attempt`() {
        val profileUuid = UUID.randomUUID()
        assertEquals(emptySet<UUID>(), RuleOverrideFailureTracker.queryFailedIds(profileUuid))
    }

    @Test
    fun `apply success clears any previously recorded failure`() {
        val profileUuid = UUID.randomUUID()
        val id = UUID.randomUUID()
        val overrides = listOf(rule(id, profileUuid, "example.com"))

        RuleOverrideFailureTracker.onApplyFailed(profileUuid, overrides)
        assertEquals(setOf(id), RuleOverrideFailureTracker.queryFailedIds(profileUuid))

        RuleOverrideFailureTracker.onApplySucceeded(profileUuid, overrides)
        assertEquals(emptySet<UUID>(), RuleOverrideFailureTracker.queryFailedIds(profileUuid))
    }

    @Test
    fun `apply failure only marks rules that changed since last success`() {
        val profileUuid = UUID.randomUUID()
        val untouchedId = UUID.randomUUID()
        val editedId = UUID.randomUUID()
        val newId = UUID.randomUUID()

        val goodState = listOf(
            rule(untouchedId, profileUuid, "good.com"),
            rule(editedId, profileUuid, "edited.com", policy = "DIRECT"),
        )
        RuleOverrideFailureTracker.onApplySucceeded(profileUuid, goodState)

        // 下一轮：untouchedId 原样未动，editedId 的策略被改坏，newId 是新增的非法规则。
        val nextAttempt = listOf(
            rule(untouchedId, profileUuid, "good.com"),
            rule(editedId, profileUuid, "edited.com", policy = "NotAGroup"),
            rule(newId, profileUuid, "bad.com", policy = "NotAGroup"),
        )
        RuleOverrideFailureTracker.onApplyFailed(profileUuid, nextAttempt)

        val failed = RuleOverrideFailureTracker.queryFailedIds(profileUuid)
        assertEquals(setOf(editedId, newId), failed)
        assertTrue(untouchedId !in failed)
    }

    @Test
    fun `onProfileRemoved clears both success fingerprint and failure state`() {
        val profileUuid = UUID.randomUUID()
        val id = UUID.randomUUID()
        val overrides = listOf(rule(id, profileUuid, "example.com"))

        RuleOverrideFailureTracker.onApplySucceeded(profileUuid, overrides)
        RuleOverrideFailureTracker.onApplyFailed(profileUuid, listOf(rule(id, profileUuid, "changed.com")))
        assertEquals(setOf(id), RuleOverrideFailureTracker.queryFailedIds(profileUuid))

        RuleOverrideFailureTracker.onProfileRemoved(profileUuid)
        assertEquals(emptySet<UUID>(), RuleOverrideFailureTracker.queryFailedIds(profileUuid))

        // 移除后再次失败，不应再残留旧指纹的影响：连「未改动」的规则也会被当作全新状态标记。
        RuleOverrideFailureTracker.onApplyFailed(profileUuid, overrides)
        assertEquals(setOf(id), RuleOverrideFailureTracker.queryFailedIds(profileUuid))
    }
}
