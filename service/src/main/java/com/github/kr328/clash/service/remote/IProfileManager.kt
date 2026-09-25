package com.github.kr328.clash.service.remote

import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.model.RulePosition
import com.github.kr328.clash.service.model.RuleOverrideItem
import com.github.kr328.clash.service.model.RuleProviderBehavior
import com.github.kr328.clash.service.model.RuleProviderFormat
import com.github.kr328.clash.service.model.RuleProviderItem
import com.github.kr328.clash.service.model.RuleProviderType
import com.github.kr328.clash.service.model.RuleProviderUpdateInterval
import com.github.kr328.clash.service.model.RuleType
import com.github.kr328.kaidl.BinderInterface
import java.util.*

@BinderInterface
interface IProfileManager {
    suspend fun create(type: Profile.Type, name: String, source: String = "", ageSecretKey: String? = null): UUID
    suspend fun clone(uuid: UUID): UUID
    suspend fun commit(uuid: UUID, callback: IFetchObserver? = null)
    suspend fun release(uuid: UUID)
    suspend fun delete(uuid: UUID)
    suspend fun patch(uuid: UUID, name: String, source: String, interval: Long, ageSecretKey: String?)
    suspend fun update(uuid: UUID)
    suspend fun queryByUUID(uuid: UUID): Profile?
    suspend fun queryAll(): List<Profile>
    suspend fun queryActive(): Profile?
    suspend fun setActive(profile: Profile)

    suspend fun queryRuleOverrides(uuid: UUID): List<RuleOverrideItem>
    suspend fun addRuleOverride(
        uuid: UUID,
        ruleType: RuleType,
        content: String,
        policy: String,
        position: RulePosition,
    ): RuleOverrideItem

    suspend fun updateRuleOverride(item: RuleOverrideItem)
    suspend fun deleteRuleOverride(id: UUID)
    suspend fun restoreRuleOverride(item: RuleOverrideItem)

    suspend fun queryRuleProviders(uuid: UUID): List<RuleProviderItem>
    suspend fun addRuleProvider(
        uuid: UUID,
        name: String,
        type: RuleProviderType,
        behavior: RuleProviderBehavior,
        format: RuleProviderFormat,
        url: String,
        updateInterval: RuleProviderUpdateInterval,
    ): RuleProviderItem

    suspend fun updateRuleProvider(item: RuleProviderItem)

    /**
     * @param force false 时若仍被 RULE-SET 规则引用则抛出 [com.github.kr328.clash.service.override.RuleProviderReferencedException]；
     * true 时先清空全部引用再删除（对应「清空引用并删除」按钮，裁定一：不提供跳过引用检查的直接删除路径）。
     */
    suspend fun deleteRuleProvider(id: UUID, force: Boolean)
}
