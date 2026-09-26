package com.github.kr328.clash.service.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.TypeConverters
import com.github.kr328.clash.service.model.RuleProviderBehavior
import com.github.kr328.clash.service.model.RuleProviderFormat
import com.github.kr328.clash.service.model.RuleProviderItem
import com.github.kr328.clash.service.model.RuleProviderType
import com.github.kr328.clash.service.model.RuleProviderUpdateInterval
import com.github.kr328.clash.service.override.RuleOverrideException
import java.util.*

/**
 * 按 profile 绑定的一条规则集定义的持久化数据，profile 删除时级联清理
 * （见 ProfileProcessor.delete，与 RuleOverride 表同一清理时机）。
 */
@Entity(
    tableName = "rule_provider",
    primaryKeys = ["id"],
    indices = [Index(value = ["profileUuid"]), Index(value = ["profileUuid", "name"], unique = true)],
)
@TypeConverters(Converters::class)
data class RuleProvider(
    @ColumnInfo(name = "id") val id: UUID,
    @ColumnInfo(name = "profileUuid") val profileUuid: UUID,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "behavior") val behavior: String,
    @ColumnInfo(name = "format") val format: String,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "updateIntervalSeconds") val updateIntervalSeconds: Long?,
    @ColumnInfo(name = "sortOrder") val sortOrder: Long,
) {
    fun toItem(referenced: Boolean = false): RuleProviderItem = RuleProviderItem(
        id = id,
        profileUuid = profileUuid,
        name = name,
        type = RuleProviderType.fromLiteral(type) ?: throw RuleOverrideException("未知规则集类型：$type"),
        behavior = RuleProviderBehavior.fromLiteral(behavior) ?: throw RuleOverrideException("未知规则集匹配语义：$behavior"),
        format = RuleProviderFormat.fromLiteral(format) ?: throw RuleOverrideException("未知规则集格式：$format"),
        url = url,
        updateInterval = RuleProviderUpdateInterval.fromSeconds(updateIntervalSeconds),
        sortOrder = sortOrder,
        referenced = referenced,
    )
}
