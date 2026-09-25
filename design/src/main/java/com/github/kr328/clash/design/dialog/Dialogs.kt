package com.github.kr328.clash.design.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import com.github.kr328.clash.common.compat.isAllowForceDarkCompat
import com.github.kr328.clash.common.compat.isSystemBarsTranslucentCompat
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ui.Insets
import com.github.kr328.clash.design.ui.Surface
import com.github.kr328.clash.design.util.getPixels
import com.github.kr328.clash.design.util.resolveThemedResourceId
import com.github.kr328.clash.design.util.setOnInsertsChangedListener
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

class AppBottomSheetDialog(context: Context) : BottomSheetDialog(context) {
    private var insets: Insets = Insets.EMPTY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setCancelable(true)

        window!!.apply {
            isSystemBarsTranslucentCompat = true
            isAllowForceDarkCompat = false

            // 弹层有自己的 Window，不继承 Activity 的 windowSoftInputMode。API 30 以下的
            // IME inset 依赖 ADJUST_RESIZE 才会派发，这里显式声明，使下面的 ime() 避让
            // 在全部受支持版本上一致生效（QA P2-A）。
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        findViewById<ViewGroup>(com.google.android.material.R.id.container)?.apply {
            fitsSystemWindows = false
        }

        findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)?.apply {
            // avoidIme：弹层窗口 decorFitsSystemWindows = false，不随键盘收缩，底部留白
            // 必须自行让出键盘高度，否则表单底部的确认按钮被键盘盖住且滚动不到（QA P2-A）。
            setOnInsertsChangedListener(avoidIme = true) {
                if (insets != it) {
                    insets = it

                    (layoutParams as CoordinatorLayout.LayoutParams).also { params ->
                        if (ViewCompat.getLayoutDirection(this) == ViewCompat.LAYOUT_DIRECTION_LTR) {
                            params.setMargins(it.start, 0, it.end, 0)
                        } else {
                            params.setMargins(it.end, 0, it.start, 0)
                        }

                        val top = context.getPixels(R.dimen.bottom_sheet_background_padding_top)
                        val height = context.getPixels(R.dimen.bottom_sheet_header_height)

                        setPaddingRelative(
                            0,
                            top * 2 + height,
                            0,
                            it.bottom
                        )
                    }
                }
            }
        }

        setOnShowListener {
            behavior.halfExpandedRatio = 0.99f
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }
}

class FullScreenDialog(
    context: Context
) : Dialog(context, context.resolveThemedResourceId(R.attr.fullScreenDialogTheme)) {
    val surface = Surface()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window!!.apply {
            isSystemBarsTranslucentCompat = true

            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )

            decorView.setOnInsertsChangedListener {
                if (surface.insets != it)
                    surface.insets = it
            }
        }
    }
}