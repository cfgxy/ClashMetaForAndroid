package com.github.kr328.clash

import androidx.activity.result.contract.ActivityResultContracts
import android.provider.DocumentsContract
import com.github.kr328.clash.common.util.uuid
import com.github.kr328.clash.design.BundleExportPreview
import com.github.kr328.clash.design.RuleOverridesDesign
import com.github.kr328.clash.design.confirmBundleExport
import com.github.kr328.clash.design.confirmBundleImport
import com.github.kr328.clash.design.describeBundleRejection
import com.github.kr328.clash.design.requestPolicyMapping
import com.github.kr328.clash.design.requestProviderDecision
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.service.bundle.BundleCodec
import com.github.kr328.clash.service.bundle.BundleFormat
import com.github.kr328.clash.service.bundle.BundleImportException
import com.github.kr328.clash.service.bundle.ProviderDecision
import com.github.kr328.clash.service.bundle.buildImportPlan
import com.github.kr328.clash.service.bundle.collectProxyPolicies
import com.github.kr328.clash.service.bundle.defaultPolicyMapping
import com.github.kr328.clash.service.bundle.exportedUrlHosts
import com.github.kr328.clash.service.bundle.referencesProvider
import com.github.kr328.clash.service.bundle.toBundleProviders
import com.github.kr328.clash.service.bundle.toRuleSequence
import com.github.kr328.clash.service.model.CustomRule
import com.github.kr328.clash.service.model.CustomRuleProvider
import com.github.kr328.clash.service.model.RuleImportRequest
import com.github.kr328.clash.service.model.toRuleLine
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import com.github.kr328.clash.design.R

class RuleOverridesActivity : BaseActivity<RuleOverridesDesign>() {
    private val uuid: UUID
        get() = intent.uuid ?: throw IllegalArgumentException("missing profile uuid")

    override suspend fun main() {
        val design = RuleOverridesDesign(this)

        setContentDesign(design)

        design.fetch()

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart, Event.ProfileChanged -> design.fetch()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        RuleOverridesDesign.Request.Add -> {
                            val rule = design.showRuleEditor(
                                null,
                                fetchPolicyCandidates(),
                                fetchRuleSetCandidates(),
                            )

                            if (rule != null) {
                                // 服务层会做 UI 无法完成的语义校验（RULE-SET 引用存在性等），
                                // 保存失败必须在此收口：withProfile 只处理 DeadObjectException，
                                // 再往上是 BaseActivity 的裸 launch，漏出去就是未捕获异常。
                                design.saving {
                                    withProfile {
                                        addRuleOverride(uuid, rule.ruleType, rule.content, rule.policy, rule.position)
                                    }
                                    withProfile { update(uuid) }
                                }

                                design.fetch()
                            }
                        }
                        is RuleOverridesDesign.Request.Edit -> {
                            val rule = design.showRuleEditor(
                                it.item,
                                fetchPolicyCandidates(),
                                fetchRuleSetCandidates(),
                            )

                            if (rule != null) {
                                design.saving {
                                    withProfile {
                                        updateRuleOverride(
                                            it.item.copy(
                                                ruleType = rule.ruleType,
                                                content = rule.content,
                                                policy = rule.policy,
                                                position = rule.position,
                                            )
                                        )
                                    }
                                    withProfile { update(uuid) }
                                }

                                design.fetch()
                            }
                        }
                        is RuleOverridesDesign.Request.Delete -> {
                            val deleted = it.item

                            withProfile { deleteRuleOverride(deleted.id) }
                            design.fetch()

                            if (design.showUndoableDeleteToast()) {
                                // 撤销：按原 id、原 sortOrder 精确还原（RuleOverridesActivity 与
                                // ProfileManager.restoreRuleOverride），不走 addRuleOverride 的
                                // 「追加到组末尾」语义，避免撤销后规则顺序被改变（Review 阻断项 B1）。
                                // 因为删除时未触发配置重应用，撤销后内核配置与删除前完全一致，
                                // 无需重复调用 update(uuid)——避免连续两次真实下载订阅。
                                // restoreRuleOverride 同样会做 RULE-SET 引用校验：撤销窗口内
                                // 规则集可能已被删除，此时还原失败要提示而不是崩溃。
                                design.saving { withProfile { restoreRuleOverride(deleted) } }
                                design.fetch()
                            } else {
                                // 未撤销才是本次真正生效的变更，此时才触发唯一一次配置重应用。
                                withProfile { update(uuid) }
                            }
                        }
                        RuleOverridesDesign.Request.Export -> exportBundle(design)
                        RuleOverridesDesign.Request.Import -> importBundle(design)
                    }
                }
            }
        }
    }

    /**
     * 统一的保存失败收口：把服务层异常转成可读 Snackbar，避免异常穿透到
     * BaseActivity.onCreate 的 launch { main() } 导致进程崩溃。
     */
    private suspend fun RuleOverridesDesign.saving(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            showExceptionToast(getString(R.string.rule_save_failed, e.message ?: ""))
        }
    }

    /**
     * 导出：把本机「我们自己定义的那一层」（Room 表 `rule_override` / `rule_provider`）打成规则包。
     * 订阅自带的规则不在这两张表里，天然不会被打包。
     */
    private suspend fun exportBundle(design: RuleOverridesDesign) {
        val rules = withProfile { queryRuleOverrides(uuid) }
            .map { CustomRule(it.ruleType, it.content, it.policy, it.position) }
        val providers = withProfile { queryRuleProviders(uuid) }
            .map {
                CustomRuleProvider(it.name, it.type, it.behavior, it.format, it.url, it.updateInterval)
            }

        if (rules.isEmpty() && providers.isEmpty()) {
            design.showToast(R.string.rule_bundle_export_empty, ToastDuration.Long)
            return
        }

        val sequence = toRuleSequence(rules)
        val bundleProviders = toBundleProviders(providers)

        val confirmed = design.confirmBundleExport(
            BundleExportPreview(rules.size, providers.size, exportedUrlHosts(bundleProviders))
        )

        if (!confirmed) return

        val output = startActivityForResult(
            ActivityResultContracts.CreateDocument("application/zip"),
            getString(R.string.rule_bundle_export_filename),
        ) ?: return

        try {
            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(output)?.use {
                    BundleCodec.write(it, sequence, bundleProviders, appVersionName())
                } ?: throw IOException("cannot open output stream")
            }

            design.showToast(
                getString(R.string.rule_bundle_export_done, rules.size, providers.size),
                ToastDuration.Long,
            )
        } catch (e: Exception) {
            // SAF 已建出目标文件：失败时尽力删除残包，避免留下打不开的 zip 让用户误以为导出成功。
            // 异常 message 来自 SAF/IO，不可控，同样不回显。
            runCatching { DocumentsContract.deleteDocument(contentResolver, output) }
            design.showToast(R.string.rule_bundle_export_failed_plain, ToastDuration.Long)
        }
    }

    /**
     * 导入：读包 → 全量校验 → 用户决策（同名规则集、线路名映射）→ 预检确认 → 单事务落库。
     * 任何一步取消或拒绝都不写入任何内容；包内容只被解析成领域对象，不进入任何执行路径。
     */
    private suspend fun importBundle(design: RuleOverridesDesign) {
        val input = startActivityForResult(
            ActivityResultContracts.OpenDocument(),
            arrayOf("application/zip"),
        ) ?: return

        val read = try {
            withContext(Dispatchers.IO) {
                contentResolver.openInputStream(input)?.use { BundleCodec.read(it) }
                    ?: throw IOException("cannot open input stream")
            }
        } catch (e: BundleImportException) {
            // 拒绝原因按类型取本地化文案，不回显异常 message、条目名或完整 URL。
            design.showToast(describeBundleRejection(e.rejection), ToastDuration.Long)
            return
        } catch (e: Exception) {
            design.showToast(R.string.rule_bundle_import_failed_plain, ToastDuration.Long)
            return
        }

        val bundle = read.bundle

        val existingProviderNames = withProfile { queryRuleProviders(uuid) }.map { it.name }.toSet()
        val existingRuleLines = withProfile { queryRuleOverrides(uuid) }
            .mapNotNull {
                runCatching {
                    CustomRule(it.ruleType, it.content, it.policy, it.position).toRuleLine()
                }.getOrNull()
            }
            .toSet()

        // 同名规则集逐个征询处置；任一处取消即放弃整次导入。
        val decisions = LinkedHashMap<String, ProviderDecision>()
        // 改名占用名单 = 本机既有 ∪ 包内全部规则集名 ∪ 本次已改的新名：
        // 改名撞上「包内即将新增」的名字会让先写入的那份被静默覆盖，必须一并挡住。
        val takenNames = HashSet(existingProviderNames)
        bundle.providers.forEach { takenNames.add(it.name) }

        for (provider in bundle.providers.filter { it.name in existingProviderNames }) {
            val referencing = (bundle.sequence.prepend + bundle.sequence.append)
                .filter { referencesProvider(it, provider.name) }

            val decision = design.requestProviderDecision(provider.name, referencing, takenNames)
                ?: return

            decisions[provider.name] = decision

            if (decision is ProviderDecision.Rename) {
                takenNames.add(decision.newName)
            }
        }

        // 候选 = 内置策略 + 当前 profile 实际可用组名；同名预选，全部预选成功时不打扰用户。
        val bundlePolicies = collectProxyPolicies(bundle.sequence)
        val groupNames = fetchPolicyCandidates()
        val candidates = BundleFormat.BUILTIN_POLICIES + groupNames
        val preselected = defaultPolicyMapping(bundlePolicies, groupNames)

        val mapping = if (bundlePolicies.all { it in preselected }) {
            preselected
        } else {
            design.requestPolicyMapping(bundlePolicies, candidates.distinct(), preselected) ?: return
        }

        val plan = buildImportPlan(bundle, existingProviderNames, existingRuleLines, decisions, mapping)

        if (plan.providers.isEmpty() && plan.rules.isEmpty()) {
            design.showToast(R.string.rule_bundle_import_nothing, ToastDuration.Long)
            return
        }

        if (!design.confirmBundleImport(plan, read.producedByNewerMinor)) return

        try {
            // requireComplete() 是写入前的最后一道断言：存在未映射线路名时直接抛出，
            // 宁可整次失败也不落一条线路名不确定的规则。
            plan.requireComplete()

            val result = withProfile {
                importRules(
                    uuid,
                    RuleImportRequest(
                        providers = plan.providers.map { it.target },
                        rules = plan.rules.map { it.rule },
                    ),
                )
            }

            withProfile { update(uuid) }

            design.showToast(
                getString(
                    R.string.rule_bundle_import_done,
                    result.rulesInserted,
                    result.providersInserted + result.providersOverwritten,
                ),
                ToastDuration.Long,
            )
        } catch (e: Exception) {
            // 写入侧的异常 message 可能携带包内内容（如事务兜底的「规则集不存在：x」），
            // 与读包拒绝同口径：只给固定文案，不回显。
            design.showToast(R.string.rule_bundle_import_failed_plain, ToastDuration.Long)
        }

        design.fetch()
    }

    private fun appVersionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull() ?: "unknown"

    private suspend fun fetchRuleSetCandidates(): List<String> {
        return try {
            withProfile { queryRuleProviders(uuid).map { it.name } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchPolicyCandidates(): List<String> {
        return try {
            withClash { queryProxyGroupNames(false) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun RuleOverridesDesign.fetch() {
        withProfile {
            patchItems(queryRuleOverrides(uuid))
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

            // Toast 是一次性的，失效规则的标记需要能在 Toast 消失后仍留在列表里
            // （Review 阻断项 B3）：这里刷新列表，让 queryRuleOverrides 从
            // RuleOverrideFailureTracker 取到的最新失效状态渲染为顶部 banner 和条目徽标。
            design?.fetch()
        }
    }
}
