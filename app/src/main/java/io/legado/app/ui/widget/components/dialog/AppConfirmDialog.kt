package io.legado.app.ui.widget.components.dialog

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.R
import io.legado.app.lib.theme.eInkGrayscale

/**
 * 确认弹窗。
 *
 * ## 为什么不再是 `AlertDialog`
 * 旧实现是 `AlertDialog` + `RectangleShape`：四个直角、`surface` 底色、按钮贴在右下角，
 * 而应用里其它弹窗（[AppAdaptiveDialog]、书架的智能标签管理等）统一是
 * extraLarge 圆角 + `surfaceContainerHigh` 底色 + 24dp 内边距。同一个界面里两套弹窗
 * 形状对不上，批量删除那种破坏性操作反而看着最旧。
 *
 * 现在直接复用 [AppAdaptiveDialogPanel]，确认弹窗与其余弹窗长得完全一样，
 * 签名一个字没改，18 处调用点（缓存清理、书签批量删除、书源回收站、直链上传……）
 * 全部自动跟着换。
 *
 * @param title 标题，headlineSmall
 * @param text 正文，bodyMedium，放在 content 槽里（自带 24dp 下边距）
 * @param confirmText 确认按钮文字
 * @param destructive 破坏性操作：确认按钮走 error 色，提示这一下按下去不可逆
 * @param dismissText 取消按钮文字
 * @param containerColor 面板底色，默认与其它弹窗同口径
 */
@Composable
fun AppConfirmDialog(
    title: String,
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    destructive: Boolean = false,
    dismissText: String = stringResource(R.string.cancel),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        // 与 AppAdaptiveDialog 一致：保留平台默认对话框宽度，整屏宽会和其它弹窗对不齐
        properties = DialogProperties(),
    ) {
        AppAdaptiveDialogPanel(
            title = title,
            // 弹层是独立窗口，页面那层灰阶罩不到，这里再贴一次
            modifier = Modifier.eInkGrayscale(),
            containerColor = containerColor,
            buttons = {
                TextButton(onClick = onDismissRequest) {
                    Text(
                        text = dismissText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onConfirm) {
                    Text(
                        text = confirmText,
                        color = if (destructive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            },
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
