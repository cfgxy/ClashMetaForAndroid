package com.github.kr328.clash.service.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.TypeConverters
import com.github.kr328.clash.service.model.RulePosition
import com.github.kr328.clash.service.model.RuleOverrideItem
import com.github.kr328.clash.service.model.RuleType
import com.github.kr328.clash.service.override.RuleOverrideException
import java.util.*

/**
 * 按 profile 绑定的一条自定义分流规则的持久化数据。
 * 不复用 OverrideSlot（Clash.kt 中 Persist/Session 两档，走 JSON 结构化字段通道，
 * 承载不了规则列表），单独建表存储，profile 删除时级联清理（见 ProfileProcessor.delete）。
 */
@Entity(
    tableName = "rule_override",
    primaryKeys = ["id"],
    indices = [Index(value = ["profileUuid"])],
)
@TypeConverters(Converters::class)
data class RuleOverride(
    @ColumnInfo(name = "id") val id: UUID,
    @ColumnInfo(name = "profileUuid") val profileUuid: UUID,
    @ColumnInfo(name = "position") val position: RulePosition,
    @ColumnInfo(name = "ruleType") val ruleType: String,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "policy") val policy: String,
    // 同一 position 分组内按 sortOrder 升序排列；新增一律追加到所属分组末尾，
    // 编辑不改变位置（Review 结论 01a0d428 REVIEW NOTE：顺序语义须确定且可预期）。
    @ColumnInfo(name = "sortOrder") val sortOrder: Long,
) {
    fun toItem(applyFailed: Boolean = false): RuleOverrideItem = RuleOverrideItem(
        id = id,
        profileUuid = profileUuid,
        position = position,
        ruleType = RuleType.fromLiteral(ruleType) ?: throw RuleOverrideException("未知规则类型：$ruleType"),
        content = content,
        policy = policy,
        sortOrder = sortOrder,
        applyFailed = applyFailed,
    )
}

/**
 * 按原 id、原 sortOrder 还原为持久化实体（Review 阻断项 B1：撤销删除需要精确复原被删条目
 * 在其所属 position 分组内的顺序，不能走 [com.github.kr328.clash.service.ProfileManager.addRuleOverride]
 * 的「追加到组末尾」语义，否则撤销会静默改变分流优先级）。
 */
fun RuleOverrideItem.toEntity(): RuleOverride = RuleOverride(
    id = id,
    profileUuid = profileUuid,
    position = position,
    ruleType = ruleType.literal,
    content = content,
    policy = policy,
    sortOrder = sortOrder,
)
