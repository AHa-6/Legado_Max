package io.legado.app.ui.book.bookmark

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import io.legado.app.ui.theme.pageAccentColor
import io.legado.app.ui.theme.pageCardElevatedContainerColor
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

/** 时间线卡片容器色 */
@Composable
fun bookmarkCardContainerColor() = pageCardElevatedContainerColor()

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
 * 卡片描边。
 *
 * 只在深色背景上描一道 42% 的 outline：深色主题下卡片与背景亮度接近，
 * 不描边会糊成一片；浅色背景下卡片本身就比背景亮，再加边框反而脏（与阅读记录页一致）。
 */
@Composable
fun bookmarkCardBorder(): BorderStroke? {
    val background = MaterialTheme.colorScheme.background
    return if (background.luminance() < 0.18f) {
        BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.42f)
        )
    } else {
        null
    }
}