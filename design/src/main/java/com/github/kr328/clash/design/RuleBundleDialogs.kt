package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ListPopupWindow
import androidx.core.widget.doOnTextChanged
import com.github.kr328.clash.design.adapter.PopupListAdapter
import com.github.kr328.clash.design.databinding.DialogRuleBundleConflictBinding
import com.github.kr328.clash.design.databinding.DialogRuleBundleMappingBinding
import com.github.kr328.clash.design.databinding.DialogRuleBundleMappingItemBinding
import com.github.kr328.clash.design.dialog.AppBottomSheetDialog
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.service.bundle.BundleRejection
import com.github.kr328.clash.service.bundle.ImportPlan
import com.github.kr328.clash.service.bundle.ProviderDecision
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 规则包导入导出的用户决策界面。做成扩展函数而不是新的 Design 子类：这些对话框依附于
 * 规则管理页，没有自己的页面骨架，也不需要独立的请求通道。
 */

/** 导出前的确认内容。[urlHosts] 只含主机名——完整 URL 可能带订阅令牌，不进界面也不进日志。 */
data class BundleExportPreview(
    val ruleCount: Int,
    val providerCount: Int,
    val urlHosts: List<String>,
)

suspend fun Design<*>.confirmBundleExport(preview: BundleExportPreview): Boolean {
    val message = buildString {
        append(context.getString(R.string.rule_bundle_export_summary, preview.ruleCount, preview.providerCount))
        append("\n\n")
        append(context.getString(R.string.rule_bundle_export_scope))

        if (preview.urlHosts.isNotEmpty()) {
            append("\n\n")
            append(context.getString(R.string.rule_bundle_export_url_warning))
            append("\n")
            append(preview.urlHosts.joinToString("\n") { "· $it" })
        }
    }

    return withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { ctx ->
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.rule_bundle_export_title)
                .setMessage(message)
                .setCancelable(true)
                .setPositiveButton(R.string.export) { _, _ -> if (!ctx.isCompleted) ctx.resume(true) }
                .setNegativeButton(R.string.cancel) { _, _ -> if (!ctx.isCompleted) ctx.resume(false) }
                .setOnDismissListener { if (!ctx.isCompleted) ctx.resume(false) }
                .show()
        }
    }
}

/**
 * 同名规则集的处置选择。
 *
 * @param takenNames 已被占用的名字（本机既有 + 包内全部规则集名 + 本次已决定写入的新名），
 *   用于挡住改名撞上任何「即将存在」的名字。
 * @return null 表示用户取消整次导入。
 */
suspend fun Design<*>.requestProviderDecision(
    name: String,
    referencingLines: List<String>,
    takenNames: Set<String>,
): ProviderDecision? {
    return withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { ctx ->
            val dialog = AppBottomSheetDialog(context)

            val binding = DialogRuleBundleConflictBinding
                .inflate(context.layoutInflater, dialog.window?.decorView as ViewGroup?, false)

            binding.titleView.text = context.getString(R.string.rule_bundle_conflict_title, name)

            // 默认「跳过」：用户未表态时不改写本地既有声明。
            binding.decisionSkipView.isChecked = true

            fun refreshHint() {
                val renaming = binding.decisionRenameView.isChecked

                binding.nameLayout.visibility = if (renaming) View.VISIBLE else View.GONE

                val skipping = binding.decisionSkipView.isChecked

                binding.referencesView.visibility =
                    if (skipping && referencingLines.isNotEmpty()) View.VISIBLE else View.GONE
                binding.referencesView.text = context.getString(
                    R.string.rule_bundle_conflict_skip_references,
                    referencingLines.size,
                    referencingLines.take(3).joinToString("\n") { "· $it" },
                )
            }

            fun validateName(): Boolean {
                if (!binding.decisionRenameView.isChecked) {
                    binding.nameLayout.error = null
                    return true
                }

                val input = binding.nameField.text?.toString().orEmpty()

                binding.nameLayout.error = when {
                    !Regex("^[A-Za-z0-9_-]{1,64}$").matches(input) ->
                        context.getString(R.string.rule_bundle_conflict_rename_invalid)
                    input in takenNames ->
                        context.getString(R.string.rule_bundle_conflict_rename_duplicated)
                    else -> null
                }

                return binding.nameLayout.error == null
            }

            binding.decisionGroup.setOnCheckedChangeListener { _, _ ->
                refreshHint()
                validateName()
            }
            binding.nameField.doOnTextChanged { _, _, _, _ -> validateName() }

            binding.confirmView.setOnClickListener {
                if (!validateName()) return@setOnClickListener

                val decision = when {
                    binding.decisionOverwriteView.isChecked -> ProviderDecision.Overwrite
                    binding.decisionRenameView.isChecked ->
                        ProviderDecision.Rename(binding.nameField.text?.toString().orEmpty())
                    else -> ProviderDecision.Skip
                }

                if (!ctx.isCompleted) ctx.resume(decision)

                dialog.dismiss()
            }

            binding.cancelView.setOnClickListener { dialog.dismiss() }

            // 点击遮罩或返回键同样是「取消整次导入」，不能落成某个默认处置。
            dialog.setOnDismissListener { if (!ctx.isCompleted) ctx.resume(null) }

            refreshHint()

            dialog.setContentView(binding.root)
            dialog.show()
        }
    }
}

/**
 * 线路名映射界面。
 *
 * @param candidates 本地可选目标：内置策略 + 当前 profile 实际可用的策略组名。
 * @param preselected 同名预选结果。
 * @return null 表示用户取消；返回的映射保证覆盖 [policies] 全部条目。
 */
suspend fun Design<*>.requestPolicyMapping(
    policies: List<String>,
    candidates: List<String>,
    preselected: Map<String, String>,
): Map<String, String>? {
    return withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { ctx ->
            val dialog = AppBottomSheetDialog(context)

            val binding = DialogRuleBundleMappingBinding
                .inflate(context.layoutInflater, dialog.window?.decorView as ViewGroup?, false)

            val selected = LinkedHashMap<String, String>()

            val rows = policies.map { policy ->
                val row = DialogRuleBundleMappingItemBinding
                    .inflate(context.layoutInflater, binding.mappingView, false)

                row.targetLayout.hint = policy
                preselected[policy]?.let {
                    selected[policy] = it
                    row.targetField.setText(it)
                }

                binding.mappingView.addView(row.root)

                policy to row
            }

            fun refreshIncomplete() {
                val missing = policies.count { it !in selected }

                binding.incompleteView.visibility = if (missing > 0) View.VISIBLE else View.GONE
                binding.incompleteView.text =
                    context.getString(R.string.rule_bundle_mapping_incomplete, missing)
                binding.confirmView.isEnabled = missing == 0
            }

            for ((policy, row) in rows) {
                val showPopup = {
                    if (candidates.isNotEmpty()) {
                        val popup = ListPopupWindow(context)

                        popup.anchorView = row.targetLayout
                        popup.setAdapter(
                            PopupListAdapter(context, candidates, candidates.indexOf(selected[policy]))
                        )
                        popup.setOnItemClickListener { _, _, position, _ ->
                            selected[policy] = candidates[position]
                            row.targetField.setText(candidates[position])
                            refreshIncomplete()
                            popup.dismiss()
                        }
                        popup.show()
                    }
                }

                row.targetLayout.setEndIconOnClickListener { showPopup() }
                row.targetField.setOnClickListener { showPopup() }
            }

            binding.confirmView.setOnClickListener {
                if (policies.any { it !in selected }) return@setOnClickListener

                if (!ctx.isCompleted) ctx.resume(LinkedHashMap(selected))

                dialog.dismiss()
            }

            binding.cancelView.setOnClickListener { dialog.dismiss() }

            dialog.setOnDismissListener { if (!ctx.isCompleted) ctx.resume(null) }

            refreshIncomplete()

            dialog.setContentView(binding.root)
            dialog.show()
        }
    }
}

/** 写入前的最后一道确认：把「要写什么、会忽略什么」一次说清。 */
suspend fun Design<*>.confirmBundleImport(plan: ImportPlan, producedByNewerMinor: Boolean): Boolean {
    val lines = ArrayList<String>()

    lines.add(context.getString(R.string.rule_bundle_import_preview_rules, plan.rules.size))
    lines.add(context.getString(R.string.rule_bundle_import_preview_providers, plan.providers.size))

    if (plan.skippedProviders.isNotEmpty()) {
        lines.add(
            context.getString(
                R.string.rule_bundle_import_preview_skipped_providers,
                plan.skippedProviders.size,
            )
        )
    }

    if (plan.skippedDuplicateRules.isNotEmpty()) {
        lines.add(
            context.getString(
                R.string.rule_bundle_import_preview_duplicated,
                plan.skippedDuplicateRules.size,
            )
        )
    }

    if (plan.unsupportedRules.isNotEmpty()) {
        lines.add(
            context.getString(
                R.string.rule_bundle_import_preview_unsupported,
                plan.unsupportedRules.size,
            )
        )
    }

    if (plan.degradedEntries.isNotEmpty()) {
        lines.add(
            context.getString(
                R.string.rule_bundle_import_preview_degraded,
                plan.degradedEntries.size,
            )
        )
    }

    if (plan.ignoredDeleteCount > 0) {
        lines.add(
            context.getString(R.string.rule_bundle_import_preview_deleted, plan.ignoredDeleteCount)
        )
    }

    if (producedByNewerMinor) {
        lines.add(context.getString(R.string.rule_bundle_newer_minor))
    }

    return withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { ctx ->
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.rule_bundle_import_preview_title)
                .setMessage(lines.joinToString("\n"))
                .setCancelable(true)
                .setPositiveButton(R.string.rule_bundle_import_confirm) { _, _ ->
                    if (!ctx.isCompleted) ctx.resume(true)
                }
                .setNegativeButton(R.string.cancel) { _, _ -> if (!ctx.isCompleted) ctx.resume(false) }
                .setOnDismissListener { if (!ctx.isCompleted) ctx.resume(false) }
                .show()
        }
    }
}

/**
 * 拒绝原因 → 本地化文案。按类型取字符串资源，不回显异常 message，也不回显条目名与 URL。
 */
fun Context.describeBundleRejection(rejection: BundleRejection): String = when (rejection) {
    is BundleRejection.NotAZip -> getString(R.string.rule_bundle_reject_not_a_zip)
    BundleRejection.ManifestMissing -> getString(R.string.rule_bundle_reject_manifest_missing)
    is BundleRejection.ManifestInvalid -> getString(R.string.rule_bundle_reject_manifest_invalid)
    is BundleRejection.FormatVersionUnsupported ->
        getString(R.string.rule_bundle_reject_version, rejection.version)
    BundleRejection.UnsafeEntryPath -> getString(R.string.rule_bundle_reject_unsafe_path)
    is BundleRejection.ContentMissing ->
        getString(R.string.rule_bundle_reject_content_missing, rejection.path)
    is BundleRejection.ContentCorrupt ->
        getString(R.string.rule_bundle_reject_content_corrupt, rejection.path)
    is BundleRejection.ContentInvalid ->
        getString(R.string.rule_bundle_reject_content_invalid, rejection.path)
    is BundleRejection.TooLarge -> getString(R.string.rule_bundle_reject_too_large)
}
