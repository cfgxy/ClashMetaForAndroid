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
    }

    private val binding = DesignRuleOverridesBinding
        .inflate(context.layoutInflater, context.root, false)
    private val adapter = RuleOverrideAdapter(context, this::showMenu, this::showMenu)

    private var allItems: List<RuleOverrideItem> = emptyList()
    private var keyword: String = ""

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
        val filtered = withContext(Dispatchers.Default) {
            if (keyword.isBlank())
                allItems
            else
                allItems.filter {
                    it.content.contains(keyword, ignoreCase = true) ||
                        it.policy.contains(keyword, ignoreCase = true)
                }
        }

        adapter.apply {
            patchDataSet(this::items, filtered, id = { it.id })
        }

        withContext(Dispatchers.Main) {
            val empty = filtered.isEmpty()

            binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
            binding.emptyDescView.visibility = if (empty) View.VISIBLE else View.GONE
        }
    }

    fun requestAdd() {
        requests.trySend(Request.Add)
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
    ): CustomRule? {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { ctx ->
                val dialog = AppBottomSheetDialog(context)

                val binding = DialogRuleOverrideEditBinding
                    .inflate(context.layoutInflater, dialog.window?.decorView as ViewGroup?, false)

                binding.editing = existing != null

                var selectedType = existing?.ruleType ?: RuleType.DOMAIN
                var selectedPosition = existing?.position ?: RulePosition.PREPEND

                fun typeLabel(type: RuleType) = type.literal

                fun refreshTypeField() {
                    binding.typeField.setText(typeLabel(selectedType))
                }

                fun currentRule() = CustomRule(
                    ruleType = selectedType,
                    content = binding.contentField.text?.toString().orEmpty(),
                    policy = binding.policyField.text?.toString().orEmpty(),
                    position = selectedPosition,
                )

                fun validateAndShowErrors(): Boolean {
                    return try {
                        currentRule().validate()
                        binding.contentLayout.error = null
                        binding.policyLayout.error = null
                        true
                    } catch (e: RuleSyntaxException) {
                        val message = e.message.orEmpty()
                        if (message.contains("策略"))
                            binding.policyLayout.error = message
                        else
                            binding.contentLayout.error = message
                        false
                    }
                }

                refreshTypeField()
                binding.contentField.setText(existing?.content.orEmpty())
                binding.policyField.setText(existing?.policy.orEmpty())

                when (selectedPosition) {
                    RulePosition.PREPEND -> binding.positionPrependView.isChecked = true
                    RulePosition.APPEND -> binding.positionAppendView.isChecked = true
                }

                binding.policyDegradedView.visibility =
                    if (policyCandidates.isEmpty()) View.VISIBLE else View.GONE

                binding.typeField.setOnClickListener {
                    val types = RuleType.entries
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
                        validateAndShowErrors()
                        popup.dismiss()
                    }
                    popup.show()
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
