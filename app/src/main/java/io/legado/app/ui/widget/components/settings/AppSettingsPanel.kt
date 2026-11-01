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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.legado.app.ui.theme.AppDimens
import io.legado.app.ui.theme.composePanelRadius
import io.legado.app.ui.theme.composePanelShape
import io.legado.app.ui.theme.glassPanelBorderColor
import io.legado.app.ui.theme.glassPanelContainerTopColor
import io.legado.app.ui.theme.pageSecondaryTextColor

/**
 * 面板发丝描边宽度。
 *
 * NG 的 `NgSettingsCardSurface` 在静态路径上用 0.6dp：比 1dp 的 dividerThickness 更轻，
 * 在白壁纸上不会显出一条硬边，这是「一层薄雾 + 一道细边」里的那道细边。
 */
private val GlassPanelBorderWidth = 0.6.dp

/**
 * 设置页分组面板：一块圆角玻璃质感卡片，内部按顺序摆放 [AppSettingsActionRow]。
 *
 * 材质刻意对齐 NG 在 API < 31 设备上实际使用的那一套（取值见 `glassPanel*` 颜色说明）：
 * 一层半透明白 + 一道细发丝描边，不叠渐变、不叠光泽、不叠高光、不叠底部暗带。
 *
 * 真模糊需要 RenderEffect（API 31）/ RuntimeShader（API 33），在更低的系统上根本画不出来；
 * 此时用多层半透明去「假装」玻璃，每层自带 alpha，叠在壁纸上只会把背景压成一块脏灰，
 * 且渐变方向与壁纸内容无关，看起来像污渍而不是玻璃。所以直接照搬 NG 的静态配方：
 * NG 在这些设备上走的正是「一层薄雾 + 一道细边」，观感反而干净。
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
    val containerColor = glassPanelContainerTopColor()
    val borderColor = glassPanelBorderColor()
    val borderWidth = GlassPanelBorderWidth
    val radiusPx = with(LocalDensity.current) { composePanelRadius().toPx() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .drawWithContent {
                // 1. 单层容器色。NG 的静态路径就是这一层，没有渐变
                drawRect(brush = SolidColor(containerColor))
                // 2. 内容
                drawContent()
                // 3. 发丝描边，压在内容之上勾勒边界。
                //    内缩半个笔宽，否则描边会被 clip 切掉一半、看起来断断续续
                val inset = borderWidth.toPx() / 2f
                drawRoundRect(
                    brush = SolidColor(borderColor),
                    topLeft = Offset(inset, inset),
                    size = Size(
                        width = (size.width - inset * 2f).coerceAtLeast(0f),
                        height = (size.height - inset * 2f).coerceAtLeast(0f)
                    ),
                    cornerRadius = CornerRadius(radiusPx),
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
