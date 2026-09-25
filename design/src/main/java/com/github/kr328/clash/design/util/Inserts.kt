package com.github.kr328.clash.design.util

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.github.kr328.clash.design.ui.Insets

/**
 * 合成弹层实际需要避让的底部区域（QA P2-A）。
 *
 * 弹层窗口设了 `decorFitsSystemWindows = false`（[com.github.kr328.clash.common.compat.isSystemBarsTranslucentCompat]），
 * 窗口不会随软键盘收缩；若只按 `systemBars()` 计算底部留白，键盘会直接盖在弹层上，
 * 底部的确认按钮既看不见也滚不到。因此底部取「系统栏」与「输入法」两者的较大值：
 * 键盘弹起时让出键盘高度（此时键盘已覆盖导航栏区域，两者不叠加），收起时退回系统栏高度。
 */
fun Insets.avoidingIme(imeBottom: Int): Insets =
    if (imeBottom > bottom) copy(bottom = imeBottom) else this

/**
 * @param avoidIme 底部是否额外避让软键盘，见 [avoidingIme]。只有内含输入框的弹层需要打开；
 * 常规页面走 Activity 的 `adjustResize`，窗口本身会收缩，这里重复避让反而会多留一段空白。
 */
fun View.setOnInsertsChangedListener(
    adaptLandscape: Boolean = true,
    avoidIme: Boolean = false,
    listener: (Insets) -> Unit,
) {
    setOnApplyWindowInsetsListener { v, ins ->
        val compat = WindowInsetsCompat.toWindowInsetsCompat(ins)
        val insets = compat.getInsets(WindowInsetsCompat.Type.systemBars())

        val rInsets = if (ViewCompat.getLayoutDirection(v) == ViewCompat.LAYOUT_DIRECTION_LTR) {
            Insets(
                insets.left,
                insets.top,
                insets.right,
                insets.bottom,
            )
        } else {
            Insets(
                insets.right,
                insets.top,
                insets.left,
                insets.bottom,
            )
        }

        val adapted = if (adaptLandscape) rInsets.landscape(v.context) else rInsets

        listener(
            if (avoidIme) {
                adapted.avoidingIme(compat.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            } else {
                adapted
            }
        )

        compat.toWindowInsets()!!
    }

    requestApplyInsets()
}
