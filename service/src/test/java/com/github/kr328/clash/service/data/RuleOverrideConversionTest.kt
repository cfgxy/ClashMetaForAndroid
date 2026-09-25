package com.github.kr328.clash.service.data

import com.github.kr328.clash.service.model.RulePosition
import com.github.kr328.clash.service.model.RuleOverrideItem
import com.github.kr328.clash.service.model.RuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.UUID

/**
 * B1（Review 阻断项）：删除中间条目后撤销，必须以原 id、原 sortOrder 落回数据库，
 * 而不是走 addRuleOverride 的「追加到组末尾」语义——否则撤销会静默改变分流优先级。
 * 这里验证 RuleOverrideItem -> RuleOverride 的还原路径本身保真：id、position、sortOrder
 * 全部原样往返，且 applyFailed 这个非持久化列不会被错误地写进实体。
 */
class RuleOverrideConversionTest {
    @Test
    fun `restoring a deleted middle item preserves its original id and sortOrder`() {
        val profileUuid = UUID.randomUUID()
        val originalId = UUID.randomUUID()

        // 模拟一个已存在三条记录的分组（position=APPEND），中间那条（sortOrder=1）被删除后
        // 又以撤销操作还原——还原前读到的 item 就是删除前的原始快照。
        val deletedMiddleItem = RuleOverrideItem(
            id = originalId,
            profileUuid = profileUuid,
            position = RulePosition.APPEND,
            ruleType = RuleType.DOMAIN,
            content = "example.com",
            policy = "DIRECT",
            sortOrder = 1L,
            applyFailed = false,
        )

        val restored = deletedMiddleItem.toEntity()

        assertEquals(originalId, restored.id)
        assertEquals(1L, restored.sortOrder)
        assertEquals(RulePosition.APPEND, restored.position)
        assertEquals(profileUuid, restored.profileUuid)
    }

    @Test
    fun `restored entity sorts back into its original position among siblings`() {
        val profileUuid = UUID.randomUUID()

        val first = RuleOverrideItem(
            UUID.randomUUID(), profileUuid, RulePosition.APPEND, RuleType.DOMAIN,
            "a.com", "DIRECT", sortOrder = 0L,
        )
        val deletedMiddle = RuleOverrideItem(
            UUID.randomUUID(), profileUuid, RulePosition.APPEND, RuleType.DOMAIN,
            "b.com", "DIRECT", sortOrder = 1L,
        )
        val third = RuleOverrideItem(
            UUID.randomUUID(), profileUuid, RulePosition.APPEND, RuleType.DOMAIN,
            "c.com", "DIRECT", sortOrder = 2L,
        )

        // 撤销删除：把 deletedMiddle 还原为实体，与剩余两条一起按 sortOrder 排序，
        // 验证它落回中间位置，而不是被追加到末尾（sortOrder=3 那种错误语义）。
        val remaining = listOf(first.toEntity(), third.toEntity())
        val afterUndo = (remaining + deletedMiddle.toEntity()).sortedBy { it.sortOrder }

        assertEquals(listOf("a.com", "b.com", "c.com"), afterUndo.map { it.content })
    }

    @Test
    fun `toEntity does not persist the transient applyFailed flag`() {
        val item = RuleOverrideItem(
            UUID.randomUUID(), UUID.randomUUID(), RulePosition.PREPEND, RuleType.GEOIP,
            "CN", "REJECT", sortOrder = 0L, applyFailed = true,
        )

        // RuleOverride 实体压根没有 applyFailed 列——toEntity() 编译期已保证不会带出这个非持久化字段，
        // 这里通过往返转换确认 applyFailed 信息只存在于内存态 Tracker，不会污染持久化实体。
        val roundTripped = item.toEntity().toItem(applyFailed = false)

        assertEquals(item.copy(applyFailed = false), roundTripped)
        assertFalse(roundTripped.applyFailed)
    }
}
