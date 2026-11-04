package io.legado.app.ui.widget.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.R
import io.legado.app.ui.theme.AppDimens
import io.legado.app.ui.theme.pageTopBarBackground
import io.legado.app.ui.theme.pageTopBarCardBackground
import io.legado.app.ui.theme.pageTopBarColors

/**
 * 页面统一顶栏（theme-styles.md §7.7.1 顶栏规范）
 *
 * 收敛各页面 TopAppBar 的重复配色样板：
 * - 背景/阴影/圆角/壁纸统一由 [pageTopBarBackground] 承载，配色源 [pageTopBarColors]（同 TopBarConfig 公式）
 * - 导航/标题/操作图标统一用 [io.legado.app.ui.theme.PageTopBarColors.contentColor]
 * - 标题用 typography.titleLarge + Medium 字重，副标题用 bodySmall
 *
 * @param title 标题文案（必须已资源化）
 * @param onBackClick 返回键点击
 * @param modifier Modifier
 * @param subtitle 副标题（可选，单行省略）
 * @param backIcon 返回图标（默认 ArrowBack，选择态等场景可换 Close）
 * @param backContentDescription 返回键无障碍描述（默认"返回"）
 * @param containerColor 容器色（默认透明；背景统一由 [pageTopBarBackground] 承载，仅连体场景可覆写）
 * @param showBackground true（默认）时由组件自身承载完整背景（阴影/圆角/壁纸）；false 时交由外层容器承载（连体场景）
 * @param scrollBehavior 滚动收起行为（默认 null；需要上滑收起/隐藏顶栏时传入 enterAlwaysScrollBehavior() 等）
 * @param actions 右侧操作区
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPageTopBar(
    title: String,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    backIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack,
    backContentDescription: String? = null,
    containerColor: Color = Color.Transparent,
    showBackground: Boolean = true,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val colors = pageTopBarColors()
    val barModifier = if (showBackground) {
        modifier.pageTopBarBackground(colors)
    } else {
        modifier
    }
    TopAppBar(
        modifier = barModifier,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = containerColor,
            scrolledContainerColor = containerColor,
            navigationIconContentColor = colors.contentColor,
            titleContentColor = colors.contentColor,
            actionIconContentColor = colors.contentColor
        ),
        scrollBehavior = scrollBehavior,
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(
                    backIcon,
                    contentDescription = backContentDescription
                        ?: stringResource(R.string.back)
                )
            }
        },
        actions = actions
    )
}

// ── 预览（§10.1 强制）────────────────────────────────────────

/**
 * 页面紧凑顶栏（悬浮卡片形态，[AppPageTopBar] 的可选形态）。
 *
 * 与 [AppPageTopBar] 的差异只在几何，配色仍走 [pageTopBarColors] / [pageTopBarCardBackground]，
 * 因此壁纸、夜间、透明导航栏、墨水屏等全部配置照常生效：
 * - 卡片形态：左右内缩、四角圆角、与状态栏留出一段空隙，视觉上比满宽顶栏窄一圈；
 * - 行高 52dp（[AppDimens.compactTopBarHeight]，少于满宽顶栏的 56dp）；
 * - 标题居中、单行省略，字号降一档到 typography.titleMedium；
 * - 图标按钮命中区仍是 48dp（accessibility.md §15.3 强制下限），紧凑只从字号、内缩、留白上省。
 *
 * 不改 [AppPageTopBar] 默认行为的原因：它有二十多个调用点，收窄只能做成新增可选形态，否则全体页面跟着变脸。
 *
 * @param modifier Modifier（structure.md §3.1：永远是第一个参数）
 * @param title 标题文案（必须已资源化）
 * @param onBackClick 返回键点击
 * @param backIcon 返回图标（默认 ArrowBack）
 * @param backContentDescription 返回键无障碍描述（默认“返回”）
 * @param actions 右侧操作区
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPageCompactTopBar(
    modifier: Modifier = Modifier,
    title: String,
    onBackClick: () -> Unit,
    backIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack,
    backContentDescription: String? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val colors = pageTopBarColors()
    // 外层先贴状态栏，再给卡片留出四周空隙；内层才是卡片本体
    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(
                start = AppDimens.compactTopBarOuterHorizontal,
                top = AppDimens.compactTopBarOuterTop,
                end = AppDimens.compactTopBarOuterHorizontal,
                bottom = AppDimens.compactTopBarOuterBottom,
            ),
    ) {
        // LocalContentColor 与 Material3 TopAppBar 同语义：调用点的 IconButton / 图标 /
        // 转圈不必各自取色，避免顶栏内出现第二种前景色（theme-styles.md §7.7.4）
        CompositionLocalProvider(LocalContentColor provides colors.contentColor) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(AppDimens.compactTopBarHeight)
                    .pageTopBarCardBackground(colors),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier.size(AppDimens.compactTopBarIconTouchSize),
                ) {
                    Icon(
                        backIcon,
                        contentDescription = backContentDescription
                            ?: stringResource(R.string.back),
                    )
                }
                Text(
                    text = title,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = AppDimens.compactTopBarTitleSpacing),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    content = actions,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPageTopBarPreview() {
    MaterialTheme {
        AppPageTopBar(
            title = "存储管理",
            subtitle = "共 5 项缓存 · 128.5 MB",
            onBackClick = {}
        )
    }
}

@Preview(name = "With actions", showBackground = true)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPageTopBarWithActionsPreview() {
    MaterialTheme {
        AppPageTopBar(
            title = "存储管理",
            onBackClick = {},
            actions = {
                IconButton(onClick = {}) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = null
                    )
                }
            }
        )
    }
}