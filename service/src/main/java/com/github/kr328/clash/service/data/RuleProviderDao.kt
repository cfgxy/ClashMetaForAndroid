package com.github.kr328.clash.service.data

import androidx.room.*
import java.util.*

@Dao
@TypeConverters(Converters::class)
interface RuleProviderDao {
    @Query("SELECT * FROM rule_provider WHERE profileUuid = :uuid ORDER BY sortOrder")
    suspend fun queryByProfile(uuid: UUID): List<RuleProvider>

    @Query("SELECT * FROM rule_provider WHERE id = :id")
    suspend fun queryById(id: UUID): RuleProvider?

    @Query("SELECT * FROM rule_provider WHERE profileUuid = :uuid AND name = :name")
    suspend fun queryByName(uuid: UUID, name: String): RuleProvider?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(provider: RuleProvider)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(provider: RuleProvider)

    @Query("DELETE FROM rule_provider WHERE id = :id")
    suspend fun remove(id: UUID)

    @Query("DELETE FROM rule_provider WHERE profileUuid = :uuid")
    suspend fun removeByProfile(uuid: UUID)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM rule_provider WHERE profileUuid = :uuid")
    suspend fun queryMaxSortOrder(uuid: UUID): Long

    /**
     * 裁定一的引用检查口径：某规则集是否被同一 profile 内至少一条 RULE-SET 规则引用。
     */
    @Query("SELECT COUNT(*) FROM rule_override WHERE profileUuid = :uuid AND ruleType = 'RULE-SET' AND content = :name")
    suspend fun countReferences(uuid: UUID, name: String): Int

    /** 一键清空引用：删除该 profile 内所有引用该规则集名称的 RULE-SET 规则。 */
    @Query("DELETE FROM rule_override WHERE profileUuid = :uuid AND ruleType = 'RULE-SET' AND content = :name")
    suspend fun clearReferences(uuid: UUID, name: String)
}
