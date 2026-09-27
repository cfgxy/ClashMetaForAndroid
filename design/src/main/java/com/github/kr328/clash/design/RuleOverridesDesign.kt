package com.github.kr328.clash.design

import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.widget.ListPopupWindow
import androidx.core.widget.doOnTextChanged
import com.github.kr328.clash.design.adapter.PopupListAdapter
import com.github.kr328.clash.design.adapter.RuleOverrideAdapter
import com.github.kr328.clash.design.databinding.DesignRuleOverridesBinding
import com.github.kr328.clash.design.databinding.DialogRuleOverrideEditBinding
import com.github.kr328.clash.design.databinding.DialogRuleOverrideMenuBinding
import com.github.kr328.clash.design.dialog.AppBottomSheetDialog
import com.github.kr328.clash.design.util.*
import com.github.kr328.clash.service.model.CustomRule
import com.github.kr328.clash.service.model.RuleOverrideItem
import com.github.kr328.clash.service.model.RulePosition
import com.github.kr328.clash.service.model.RuleSyntaxException
import com.github.kr328.clash.service.model.RuleType
import com.github.kr328.clash.service.model.RuleValidationField
import com.github.kr328.clash.service.model.validate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class RuleOverridesDesign(context: android.content.Context) : Design<RuleOverridesDesign.Request>(context) {
    sealed class Request {
        object Add : Request()
        data class Edit(val item: RuleOverrideItem) : Request()
        data class Delete(val item: RuleOverrideItem) : Request()
        object Import : Request()
        object Export : Request()
    }

    private val binding = DesignRuleOverridesBinding
        .inflate(context.layoutInflater, context.root, false)
    private val adapter = RuleOverrideAdapter(context, this::showMenu, this::showMenu)

    private var allItems: List<RuleOverrideItem> = emptyList()
    private var keyword: String = ""
    private var failedOnly: Boolean = false

    private val bannerHeightPx: Int
        get() = context.resources.getDimensionPixelSize(R.dimen.rule_overrides_banner_height)

    override val root: View
        get() = binding.root

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.recyclerList.applyLinearAdapter(context, adapter)

        binding.filterView.doOnTextChanged { text, _, _, _ ->
            keyword = text?.toString().orEmpty()

            launch { applyFilter() }
        }
    }

    suspend fun patchItems(items: List<RuleOverrideItem>) {
        allItems = items

        applyFilter()
    }

    private suspend fun applyFilter() {
        val failedCount = withContext(Dispatchers.Default) {
            allItems.count { it.applyFailed }
        }

        val filtered = withContext(Dispatchers.Default) {
            allItems
                .let {
                    if (keyword.isBlank())
                        it
                    else
                        it.filter { item ->
                            item.content.contains(keyword, ignoreCase = true) ||
                                item.policy.contains(keyword, ignoreCase = true)
                        }
                }
                .let {
                    if (failedOnly) it.filter { item -> item.applyFailed } else it
                }
        }

        adapter.apply {
            patchDataSet(this::items, filtered, id = { it.id })
        }

        withContext(Dispatchers.Main) {
            val empty = filtered.isEmpty()

            binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
            binding.emptyDescView.visibility = if (empty) View.VISIBLE else View.GONE

            val showBanner = failedCount > 0

            binding.failedBannerView.visibility = if (showBanner) View.VISIBLE else View.GONE
            binding.failedBannerTextView.text =
                context.getString(R.string.rule_apply_failed_banner, failedCount)

            // 顶栏（filter bar）高度固定，banner 是运行时才知道是否要显示的额外高度；
            // layout_marginTop 在此布局的 LinearLayout 上没有可用的 databinding setter，
            // 因此 banner 的定位和下方列表的 paddingTop 都在此处一并以命令式方式维护，
            // 避免出现两套高度来源打架。
            val topBarHeight = context.resources.getDimensionPixelSize(R.dimen.toolbar_height) +
                context.resources.getDimensionPixelSize(R.dimen.rule_overrides_filter_bar_height) +
                surface.insets.top

            (binding.failedBannerView.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.topMargin = topBarHeight
                binding.failedBannerView.layoutParams = it
            }

            val extraPadding = if (showBanner) bannerHeightPx else 0

            binding.recyclerList.setPadding(
                binding.recyclerList.paddingLeft,
                topBarHeight + extraPadding,
                binding.recyclerList.paddingRight,
                binding.recyclerList.paddingBottom,
            )
        }
    }

    fun requestToggleFailedOnly() {
        failedOnly = !failedOnly

        launch { applyFilter() }
    }

    fun requestAdd() {
        requests.trySend(Request.Add)
    }

    fun requestImport() {
        requests.trySend(Request.Import)
    }

    fun requestExport() {
        requests.trySend(Request.Export)
    }

    private fun showMenu(item: RuleOverrideItem) {
        val dialog = AppBottomSheetDialog(context)

        val binding = DialogRuleOverrideMenuBinding
            .inflate(context.layoutInflater, dialog.window?.decorView as ViewGroup?, false)

        binding.master = this
        binding.self = dialog
        binding.item = item

        dialog.setContentView(binding.root)
        dialog.show()
    }

    fun requestEdit(dialog: Dialog, item: RuleOverrideItem) {
        requests.trySend(Request.Edit(item))

        dialog.dismiss()
    }

    fun requestDelete(dialog: Dialog, item: RuleOverrideItem) {
        requests.trySend(Request.Delete(item))

        dialog.dismiss()
    }

    suspend fun showUndoableDeleteToast(): Boolean {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { ctx ->
                com.google.android.material.snackbar.Snackbar.make(
                    root,
                    context.getString(R.string.rule_deleted),
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                ).apply {
                    setAction(R.string.undo) {
                        if (!ctx.isCompleted) ctx.resume(true)
                    }
                    addCallback(object : com.google.android.material.snackbar.Snackbar.Callback() {
                        override fun onDismissed(
                            transientBottomBar: com.google.android.material.snackbar.Snackbar?,
                            event: Int
                        ) {
                            if (!ctx.isCompleted) ctx.resume(false)
                        }
                    })
                }.show()
            }
        }
    }

    suspend fun showRuleEditor(
        existing: RuleOverrideItem?,
        policyCandidates: List<String>,
        ruleSetCandidates: List<String>,
    ): CustomRule? {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { ctx ->
                val dialog = AppBottomSheetDialog(context)

                val binding = DialogRuleOverrideEditBinding
                    .inflate(context.layoutInflater, dialog.window?.decorView as ViewGroup?, false)

                binding.editing = existing != null

                var selectedType = existing?.ruleType ?: RuleType.DOMAIN
                var selectedPosition = existing?.position ?: RulePosition.PREPEND

                // RULE-SET 的 content 必须指向当前配置已声明的规则集（服务层
                // ProfileManager.validateRuleSetReference 强制），自由文本必然保存失败；
                // 因此当前配置没有任何规则集时，类型下拉里直接不出现 RULE-SET
                // ——不给用户一个必定失败的分支。仅当正在编辑的规则本身已是 RULE-SET 时
                // 才保留该项（否则编辑框会显示一个下拉里不存在的类型）。
                val selectableTypes = RuleType.entries.filter {
                    it != RuleType.RULE_SET ||
                        ruleSetCandidates.isNotEmpty() ||
                        existing?.ruleType == RuleType.RULE_SET
                }

                fun typeLabel(type: RuleType) = type.literal

                fun refreshTypeField() {
                    binding.typeField.setText(typeLabel(selectedType))
                }

                /**
                 * RULE-SET 走「只读输入框 + 规则集下拉」，其余类型走自由文本，
                 * 结构上消除「填了一个不存在的规则集名」的可能。
                 */
                fun refreshContentMode(autoFill: Boolean) {
                    val ruleSet = selectedType == RuleType.RULE_SET

                    binding.contentField.isFocusable = !ruleSet
                    binding.contentField.isFocusableInTouchMode = !ruleSet
                    binding.contentField.isCursorVisible = !ruleSet
                    binding.contentLayout.hint = context.getString(
                        if (ruleSet) R.string.rule_content_rule_set else R.string.rule_content
                    )
                    binding.contentLayout.endIconMode = if (ruleSet)
                        com.google.android.material.textfield.TextInputLayout.END_ICON_CUSTOM
                    else
                        com.google.android.material.textfield.TextInputLayout.END_ICON_NONE

                    if (autoFill && ruleSet && binding.contentField.text?.toString() !in ruleSetCandidates) {
                        binding.contentField.setText(ruleSetCandidates.firstOrNull().orEmpty())
                    }
                }

                fun showRuleSetPopup() {
                    if (ruleSetCandidates.isEmpty()) return

                    val popup = ListPopupWindow(context)
                    val current = ruleSetCandidates.indexOf(binding.contentField.text?.toString())

                    popup.anchorView = binding.contentLayout
                    popup.setAdapter(PopupListAdapter(context, ruleSetCandidates, current))
                    popup.setOnItemClickListener { _, _, position, _ ->
                        binding.contentField.setText(ruleSetCandidates[position])
                        popup.dismiss()
                    }
                    popup.show()
                }

                fun currentRule() = CustomRule(
                    ruleType = selectedType,
                    content = binding.contentField.text?.toString().orEmpty(),
                    policy = binding.policyField.text?.toString().orEmpty(),
                    position = selectedPosition,
                )

                fun validateAndShowErrors(): Boolean {
                    if (selectedType == RuleType.RULE_SET &&
                        binding.contentField.text?.toString() !in ruleSetCandidates
                    ) {
                        // 唯一能走到这里的情形：编辑一条 RULE-SET 规则，而它引用的规则集
                        // 已被删除（列表页「清空引用并删除」之外的路径不会留下这种规则）。
                        // 明确置为不可保存并给出可读提示，而不是放行到服务层再抛异常。
                        binding.contentLayout.error =
                            context.getString(R.string.rule_content_rule_set_unavailable)
                        binding.policyLayout.error = null
                        return false
                    }

                    return try {
                        currentRule().validate()
                        binding.contentLayout.error = null
                        binding.policyLayout.error = null
                        true
                    } catch (e: RuleSyntaxException) {
                        when (e.field) {
                            RuleValidationField.POLICY -> {
                                binding.policyLayout.error = context.getString(R.string.rule_policy_invalid)
                                binding.contentLayout.error = null
                            }
                            RuleValidationField.CONTENT -> {
                                binding.contentLayout.error = context.getString(R.string.rule_content_invalid)
                                binding.policyLayout.error = null
                            }
                        }
                        false
                    }
                }

                refreshTypeField()
                binding.contentField.setText(existing?.content.orEmpty())
                // 初始化不自动改写既有内容：编辑一条引用已删除规则集的旧规则时，
                // 原值要保留下来配合错误提示，而不是被悄悄换成另一个规则集。
                refreshContentMode(autoFill = existing == null)
                binding.policyField.setText(existing?.policy.orEmpty())

                when (selectedPosition) {
                    RulePosition.PREPEND -> binding.positionPrependView.isChecked = true
                    RulePosition.APPEND -> binding.positionAppendView.isChecked = true
                }

                binding.policyDegradedView.visibility =
                    if (policyCandidates.isEmpty()) View.VISIBLE else View.GONE

                binding.typeField.setOnClickListener {
                    val types = selectableTypes
                    val popup = ListPopupWindow(context)

                    popup.anchorView = binding.typeLayout
                    popup.setAdapter(
                        PopupListAdapter(
                            context,
                            types.map { typeLabel(it) },
                            types.indexOf(selectedType)
                        )
                    )
                    popup.setOnItemClickListener { _, _, position, _ ->
                        selectedType = types[position]
                        refreshTypeField()
                        refreshContentMode(autoFill = true)
                        validateAndShowErrors()
                        popup.dismiss()
                    }
                    popup.show()
                }

                binding.contentLayout.setEndIconOnClickListener { showRuleSetPopup() }
                binding.contentField.setOnClickListener {
                    if (selectedType == RuleType.RULE_SET) showRuleSetPopup()
                }

                if (policyCandidates.isNotEmpty()) {
                    binding.policyLayout.setEndIconOnClickListener {
                        val popup = ListPopupWindow(context)
                        val current = policyCandidates.indexOf(binding.policyField.text?.toString())

                        popup.anchorView = binding.policyLayout
                        popup.setAdapter(PopupListAdapter(context, policyCandidates, current))
                        popup.setOnItemClickListener { _, _, position, _ ->
                            binding.policyField.setText(policyCandidates[position])
                            validateAndShowErrors()
                            popup.dismiss()
                        }
                        popup.show()
                    }
                }

                binding.contentField.doOnTextChanged { _, _, _, _ -> validateAndShowErrors() }
                binding.policyField.doOnTextChanged { _, _, _, _ -> validateAndShowErrors() }

                binding.positionGroup.setOnCheckedChangeListener { _, checkedId ->
                    selectedPosition = if (checkedId == binding.positionAppendView.id)
                        RulePosition.APPEND
                    else
                        RulePosition.PREPEND
                }

                binding.cancelView.setOnClickListener {
                    if (!ctx.isCompleted) ctx.resume(null)
                    dialog.dismiss()
                }

                binding.saveView.setOnClickListener {
                    if (validateAndShowErrors()) {
                        if (!ctx.isCompleted) ctx.resume(currentRule())
                        dialog.dismiss()
                    }
                }

                dialog.setOnDismissListener {
                    if (!ctx.isCompleted) ctx.resume(null)
                }

                dialog.setContentView(binding.root)
                dialog.show()

                ctx.invokeOnCancellation { dialog.dismiss() }
            }
        }
    }
}
