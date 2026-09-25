package com.github.kr328.clash.design

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.ListPopupWindow
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doOnTextChanged
import com.github.kr328.clash.design.adapter.PopupListAdapter
import com.github.kr328.clash.design.adapter.RuleProviderAdapter
import com.github.kr328.clash.design.databinding.DesignRuleProvidersBinding
import com.github.kr328.clash.design.databinding.DialogRuleProviderDeleteReferencedBinding
import com.github.kr328.clash.design.databinding.DialogRuleProviderEditBinding
import com.github.kr328.clash.design.databinding.DialogRuleProviderMenuBinding
import com.github.kr328.clash.design.dialog.AppBottomSheetDialog
import com.github.kr328.clash.design.model.RuleProviderState
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.applyLinearAdapter
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.patchDataSet
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.model.CustomRuleProvider
import com.github.kr328.clash.service.model.RuleProviderBehavior
import com.github.kr328.clash.service.model.RuleProviderFormat
import com.github.kr328.clash.service.model.RuleProviderItem
import com.github.kr328.clash.service.model.RuleProviderSyntaxException
import com.github.kr328.clash.service.model.RuleProviderType
import com.github.kr328.clash.service.model.RuleProviderUpdateInterval
import com.github.kr328.clash.service.model.RuleProviderValidationField
import com.github.kr328.clash.service.model.derivedPath
import com.github.kr328.clash.service.model.validate
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.resume

/**
 * 规则集（rule-providers）管理页。骨架与自定义规则页一致（顶栏 + 计数行 + 卡片列表），
 * 交互覆盖：列表默认态 / 空态 / 单条刷新中 / 更新全部进度 / 失败 banner 与失败 Snackbar、
 * 新增与编辑表单、无引用轻确认与存在引用强提示两个删除分支。
 */
class RuleProvidersDesign(context: Context) : Design<RuleProvidersDesign.Request>(context) {
    sealed class Request {
        object Add : Request()
        object UpdateAll : Request()
        data class Edit(val item: RuleProviderItem) : Request()
        data class Delete(val item: RuleProviderItem) : Request()
        data class Update(val item: RuleProviderItem) : Request()
    }

    private val binding = DesignRuleProvidersBinding
        .inflate(context.layoutInflater, context.root, false)

    private val adapter = RuleProviderAdapter(
        context,
        onClicked = { requests.trySend(Request.Edit(it)) },
        onRefreshClicked = { requests.trySend(Request.Update(it)) },
        onMenuClicked = this::showMenu,
    )

    private var allStates: List<RuleProviderState> = emptyList()
    private var failedOnly: Boolean = false

    /** 「更新全部」进行中：顶栏两个图标置灰，避免叠加发起第二轮。 */
    private var updatingAll: Boolean = false

    private val bannerHeightPx: Int
        get() = context.resources.getDimensionPixelSize(R.dimen.rule_overrides_banner_height)

    override val root: View
        get() = binding.root

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.recyclerList.applyLinearAdapter(context, adapter)
    }

    // region 列表数据

    /**
     * @param updatedAtByName 内核侧报告的各规则集最后更新时间（毫秒）。内核未运行时传空 map，
     * 行内即显示「尚未更新」，不以本地时间伪造内核状态。
     */
    suspend fun patchItems(items: List<RuleProviderItem>, updatedAtByName: Map<String, Long>) {
        // 重新拉取列表的触发点包括「保存后刷新」，此时正在进行的更新流程状态
        // （刷新中 / 失败 / 排队）不能被抹掉，按 id 继承下来。
        val previous = allStates.associateBy { it.item.id }

        allStates = items.map { item ->
            val old = previous[item.id]

            RuleProviderState(
                item = item,
                updating = old?.updating ?: false,
                updateFailed = old?.updateFailed ?: false,
                queued = old?.queued ?: false,
                updatedAt = updatedAtByName[item.name] ?: old?.updatedAt ?: 0L,
            )
        }

        applyFilter()
    }

    private suspend fun applyFilter() {
        val failedCount = allStates.count { it.updateFailed }
        val visible = if (failedOnly) allStates.filter { it.updateFailed } else allStates

        adapter.patchDataSet(adapter::states, visible, id = { it.item.id })

        withContext(Dispatchers.Main) {
            val empty = allStates.isEmpty()

            binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
            binding.recyclerList.visibility = if (empty) View.GONE else View.VISIBLE

            binding.summaryView.text =
                context.getString(R.string.rule_providers_summary, allStates.size)

            // 更新全部进行中时进度 banner 独占该位置，失败 banner 不与其叠加。
            val showFailedBanner = failedCount > 0 && !updatingAll

            binding.failedBannerView.visibility = if (showFailedBanner) View.VISIBLE else View.GONE
            binding.failedBannerTextView.text =
                context.getString(R.string.rule_provider_update_failed_banner, failedCount)

            layoutBanners()
        }
    }

    /**
     * banner 是运行时才知道是否显示的额外高度，且其 layout_marginTop 在本布局上没有可用的
     * databinding setter；因此 banner 定位与列表 paddingTop 统一在此一处以命令式维护，
     * 与自定义规则页同一做法，避免出现两套高度来源。
     */
    private fun layoutBanners() {
        val topBarHeight = context.resources.getDimensionPixelSize(R.dimen.toolbar_height) +
            context.resources.getDimensionPixelSize(R.dimen.rule_providers_summary_height) +
            surface.insets.top

        listOf(binding.progressBannerView, binding.failedBannerView).forEach { banner ->
            (banner.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.topMargin = topBarHeight
                banner.layoutParams = it
            }
        }

        val showBanner = binding.progressBannerView.visibility == View.VISIBLE ||
            binding.failedBannerView.visibility == View.VISIBLE

        binding.recyclerList.setPadding(
            binding.recyclerList.paddingLeft,
            topBarHeight + if (showBanner) bannerHeightPx else 0,
            binding.recyclerList.paddingRight,
            binding.recyclerList.paddingBottom,
        )
    }

    // endregion

    // region 行状态

    /**
     * adapter 持有的 state 与 [allStates] 是同一批对象引用（过滤只筛选不拷贝），
     * 改一处即可，databinding 会把变化推到对应行。
     */
    private suspend fun updateState(id: UUID, block: RuleProviderState.() -> Unit) {
        withContext(Dispatchers.Main) {
            allStates.find { it.item.id == id }?.block()
        }
    }

    suspend fun markUpdating(id: UUID) = updateState(id) {
        updating = true
        queued = false
        updateFailed = false
    }

    suspend fun markQueued(ids: Collection<UUID>) {
        val queuedIds = ids.toSet()

        withContext(Dispatchers.Main) {
            allStates.forEach {
                if (it.item.id in queuedIds) it.queued = true
            }
        }
    }

    suspend fun markUpdated(id: UUID, updatedAt: Long) {
        updateState(id) {
            updating = false
            queued = false
            updateFailed = false
            this.updatedAt = updatedAt
        }

        // 最后一条失败项被成功重试后，失败 banner 应随之消失。
        applyFilter()
    }

    suspend fun markUpdateFailed(id: UUID) {
        updateState(id) {
            updating = false
            queued = false
            updateFailed = true
        }

        applyFilter()
    }

    // endregion

    // region 顶部反馈

    suspend fun showUpdateAllProgress(current: Int, total: Int) {
        updatingAll = true

        withContext(Dispatchers.Main) {
            binding.progressBannerView.visibility = View.VISIBLE
            binding.progressBannerTextView.text =
                context.getString(R.string.rule_provider_update_all_progress, current, total)
            binding.failedBannerView.visibility = View.GONE

            setTopActionsEnabled(false)

            layoutBanners()
        }
    }

    suspend fun hideUpdateAllProgress() {
        updatingAll = false

        withContext(Dispatchers.Main) {
            binding.progressBannerView.visibility = View.GONE

            setTopActionsEnabled(true)
        }

        applyFilter()
    }

    private fun setTopActionsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.45f

        binding.updateAllView.isEnabled = enabled
        binding.updateAllView.alpha = alpha
        binding.addView.isEnabled = enabled
        binding.addView.alpha = alpha
    }

    /** 全部成功才弹成功 Snackbar；存在失败项时只走失败 banner，不同时给出两种结论。 */
    suspend fun showUpdateAllDone(count: Int, elapsedSeconds: Long) {
        showToast(
            context.getString(R.string.rule_provider_update_all_done, count, elapsedSeconds),
            ToastDuration.Long
        )
    }

    suspend fun showUpdateAllUnavailable() {
        showToast(R.string.rule_provider_update_all_unavailable, ToastDuration.Long)
    }

    suspend fun showUpdateAllRunning() {
        showToast(R.string.rule_provider_update_all_running, ToastDuration.Short)
    }

    /**
     * 单条更新失败 Snackbar，带「重试」。
     * @param reason 已脱敏的失败原因（不得包含完整 URL，见 `maskUrl`）。
     * @return true 表示用户点了「重试」。
     */
    suspend fun showUpdateFailedToast(name: String, reason: String): Boolean {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { ctx ->
                Snackbar.make(
                    root,
                    context.getString(R.string.rule_provider_update_failed_toast, name, reason),
                    Snackbar.LENGTH_LONG
                ).apply {
                    setAction(R.string.rule_provider_update_retry) {
                        if (!ctx.isCompleted) ctx.resume(true)
                    }
                    addCallback(object : Snackbar.Callback() {
                        override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                            if (!ctx.isCompleted) ctx.resume(false)
                        }
                    })
                }.show()
            }
        }
    }

    suspend fun showSaveFailedToast(reason: String) {
        showToast(
            context.getString(R.string.rule_provider_save_failed, reason),
            ToastDuration.Long
        )
    }

    suspend fun showDeletedToast(name: String) {
        showToast(
            context.getString(R.string.rule_provider_deleted, name),
            ToastDuration.Long
        )
    }

    /** 清空引用并删除成功后的反馈，需说明被清掉的引用条数。 */
    suspend fun showDeletedWithReferencesToast(count: Int, name: String) {
        showToast(
            context.getString(R.string.rule_provider_deleted_with_references, count, name),
            ToastDuration.Long
        )
    }

    // endregion

    // region 请求入口

    fun requestAdd() {
        if (updatingAll) {
            launch { showUpdateAllRunning() }

            return
        }

        requests.trySend(Request.Add)
    }

    fun requestUpdateAll() {
        if (updatingAll) {
            launch { showUpdateAllRunning() }

            return
        }

        requests.trySend(Request.UpdateAll)
    }

    fun requestToggleFailedOnly() {
        failedOnly = !failedOnly

        launch { applyFilter() }
    }

    private fun showMenu(item: RuleProviderItem) {
        val dialog = AppBottomSheetDialog(context)

        val menuBinding = DialogRuleProviderMenuBinding
            .inflate(context.layoutInflater, dialog.window?.decorView as ViewGroup?, false)

        menuBinding.master = this
        menuBinding.self = dialog
        menuBinding.item = item

        dialog.setContentView(menuBinding.root)
        dialog.show()
    }

    fun requestEdit(dialog: Dialog, item: RuleProviderItem) {
        requests.trySend(Request.Edit(item))

        dialog.dismiss()
    }

    fun requestDelete(dialog: Dialog, item: RuleProviderItem) {
        requests.trySend(Request.Delete(item))

        dialog.dismiss()
    }

    // endregion

    // region 删除确认

    /** 无引用分支：轻确认，默认动作是取消，删除为红色次级按钮。 */
    suspend fun confirmDelete(name: String): Boolean {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { ctx ->
                val dialog = MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.rule_provider_delete_title)
                    .setMessage(context.getString(R.string.rule_provider_delete_message, name))
                    .setCancelable(true)
                    .setNegativeButton(R.string.delete) { _, _ ->
                        if (!ctx.isCompleted) ctx.resume(true)
                    }
                    .setPositiveButton(R.string.cancel) { _, _ ->
                        if (!ctx.isCompleted) ctx.resume(false)
                    }
                    .setOnDismissListener {
                        if (!ctx.isCompleted) ctx.resume(false)
                    }
                    .show()

                dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.setTextColor(
                    context.resolveThemedColor(com.google.android.material.R.attr.colorError)
                )

                ctx.invokeOnCancellation { dialog.dismiss() }
            }
        }
    }

    /**
     * 存在引用分支：强提示。默认动作是「取消 · 不做任何操作」，次级动作是
     * 「清空引用并删除」；**不提供**跳过引用检查的直接删除。
     *
     * @param references 引用该规则集的规则预览行（由调用方组装）。
     * @return true 表示用户选择「清空引用并删除」。
     */
    suspend fun confirmDeleteReferenced(
        name: String,
        referenceCount: Int,
        references: List<String>,
    ): Boolean {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { ctx ->
                val content = DialogRuleProviderDeleteReferencedBinding
                    .inflate(context.layoutInflater, null, false)

                content.messageView.text = context.getString(
                    R.string.rule_provider_delete_referenced_message,
                    referenceCount,
                    name,
                )

                val previewLimit = 3

                fun renderReferences(all: Boolean) {
                    content.referencesView.removeAllViews()

                    (if (all) references else references.take(previewLimit)).forEach { line ->
                        content.referencesView.addView(
                            TextView(context).apply {
                                text = line
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                            }
                        )
                    }

                    val hasMore = !all && references.size > previewLimit

                    content.viewAllView.visibility = if (hasMore) View.VISIBLE else View.GONE
                    content.viewAllView.text = context.getString(
                        R.string.rule_provider_delete_referenced_view_all,
                        referenceCount,
                    )
                }

                renderReferences(all = false)

                content.viewAllView.setOnClickListener { renderReferences(all = true) }

                val dialog = MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.rule_provider_delete_referenced_title)
                    .setView(content.root)
                    .setCancelable(true)
                    .setNegativeButton(R.string.rule_provider_delete_referenced_force) { _, _ ->
                        if (!ctx.isCompleted) ctx.resume(true)
                    }
                    .setPositiveButton(R.string.rule_provider_delete_referenced_cancel) { _, _ ->
                        if (!ctx.isCompleted) ctx.resume(false)
                    }
                    .setOnDismissListener {
                        if (!ctx.isCompleted) ctx.resume(false)
                    }
                    .show()

                dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.setTextColor(
                    context.resolveThemedColor(com.google.android.material.R.attr.colorError)
                )

                ctx.invokeOnCancellation { dialog.dismiss() }
            }
        }
    }

    // endregion

    // region 新增 / 编辑表单

    /**
     * 新增与编辑共用一套表单，差异只在标题与主按钮文案。
     *
     * @param existingNames 同一配置内已存在的规则集名称（编辑时不含自身），用于就地做唯一性
     * 校验，不等服务层抛异常再提示。
     */
    suspend fun showEditor(
        existing: RuleProviderItem?,
        existingNames: Set<String>,
    ): CustomRuleProvider? {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { ctx ->
                val dialog = AppBottomSheetDialog(context)

                val form = DialogRuleProviderEditBinding
                    .inflate(context.layoutInflater, dialog.window?.decorView as ViewGroup?, false)

                form.editing = existing != null

                var selectedType = existing?.type ?: RuleProviderType.HTTP
                var selectedBehavior = existing?.behavior ?: RuleProviderBehavior.DOMAIN
                var selectedInterval =
                    existing?.updateInterval ?: RuleProviderUpdateInterval.EVERY_12_HOURS

                // format 不进表单：mihomo 要求 path 后缀与 format 一致，而 path 由名称与
                // format 确定性生成；本期只落地 yaml 一种格式，不给出一个会写出
                // 「后缀与 format 不符」配置的自由选项。
                val format = existing?.format ?: RuleProviderFormat.YAML

                val behaviorCandidates = RuleProviderBehavior.entries
                val intervalCandidates = RuleProviderUpdateInterval.entries

                // 类型候选集取领域层单一事实源 RuleProviderType.selectable（排除 INLINE：
                // 它的内容载体是 payload 列表，本版本没有编辑入口，服务层 validate() 也硬拒）。
                // 布局里只画了 HTTP/FILE 两个按钮，这里按候选集控制可见性并兜住
                // 「已存在记录的 type 不在候选集里」的情况，不让一个必定校验失败的值留在表单上。
                val typeCandidates = RuleProviderType.selectable

                form.typeHttpView.visibility =
                    if (RuleProviderType.HTTP in typeCandidates) View.VISIBLE else View.GONE
                form.typeFileView.visibility =
                    if (RuleProviderType.FILE in typeCandidates) View.VISIBLE else View.GONE

                if (selectedType !in typeCandidates) {
                    selectedType = typeCandidates.first()
                }

                fun behaviorLabel(behavior: RuleProviderBehavior) = context.getString(
                    when (behavior) {
                        RuleProviderBehavior.DOMAIN -> R.string.rule_provider_behavior_domain
                        RuleProviderBehavior.IPCIDR -> R.string.rule_provider_behavior_ipcidr
                        RuleProviderBehavior.CLASSICAL -> R.string.rule_provider_behavior_classical
                    }
                )

                fun intervalLabel(interval: RuleProviderUpdateInterval) = context.getString(
                    when (interval) {
                        RuleProviderUpdateInterval.NEVER ->
                            R.string.rule_provider_interval_never
                        RuleProviderUpdateInterval.HOURLY ->
                            R.string.rule_provider_interval_hourly
                        RuleProviderUpdateInterval.EVERY_6_HOURS ->
                            R.string.rule_provider_interval_6h
                        RuleProviderUpdateInterval.EVERY_12_HOURS ->
                            R.string.rule_provider_interval_12h
                        RuleProviderUpdateInterval.DAILY ->
                            R.string.rule_provider_interval_daily
                    }
                )

                fun currentName() = form.nameField.text?.toString().orEmpty()

                fun refreshPath() {
                    val name = currentName()

                    form.pathField.setText(
                        if (name.isEmpty()) ""
                        else CustomRuleProvider(
                            name = name,
                            type = selectedType,
                            behavior = selectedBehavior,
                            format = format,
                            url = "",
                            updateInterval = selectedInterval,
                        ).derivedPath()
                    )
                }

                /**
                 * 类型切换：本地 FILE 时 URL 字段变为「本地文件路径」并补充
                 * 「相对配置目录，文件需已存在」说明，更新间隔置灰为「FILE 不自动更新」。
                 * FILE 的文件位置同样取 derivedPath，不接受自由输入，与缓存路径同一口径，
                 * 结构性消除路径穿越。
                 */
                fun refreshTypeMode() {
                    val file = selectedType == RuleProviderType.FILE

                    form.typeHttpView.isChecked = !file
                    form.typeFileView.isChecked = file

                    form.urlLayout.hint = context.getString(
                        if (file) R.string.rule_provider_file_path else R.string.rule_provider_url
                    )
                    form.urlDescView.visibility = if (file) View.VISIBLE else View.GONE
                    form.urlField.isEnabled = !file

                    if (file) {
                        form.urlLayout.error = null
                        form.urlField.setText(form.pathField.text?.toString().orEmpty())
                    } else if (form.urlField.text?.toString() == form.pathField.text?.toString()) {
                        form.urlField.setText(existing?.url.orEmpty())
                    }

                    form.intervalLayout.isEnabled = !file
                    form.intervalField.setText(
                        if (file) context.getString(R.string.rule_provider_interval_file)
                        else intervalLabel(selectedInterval)
                    )
                }

                fun currentProvider() = CustomRuleProvider(
                    name = currentName(),
                    type = selectedType,
                    behavior = selectedBehavior,
                    format = format,
                    // FILE 不使用 url（RuleProviderOverrideApplier.toEntry 不写 url 键）。
                    url = if (selectedType == RuleProviderType.FILE) ""
                    else form.urlField.text?.toString().orEmpty(),
                    updateInterval = if (selectedType == RuleProviderType.FILE)
                        RuleProviderUpdateInterval.NEVER
                    else
                        selectedInterval,
                )

                /**
                 * 字段级校验：错误只落在对应字段下方，其余字段保留输入；点击保存后重新校验
                 * 并把弹层滚动到第一个出错字段，避免错误提示落在键盘遮住的区域里看不见。
                 */
                fun validate(scrollToError: Boolean): Boolean {
                    form.nameLayout.error = null
                    form.urlLayout.error = null
                    form.behaviorLayout.error = null

                    var firstError: View? = null

                    if (currentName().isEmpty()) {
                        form.nameLayout.error =
                            context.getString(R.string.rule_provider_name_required)
                        firstError = form.nameLayout
                    } else if (currentName() in existingNames) {
                        form.nameLayout.error =
                            context.getString(R.string.rule_provider_name_duplicated)
                        firstError = form.nameLayout
                    }

                    if (firstError == null) {
                        try {
                            currentProvider().validate()
                        } catch (e: RuleProviderSyntaxException) {
                            when (e.field) {
                                RuleProviderValidationField.NAME -> {
                                    form.nameLayout.error =
                                        context.getString(R.string.rule_provider_name_invalid)
                                    firstError = form.nameLayout
                                }
                                RuleProviderValidationField.URL -> {
                                    form.urlLayout.error = context.getString(
                                        if (form.urlField.text.isNullOrEmpty())
                                            R.string.rule_provider_url_required
                                        else
                                            R.string.rule_provider_url_invalid
                                    )
                                    firstError = form.urlLayout
                                }
                                // TYPE 只在 INLINE 时触发，而表单候选集只有 HTTP/FILE；
                                // 仍按字段落位提示，不让它退化成静默失败。
                                RuleProviderValidationField.TYPE -> {
                                    form.behaviorLayout.error = e.message
                                    firstError = form.behaviorLayout
                                }
                            }
                        }
                    }

                    val target = firstError

                    if (target != null && scrollToError) {
                        (form.root as? NestedScrollView)?.smoothScrollTo(0, target.top)
                    }

                    return target == null
                }

                form.nameField.setText(existing?.name.orEmpty())
                form.urlField.setText(existing?.url.orEmpty())
                form.behaviorField.setText(behaviorLabel(selectedBehavior))
                refreshPath()
                refreshTypeMode()

                form.nameField.doOnTextChanged { _, _, _, _ ->
                    refreshPath()

                    if (selectedType == RuleProviderType.FILE) refreshTypeMode()

                    // 只在已有错误时重算，避免用户刚开始输入就被「名称不能为空」打断。
                    if (form.nameLayout.error != null) validate(scrollToError = false)
                }

                form.urlField.doOnTextChanged { _, _, _, _ ->
                    if (form.urlLayout.error != null) validate(scrollToError = false)
                }

                form.typeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
                    if (!isChecked) return@addOnButtonCheckedListener

                    selectedType = if (checkedId == form.typeFileView.id)
                        RuleProviderType.FILE
                    else
                        RuleProviderType.HTTP

                    refreshTypeMode()
                }

                form.behaviorLayout.setEndIconOnClickListener {
                    val popup = ListPopupWindow(context)

                    popup.anchorView = form.behaviorLayout
                    popup.setAdapter(
                        PopupListAdapter(
                            context,
                            behaviorCandidates.map { behaviorLabel(it) },
                            behaviorCandidates.indexOf(selectedBehavior),
                        )
                    )
                    popup.setOnItemClickListener { _, _, position, _ ->
                        selectedBehavior = behaviorCandidates[position]
                        form.behaviorField.setText(behaviorLabel(selectedBehavior))
                        refreshPath()
                        if (selectedType == RuleProviderType.FILE) refreshTypeMode()
                        popup.dismiss()
                    }
                    popup.show()
                }

                form.intervalLayout.setEndIconOnClickListener {
                    if (selectedType == RuleProviderType.FILE) return@setEndIconOnClickListener

                    val popup = ListPopupWindow(context)

                    popup.anchorView = form.intervalLayout
                    popup.setAdapter(
                        PopupListAdapter(
                            context,
                            intervalCandidates.map { intervalLabel(it) },
                            intervalCandidates.indexOf(selectedInterval),
                        )
                    )
                    popup.setOnItemClickListener { _, _, position, _ ->
                        selectedInterval = intervalCandidates[position]
                        form.intervalField.setText(intervalLabel(selectedInterval))
                        popup.dismiss()
                    }
                    popup.show()
                }

                form.cancelView.setOnClickListener {
                    if (!ctx.isCompleted) ctx.resume(null)
                    dialog.dismiss()
                }

                form.saveView.setOnClickListener {
                    if (validate(scrollToError = true)) {
                        if (!ctx.isCompleted) ctx.resume(currentProvider())
                        dialog.dismiss()
                    }
                }

                dialog.setOnDismissListener {
                    if (!ctx.isCompleted) ctx.resume(null)
                }

                dialog.setContentView(form.root)
                dialog.show()

                ctx.invokeOnCancellation { dialog.dismiss() }
            }
        }
    }

    // endregion
}
