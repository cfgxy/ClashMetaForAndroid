package com.github.kr328.clash

import com.github.kr328.clash.common.util.uuid
import com.github.kr328.clash.core.model.Provider
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.RuleProvidersDesign
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.service.model.RuleProviderItem
import com.github.kr328.clash.service.model.RuleType
import com.github.kr328.clash.service.model.maskUrl
import com.github.kr328.clash.service.override.RuleProviderReferencedException
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.util.UUID

class RuleProvidersActivity : BaseActivity<RuleProvidersDesign>() {
    private val uuid: UUID
        get() = intent.uuid ?: throw IllegalArgumentException("missing profile uuid")

    override suspend fun main() {
        val design = RuleProvidersDesign(this)

        setContentDesign(design)

        design.fetch()

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart, Event.ProfileChanged, Event.ProfileLoaded ->
                            design.fetch()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        RuleProvidersDesign.Request.Add -> design.handleAdd()
                        RuleProvidersDesign.Request.UpdateAll -> design.handleUpdateAll()
                        is RuleProvidersDesign.Request.Edit -> design.handleEdit(it.item)
                        is RuleProvidersDesign.Request.Delete -> design.handleDelete(it.item)
                        is RuleProvidersDesign.Request.Update -> design.handleUpdateSingle(it.item)
                    }
                }
            }
        }
    }

    // region 新增 / 编辑

    private suspend fun RuleProvidersDesign.handleAdd() {
        val existing = queryItems()
        val provider = showEditor(null, existing.map { it.name }.toSet()) ?: return

        // 服务层会做 UI 无法完成的语义校验（名称唯一、INLINE 拒绝、URL scheme），
        // 保存失败必须在此收口：withProfile 只处理 DeadObjectException，再往上是
        // BaseActivity 的裸 launch，漏出去就是未捕获异常。
        saving {
            withProfile {
                addRuleProvider(
                    uuid,
                    provider.name,
                    provider.type,
                    provider.behavior,
                    provider.format,
                    provider.url,
                    provider.updateInterval,
                )
            }
            withProfile { update(uuid) }
        }

        fetch()
    }

    private suspend fun RuleProvidersDesign.handleEdit(item: RuleProviderItem) {
        val existing = queryItems()
        val provider = showEditor(
            item,
            existing.filter { it.id != item.id }.map { it.name }.toSet(),
        ) ?: return

        saving {
            withProfile {
                updateRuleProvider(
                    item.copy(
                        name = provider.name,
                        type = provider.type,
                        behavior = provider.behavior,
                        format = provider.format,
                        url = provider.url,
                        updateInterval = provider.updateInterval,
                    )
                )
            }
            withProfile { update(uuid) }
        }

        fetch()
    }

    // endregion

    // region 删除

    private suspend fun RuleProvidersDesign.handleDelete(item: RuleProviderItem) {
        // referenced 由服务层实时计算，但它只是渲染列表那一刻的快照；真正的引用判定以
        // deleteRuleProvider(force = false) 抛出的 RuleProviderReferencedException 为准，
        // 避免快照与库内状态不一致时误走轻确认分支把引用一并删掉。
        if (!item.referenced) {
            if (!confirmDelete(item.name)) return

            try {
                withProfile { deleteRuleProvider(item.id, force = false) }
                withProfile { update(uuid) }

                showDeletedToast(item.name)
                fetch()

                return
            } catch (e: RuleProviderReferencedException) {
                // 快照过期：落到强提示分支，不静默删除。
            } catch (e: Exception) {
                showExceptionToast(getString(R.string.rule_provider_save_failed, e.message ?: ""))

                return
            }
        }

        val references = queryReferences(item.name)
        val referenceCount = references.size

        if (!confirmDeleteReferenced(item.name, referenceCount, references)) return

        saving {
            withProfile { deleteRuleProvider(item.id, force = true) }
            withProfile { update(uuid) }

            showDeletedWithReferencesToast(referenceCount, item.name)
        }

        fetch()
    }

    /**
     * 引用预览行，形如 `RULE-SET | 广告拦截 → REJECT`。引用关系只在自定义规则表里，
     * 与服务层 countReferences 同一口径（ruleType = RULE-SET 且 content = 规则集名）。
     */
    private suspend fun queryReferences(name: String): List<String> {
        return try {
            withProfile {
                queryRuleOverrides(uuid)
                    .filter { it.ruleType == RuleType.RULE_SET && it.content == name }
                    .map { "${it.ruleType.literal} | ${it.content} → ${it.policy}" }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // endregion

    // region 更新

    private suspend fun RuleProvidersDesign.handleUpdateSingle(item: RuleProviderItem) {
        markUpdating(item.id)

        if (updateOne(item)) {
            fetch()
        } else if (showUpdateFailedToast(item.name, failureReason)) {
            handleUpdateSingle(item)
        }
    }

    /**
     * 更新全部：内核的 updateProvider 是逐个同步调用，这里顺序执行并把尚未轮到的行标为排队，
     * 同时用进度 banner 交代「第 N/总数」——不给一个无进度的不确定等待。
     */
    private suspend fun RuleProvidersDesign.handleUpdateAll() {
        val items = queryItems()

        if (items.isEmpty()) return

        val startedAt = System.currentTimeMillis()

        markQueued(items.map { it.id })
        showUpdateAllProgress(0, items.size)

        var succeeded = 0
        var failed = 0

        items.forEachIndexed { index, item ->
            showUpdateAllProgress(index + 1, items.size)
            markUpdating(item.id)

            if (updateOne(item)) succeeded++ else failed++
        }

        hideUpdateAllProgress()

        fetch()

        when {
            // 内核未运行时全部调用都会失败，这是环境前置条件而不是规则集本身有问题，
            // 单独给一条可执行的提示。
            succeeded == 0 && failed > 0 && !clashRunning() -> showUpdateAllUnavailable()
            // 存在失败项时不弹成功 Snackbar，失败结论由顶部失败 banner 单独承载。
            failed == 0 -> showUpdateAllDone(
                succeeded,
                (System.currentTimeMillis() - startedAt) / 1000,
            )
        }
    }

    /** 最近一次 [updateOne] 失败的脱敏原因，供失败 Snackbar 使用。 */
    private var failureReason: String = ""

    private suspend fun RuleProvidersDesign.updateOne(item: RuleProviderItem): Boolean {
        return try {
            withClash { updateProvider(Provider.Type.Rule, item.name) }

            markUpdated(item.id, System.currentTimeMillis())

            true
        } catch (e: Exception) {
            // 失败文案不得包含完整 URL（可能携带订阅令牌），统一按 scheme+host 脱敏。
            failureReason = (e.message ?: "").replace(item.url, maskUrl(item.url))

            markUpdateFailed(item.id)

            false
        }
    }

    private suspend fun clashRunning(): Boolean {
        return try {
            withClash { queryTunnelState() }

            true
        } catch (e: Exception) {
            false
        }
    }

    // endregion

    private suspend fun queryItems(): List<RuleProviderItem> {
        return try {
            withProfile { queryRuleProviders(uuid) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 内核侧报告的最后更新时间只能从 queryProviders() 取（IClashManager 未提供按名查询），
     * 内核未运行时取不到，此时列表行显示「尚未更新」而不是伪造一个本地时间。
     */
    private suspend fun queryUpdatedAt(): Map<String, Long> {
        return try {
            withClash {
                queryProviders()
                    .filter { it.type == Provider.Type.Rule }
                    .associate { it.name to it.updatedAt }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private suspend fun RuleProvidersDesign.fetch() {
        patchItems(queryItems(), queryUpdatedAt())
    }

    /** 统一的保存失败收口：把服务层异常转成可读 Snackbar，避免异常穿透 BaseActivity。 */
    private suspend fun RuleProvidersDesign.saving(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            showExceptionToast(getString(R.string.rule_provider_save_failed, e.message ?: ""))
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
