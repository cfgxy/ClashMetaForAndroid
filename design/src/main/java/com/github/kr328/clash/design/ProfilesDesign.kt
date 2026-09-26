package com.github.kr328.clash.design

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import com.github.kr328.clash.design.adapter.ProfileAdapter
import com.github.kr328.clash.design.databinding.DesignProfilesBinding
import com.github.kr328.clash.design.databinding.DialogProfilesMenuBinding
import com.github.kr328.clash.design.databinding.DialogShareQrCodeBinding
import com.github.kr328.clash.design.dialog.AppBottomSheetDialog
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.*
import com.github.kr328.clash.service.model.Profile
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProfilesDesign(context: Context) : Design<ProfilesDesign.Request>(context) {
    sealed class Request {
        object UpdateAll : Request()
        object Create : Request()
        data class Active(val profile: Profile) : Request()
        data class Update(val profile: Profile) : Request()
        data class Edit(val profile: Profile) : Request()
        data class Duplicate(val profile: Profile) : Request()
        data class Delete(val profile: Profile) : Request()
        data class ShareQrCode(val profile: Profile) : Request()
        data class SaveQrCode(val fileName: String, val bitmap: Bitmap) : Request()
    }

    private val binding = DesignProfilesBinding
        .inflate(context.layoutInflater, context.root, false)
    private val adapter = ProfileAdapter(context, this::requestActive, this::showMenu)

    private var allUpdating: Boolean
        get() = adapter.states.allUpdating;
        set(value) {
            adapter.states.allUpdating = value
        }
    private val rotateAnimation : Animation = AnimationUtils.loadAnimation(context, R.anim.rotate_infinite)

    override val root: View
        get() = binding.root

    suspend fun patchProfiles(profiles: List<Profile>) {
        adapter.apply {
            patchDataSet(this::profiles, profiles, id = { it.uuid })
        }

        val updatable = withContext(Dispatchers.Default) {
            profiles.any { it.imported && it.type != Profile.Type.File }
        }

        withContext(Dispatchers.Main) {
            binding.updateView.visibility = if (updatable) View.VISIBLE else View.GONE
        }
    }

    suspend fun requestSave(profile: Profile) {
        showToast(R.string.active_unsaved_tips, ToastDuration.Long) {
            setAction(R.string.edit) {
                requests.trySend(Request.Edit(profile))
            }
        }
    }

    /**
     * 二维码保存结果提示，只给动作级描述，不回显订阅地址任何片段。
     */
    suspend fun showQrCodeSaveResult(succeed: Boolean) {
        showToast(
            if (succeed) R.string.share_qr_code_saved else R.string.share_qr_code_save_failed,
            ToastDuration.Long,
        )
    }

    fun updateElapsed() {
        adapter.updateElapsed()
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.mainList.recyclerList.also {
            it.bindAppBarElevation(binding.activityBarLayout)
            it.applyLinearAdapter(context, adapter)
        }
    }

    private fun showMenu(profile: Profile) {
        val dialog = AppBottomSheetDialog(context)

        val binding = DialogProfilesMenuBinding
            .inflate(context.layoutInflater, dialog.window?.decorView as ViewGroup?, false)

        binding.master = this
        binding.self = dialog
        binding.profile = profile

        dialog.setContentView(binding.root)
        dialog.show()
    }

    fun requestUpdateAll() {
        allUpdating = true;
        changeUpdateAllButtonStatus()
        requests.trySend(Request.UpdateAll)
    }

    fun finishUpdateAll() {
        allUpdating = false;
        changeUpdateAllButtonStatus()
    }

    fun requestCreate() {
        requests.trySend(Request.Create)
    }

    private fun requestActive(profile: Profile) {
        requests.trySend(Request.Active(profile))
    }

    fun requestUpdate(dialog: Dialog, profile: Profile) {
        requests.trySend(Request.Update(profile))

        dialog.dismiss()
    }

    fun requestEdit(dialog: Dialog, profile: Profile) {
        requests.trySend(Request.Edit(profile))

        dialog.dismiss()
    }

    fun requestDuplicate(dialog: Dialog, profile: Profile) {
        requests.trySend(Request.Duplicate(profile))

        dialog.dismiss()
    }

    fun requestDelete(dialog: Dialog, profile: Profile) {
        requests.trySend(Request.Delete(profile))

        dialog.dismiss()
    }

    fun requestShareQrCode(dialog: Dialog, profile: Profile) {
        requests.trySend(Request.ShareQrCode(profile))

        dialog.dismiss()
    }

    /**
     * 展示订阅二维码。二维码内容仅在内存中构造与渲染，不落盘、不记日志。
     */
    suspend fun showQrCode(profile: Profile) {
        val content = ProfileShare.shareContentOf(profile)
            ?: return showToast(R.string.share_qr_code_unavailable, ToastDuration.Long)

        val size = context.getPixels(R.dimen.share_qr_code_size)

        val bitmap = withContext(Dispatchers.Default) {
            try {
                QrCode.encodeToBitmap(content, size)
            } catch (e: Exception) {
                null
            }
        } ?: return showToast(R.string.share_qr_code_generate_failed, ToastDuration.Long)

        withContext(Dispatchers.Main) {
            val binding = DialogShareQrCodeBinding.inflate(context.layoutInflater)

            binding.qrCodeView.setImageBitmap(bitmap)
            binding.profileNameView.text = profile.name

            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.share_qr_code)
                .setView(binding.root)
                .setCancelable(true)
                .setPositiveButton(R.string.save) { _, _ ->
                    requests.trySend(Request.SaveQrCode(qrCodeFileNameOf(profile), bitmap))
                }
                .setNegativeButton(R.string.close) { _, _ -> }
                .show()
        }
    }

    /**
     * 生成保存用文件名：仅取配置名中的安全字符与时间戳，不含订阅地址任何片段。
     */
    private fun qrCodeFileNameOf(profile: Profile): String {
        val name = profile.name
            .replace(Regex("[^A-Za-z0-9_\\-]"), "_")
            .take(32)
            .ifBlank { "profile" }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())

        return "clash_qrcode_${name}_$timestamp"
    }

    private fun changeUpdateAllButtonStatus() {
        if (allUpdating) {
            binding.updateView.startAnimation(rotateAnimation)
        } else {
            binding.updateView.clearAnimation()
        }
    }
}