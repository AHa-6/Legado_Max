package io.legado.app.ui.widget.components.dialog

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.legado.app.base.BaseDialogFragment
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.dpToPx

/**
 * 基础 Compose 弹窗 Fragment。
 *
 * 有两种承载形态，由 [rendersInOwnWindow] 决定：
 *
 * 1. 默认（false）：Fragment 自己的窗口只放一个零尺寸的 [ComposeView]，真正的弹窗窗口
 *    由内容里的 Compose `Dialog()`（通常是 [AppAdaptiveDialog]）另起一层。纯展示型弹窗用这个，
 *    外观与项目里其它 Compose 弹窗完全一致。
 *
 * 2. 单窗口（true）：内容直接画在 Fragment 自己的窗口里，不再有第二层窗口。
 *    **带输入框的弹窗必须用这个**——两层窗口时输入框所在的那层不是 DialogFragment 的窗口，
 *    输入法目标窗口与焦点归属对不上，实测表现就是点了输入框不弹键盘（2026-11-05 反馈的
 *    书签弹窗无法输入）。NG 版书签弹窗也是单窗口，并在 onStart 里显式设 ADJUST_RESIZE。
 *
 *    单窗口模式下窗口背景和根视图都会改成透明（否则面板圆角外会露出不透明底板），
 *    窗口宽度按平台默认比例收窄、高度跟着内容走，让 ADJUST_RESIZE 能把窗口顶到键盘上方。
 */
abstract class BaseComposeDialogFragment : BaseDialogFragment(0) {

    /** 是否把弹窗内容直接画在本 Fragment 自己的窗口里（单窗口模式），带输入框的弹窗置 true */
    protected open val rendersInOwnWindow: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            if (rendersInOwnWindow) {
                // 宽度撑满由窗口宽度决定，高度必须跟着内容走：
                // 窗口高度是 WRAP_CONTENT，这里若写 MATCH_PARENT，在 AT_MOST 的测量下
                // 会被解析成「占满全部可用高度」，窗口被撑成整屏，ADJUST_RESIZE 随之失效。
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    DialogContent()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!rendersInOwnWindow) return
        // BaseDialogFragment.onViewCreated 会把根视图刷成主题底色（XML 弹窗需要），
        // 单窗口模式下那是一块不透明矩形，会把面板的 extraLarge 圆角糊掉，这里改回透明。
        view?.setBackgroundColor(Color.TRANSPARENT)
        dialog?.window?.let { window ->
            // DialogFragment 默认的窗口背景是带内边距的不透明底板，同样会毁掉圆角
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setLayout(dialogWidthPx(window), ViewGroup.LayoutParams.WRAP_CONTENT)
            // 键盘弹出时让窗口避开输入法。高度保持 WRAP_CONTENT（与 NG 版一致）：
            // 一旦窗口被撑成整屏高，输入法就不会再把它顶开，面板会被键盘压住。
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    /**
     * 单窗口模式下的弹窗宽度，复刻原先走 Compose `Dialog()` 时的观感：
     * 平台默认宽度约为屏宽的 88.9%，再套上 [AppAdaptiveDialogPanel] 的 280~560dp 上下限。
     */
    private fun dialogWidthPx(window: android.view.Window): Int {
        val metrics = window.decorView.resources.displayMetrics
        val platformDefault = (metrics.widthPixels * 0.889f).toInt()
        return platformDefault
            .coerceAtMost(560.dpToPx())
            .coerceAtLeast(minOf(280.dpToPx(), metrics.widthPixels))
    }

    @Composable
    protected abstract fun DialogContent()

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
    }
}
