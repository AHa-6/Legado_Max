package io.legado.app.ui.widget.components.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import io.legado.app.ui.theme.AppDimens
import io.legado.app.ui.theme.composePanelShape
import io.legado.app.ui.theme.glassPanelBorderColor
import io.legado.app.ui.theme.glassPanelContainerBottomColor
import io.legado.app.ui.theme.glassPanelContainerTopColor
import io.legado.app.ui.theme.glassPanelDepthEdgeColor
import io.legado.app.ui.theme.glassPanelHighlightColor
import io.legado.app.ui.theme.glassPanelSurfaceGlossColor
import io.legado.app.ui.theme.pageSecondaryTextColor

/**
 * 设置页分组面板：一块圆角**玻璃质感**卡片，内部按顺序摆放 [AppSettingsActionRow]。
 *
 * 参考阅读 NG 的液态玻璃设计体系（简化版），提供：
 * - 渐变容器色（顶部略亮、底部略深）
 * - 边缘高光（对角方向）
 * - 深度边缘（底部暗色阴影带）
 * - 表面光泽（顶部渐变光泽）
 * - 细边框勾勒边界
 *
 * 改造成玻璃质感后，「我的」页面不再是一片灰平板，而是有层次、有深度的透明玻璃卡片，
 * 能更好地透出壁纸背景，同时保持文字可读性。
 *
 * @param modifier 外部修饰符，调用方负责面板之间的间距
 * @param content 面板内容，通常是 [AppSettingsSectionTitle] + 若干行
 */
@Composable
fun AppSettingsPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = composePanelShape()

    // 颜色必须在 composable 上下文中求值，不能在 drawWithContent 的绘制 lambda 里调用 @Composable 函数
    val containerTop = glassPanelContainerTopColor()
    val containerBottom = glassPanelContainerBottomColor()
    val gloss = glassPanelSurfaceGlossColor()
    val highlight = glassPanelHighlightColor()
    val depthEdge = glassPanelDepthEdgeColor()
    val borderColor = glassPanelBorderColor()
    val borderWidth = AppDimens.dividerThickness

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .drawWithContent {
                // 1. 渐变背景（顶部亮 → 底部深）
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(containerTop, containerBottom),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height)
                    )
                )

                // 2. 表面光泽（顶部渐变，模拟玻璃反光）
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            gloss,
                            gloss.copy(alpha = gloss.alpha * 0.35f),
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = (size.height * 0.45f).coerceAtLeast(1f)
                    )
                )

                // 3. 边缘高光（左上 → 右下对角线）
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            highlight,
                            highlight.copy(alpha = highlight.alpha * 0.3f),
                            Color.Transparent
                        ),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height)
                    )
                )

                // 4. 深度边缘（底部暗色带，营造厚度）
                val depthBandHeight = (size.height * 0.28f).coerceAtLeast(1f)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, depthEdge),
                        startY = (size.height - depthBandHeight).coerceAtLeast(0f),
                        endY = size.height.coerceAtLeast(1f)
                    )
                )

                // 5. 内容
                drawContent()

                // 6. 边框（最后画，压在内容之上，勾勒玻璃边界）
                drawRect(
                    brush = SolidColor(borderColor),
                    style = Stroke(width = borderWidth.toPx())
                )
            },
        content = content
    )
}

/**
 * 分组标题，位于 [AppSettingsPanel] 内部的第一行。
 *
 * @param title 分组名，调用方从 string resource 取
 */
@Composable
fun AppSettingsSectionTitle(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = pageSecondaryTextColor(),
        modifier = modifier.padding(
            start = AppDimens.panelRowHorizontalPadding,
            top = AppDimens.panelTitleTopPadding,
            end = AppDimens.panelRowHorizontalPadding,
            bottom = AppDimens.panelTitleBottomPadding
        )
    )
}