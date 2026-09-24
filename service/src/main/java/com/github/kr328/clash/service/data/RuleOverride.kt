package com.github.kr328.clash.service.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.TypeConverters
import com.github.kr328.clash.service.model.RulePosition
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
)
