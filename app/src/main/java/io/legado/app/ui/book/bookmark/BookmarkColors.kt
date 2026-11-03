package io.legado.app.ui.book.bookmark

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import io.legado.app.ui.theme.pageAccentColor
import io.legado.app.ui.theme.pageHeaderContainerColor
import io.legado.app.ui.theme.pageMutedIconTint
import io.legado.app.ui.theme.pageSecondaryTextColor

/**
 * 书签页配色。
 *
 * 一律转调 CommonPageColors 的页面级函数，不自己调 MaterialTheme：
 * 深色背景（luminance < 0.18）下那批函数会把次要色往亮处推、把卡片色压成不透明，
 * 各页面共用同一口径才不会出现"阅读记录页看得清、书签页发灰"的割裂。
 * 与 ReadRecordColors 保持同构，方便后续统一收口。
 */

/**
 * 时间线卡片容器色。
 *
 * 不再走 pageCardElevatedContainerColor：它在浅色下取 `surface.copy(alpha = 0.95f)`，
 * 而本仓多套浅色主题的 background 与 surface 只差 1/255（如「白榆·大寒」是
 * #fefefe / #fefdfd），0.95 的半透明叠回背景后两者色差不到 2/255，卡片整块隐形，
 * 用户看到的就是"一片白底上飘着文字"。
 *
 * 这里改成从 background 出发做**有向偏移**：浅色主题把卡片往 onSurface 方向压暗
 * （纸面上的浅灰卡），深色主题往 onSurface 方向提亮（夜里的微亮卡）。
 * 两种方向都保证卡片与页面背景之间有一段肉眼可辨的明度差，且与具体主题的
 * surface 取值无关——主题换色板不影响卡片可见性。
 *
 * 偏移量刻意保持很小（浅色 0.05 / 深色 0.06）：书签页是长列表，卡片一多，
 * 每块都重一点就会糊成灰墙。可见性主要交给 [bookmarkCardBorder] 的发丝描边。
 */
@Composable
fun bookmarkCardContainerColor(): Color {
    val background = MaterialTheme.colorScheme.background
    val onSurface = MaterialTheme.colorScheme.onSurface
    return if (background.luminance() < 0.18f) {
        lerp(background, onSurface, 0.06f)
    } else {
        lerp(background, onSurface, 0.05f)
    }
}

/** 分组头（书名条）容器色 */
@Composable
fun bookmarkHeaderContainerColor() = pageHeaderContainerColor()

/** 次要文字：作者、位置、计数 */
@Composable
fun bookmarkSecondaryTextColor() = pageSecondaryTextColor()

/** 强调色：书名、笔记、时间线节点 */
@Composable
fun bookmarkAccentColor() = pageAccentColor()

/** 弱化图标色 */
@Composable
fun bookmarkMutedIconTint() = pageMutedIconTint()

/**
 * 卡片描边：**昼夜都描**，不再只在深色下描。
 *
 * 原来浅色下返回 null，理由是"浅色卡片本身比背景亮，再加边框反而脏"。这个前提在
 * 本仓的浅色主题上不成立：多套主题（「白榆·大寒」「白榆·山燃花色」等）把
 * background 与 surface 都设成近白且只差 1/255，卡片与背景之间没有任何亮度差，
 * 不描边就完全没有边界，用户看到的就是纯白背景上飘着文字。
 *
 * 浅色下用 outline 的 18% 而不是深色下的 42%：浅底对同样的灰线更敏感，
 * 42% 会把列表切成硬格子；18% 刚好只勾出轮廓。
 *
 * @param selected 批量管理模式下是否被选中，见 [bookmarkCardSelectedBorder]
 */
@Composable
fun bookmarkCardBorder(selected: Boolean = false): BorderStroke? {
    if (selected) return bookmarkCardSelectedBorder()
    val background = MaterialTheme.colorScheme.background
    return BorderStroke(
        width = 1.dp,
        color = MaterialTheme.colorScheme.outline.copy(
            alpha = if (background.luminance() < 0.18f) 0.42f else 0.18f
        )
    )
}

/**
 * 批量管理下选中态的描边：强调色 + 1.5dp，与 [bookmarkCardBorder] 的发丝灰边区分开。
 *
 * 选中态靠"容器色变化"是表达不出来的——本仓浅色主题下卡片色与背景色本就极近，
 * 再叠一层强调色的低透明度只会两者都看不清。描边换成强调色是最省事的判据，
 * 左侧色条由列表页另行绘制。
 */
@Composable
fun bookmarkCardSelectedBorder(): BorderStroke {
    return BorderStroke(width = 1.5.dp, color = pageAccentColor().copy(alpha = 0.85f))
}