package com.github.kr328.clash.service.data

import androidx.room.*
import com.github.kr328.clash.service.model.RulePosition
import java.util.*

@Dao
@TypeConverters(Converters::class)
interface RuleOverrideDao {
    @Query("SELECT * FROM rule_override WHERE profileUuid = :uuid ORDER BY position, sortOrder")
    suspend fun queryByProfile(uuid: UUID): List<RuleOverride>

    @Query("SELECT * FROM rule_override WHERE id = :id")
    suspend fun queryById(id: UUID): RuleOverride?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(rule: RuleOverride)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(rule: RuleOverride)

    @Query("DELETE FROM rule_override WHERE id = :id")
    suspend fun remove(id: UUID)

    @Query("DELETE FROM rule_override WHERE profileUuid = :uuid")
    suspend fun removeByProfile(uuid: UUID)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM rule_override WHERE profileUuid = :uuid AND position = :position")
    suspend fun queryMaxSortOrder(uuid: UUID, position: RulePosition): Long
}
