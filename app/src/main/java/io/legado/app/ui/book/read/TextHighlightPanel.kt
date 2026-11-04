package io.legado.app.ui.book.read

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.ui.theme.AppDimens
import io.legado.app.ui.theme.composeActionShape
import io.legado.app.data.entities.Bookmark
import kotlinx.coroutines.delay

/**
 * 划线操作区（挂在 [TextActionMenu] **第一层菜单下方**）。
 *
 * ## 为什么不再是「整卡替换」
 * 旧实现里点击已有划线会把整张菜单卡片换成一个编辑面板（备注 / 样式 / 颜色 / 删除），
 * 第一层的复制、朗读、搜索等条目全部消失，看起来就像弹出了「书签编辑页」。
 * 现在改成：第一层菜单始终在，高亮态只把「书签」槽位换成「删除划线」，
 * 再在第一层**下面**追加这一条操作区。一次点击就能同时看到「这段文字能做什么」
 * 和「这条划线长什么样」，不必再走「第一层菜单 → 第二层编辑」两步。
 *
 * ## 三个部分
 * - [HighlightStyleBar] 一行紧凑工具条：备注开关 + 三种样式 + 五个颜色；
 * - [HighlightNoteSummary] 这条划线已有备注时的摘要行，点它直接进编辑；
 * - [HighlightNoteEditor] 备注输入框，出现即拉起输入法。
 *
 * 样式与颜色不再复用书签弹窗里的 BookmarkStyleRow / BookmarkDialogColorRow：
 * 那两个是「标签 + 大块选项」的弹窗排版，放进只有一行高的菜单条里会把菜单撑到遮住正文。
 * 这里改成自绘的小图标，语义相同、占位只有三分之一。
 */

// 尺寸一律取 AppDimens（theme-styles.md §7.2）：面板与样式条的几何值原先在这里定义，
// 其中样式条宽度还被 TextActionMenu 抄了一份字面量去估算 popup 居中位置，
// 改一处漏另一处就会让面板固定偏一半。现在两边指向同一个令牌。

/** 面板最大宽度：popup 是 WRAP_CONTENT，不给上界时备注文本会把菜单撑到整屏宽 */
internal val PanelMaxWidth = AppDimens.highlightPanelMaxWidth

/** 面板最小宽度：太窄时样式条的五个色块会挤成一团 */
internal val PanelMinWidth = AppDimens.highlightPanelMinWidth

/** 样式条宽度，[TextActionMenu] 估算 popup 居中位置时用同一个值 */
internal val HighlightStyleBarWidth = AppDimens.highlightStyleBarWidth

/** 面板各段之间的竖向间距：第一层菜单、样式条、备注摘要、备注输入框共用一个口径 */
internal val HighlightSectionGap = AppDimens.highlightSectionGap

/** 备注输入框与样式条之间多留一点，避免输入框贴着色块 */
internal val HighlightNoteEditorGap = AppDimens.highlightNoteEditorGap

/**
 * 一行紧凑工具条：备注开关 + 三种高亮样式 + 五个颜色。
 *
 * @param noteEditorVisible 备注输入框是否已展开，展开时备注按钮高亮
 * @param noteDraft 备注草稿，用来决定备注按钮是否显示「已有备注」的强调色
 * @param onNoteEditorVisibleChange 切换备注输入框；收起时由宿主提交草稿
 */
@Composable
internal fun HighlightStyleBar(
    highlight: Bookmark,
    noteEditorVisible: Boolean,
    noteDraft: String,
    accentColor: Color,
    contentColor: Color,
    onNoteEditorVisibleChange: (Boolean) -> Unit,
    onStyleChange: (Int) -> Unit,
    onColorChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlightColor = Color(highlight.highlightColor)
    Row(
        modifier = modifier
            .width(HighlightStyleBarWidth)
            .height(AppDimens.highlightStyleBarHeight),
        verticalAlignment = Alignment.CenterVertically,
        // 旧值 3dp：四个方形按钮 + 颜色条挤在 268dp 里，点起来像一排连体键（第 4 项）
        horizontalArrangement = Arrangement.spacedBy(AppDimens.highlightChipSpacing),
    ) {
        HighlightNoteButton(
            expanded = noteEditorVisible,
            hasNote = noteDraft.isNotBlank(),
            accentColor = accentColor,
            contentColor = contentColor,
            onClick = { onNoteEditorVisibleChange(!noteEditorVisible) },
        )
        HighlightStyleChip(
            style = Bookmark.STYLE_BACKGROUND,
            selected = highlight.highlightStyle == Bookmark.STYLE_BACKGROUND,
            highlightColor = highlightColor,
            accentColor = accentColor,
            contentColor = contentColor,
            contentDescription = stringResource(R.string.bookmark_style_background),
            onClick = { onStyleChange(Bookmark.STYLE_BACKGROUND) },
        )
        HighlightStyleChip(
            style = Bookmark.STYLE_UNDERLINE,
            selected = highlight.highlightStyle == Bookmark.STYLE_UNDERLINE,
            highlightColor = highlightColor,
            accentColor = accentColor,
            contentColor = contentColor,
            contentDescription = stringResource(R.string.bookmark_style_underline),
            onClick = { onStyleChange(Bookmark.STYLE_UNDERLINE) },
        )
        HighlightStyleChip(
            style = Bookmark.STYLE_WAVY_UNDERLINE,
            selected = highlight.highlightStyle == Bookmark.STYLE_WAVY_UNDERLINE,
            highlightColor = highlightColor,
            accentColor = accentColor,
            contentColor = contentColor,
            contentDescription = stringResource(R.string.bookmark_style_wavy),
            onClick = { onStyleChange(Bookmark.STYLE_WAVY_UNDERLINE) },
        )
        Spacer(Modifier.width(AppDimens.highlightChipColorGap))
        // 颜色条吃掉剩余宽度：五个色块均分，以后要加色块也不会撑破这一行
        Row(
            modifier = Modifier
                .weight(1f)
                .height(AppDimens.highlightColorBarHeight)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = 0.07f)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Bookmark.HIGHLIGHT_COLORS.forEach { value ->
                HighlightColorDot(
                    color = Color(value),
                    selected = highlight.highlightColor == value,
                    accentColor = accentColor,
                    // 与书签弹窗同一个口径：描述里给十六进制色值。
                    // 这里不能用序号——all_bookmark_filter_color_value 是 %1$s，传 Int 会直接崩
                    contentDescription = stringResource(
                        R.string.all_bookmark_filter_color_value,
                        String.format("#%06X", 0xFFFFFF and value),
                    ),
                    onClick = { onColorChange(value) },
                )
            }
        }
    }
}

/**
 * 已有备注时的摘要行。
 *
 * 旧实现用「备注预览态」把整张卡片换成一段备注文本，代价是第一层菜单不见了。
 * 现在只在样式条下面补一行摘要：菜单该有的功能一个不少，备注内容也看得见，
 * 点这一行直接进编辑。
 */
@Composable
internal fun HighlightNoteSummary(
    note: String,
    accentColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .widthIn(min = PanelMinWidth, max = PanelMaxWidth)
            .clip(composeActionShape())
            .background(accentColor.copy(alpha = 0.07f))
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.note_content),
                onClick = onClick,
            )
            .padding(
                horizontal = AppDimens.highlightNoteSummaryHorizontalPadding,
                vertical = AppDimens.highlightNoteSummaryVerticalPadding,
            ),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_check),
            contentDescription = null,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(AppDimens.highlightNoteSummaryIconSize),
            tint = accentColor,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = note,
            modifier = Modifier.weight(1f),
            color = contentColor.copy(alpha = 0.86f),
            // 字号走 typography 档位（§7.4），不再裸写 12.sp
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 17.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 备注输入框。
 *
 * ## 形态（第 3 项：原文案是「文本框难看」）
 * 旧实现是一个 `OutlinedTextField`：Material 默认的 56dp 高方框 + 四角细描边 +
 * 右上角塞一颗圆形确认键，且高度随 `heightIn(min = 76.dp)` 撑到 76dp，
 * 放在 8dp 内边距的菜单卡片里像一块贴上去的表单。现在按 NG 的形态重做：
 *
 * - 顶部一个 14×8dp 的三角指针，指向被划线的那行字——一行 `Canvas` 画出来，
 *   视觉上把「这个框属于上面那段文字」讲清楚，这是旧版完全没有的；
 * - 框体是 52dp 高的圆角卡片（[AppDimens.highlightNoteCardHeight]），
 *   用 [composePanelShape] 与菜单卡片同一套圆角，内边距 12dp；
 * - 内部是 [BasicTextField] + 自绘占位符，字号取 typography.bodyMedium（14sp）
 *   配 18sp 行高、最多两行——旧版 maxLines = 5 会把菜单顶到遮住正文；
 * - 确认键是 36dp 圆形填色钮 + 20dp 对勾，右下角对齐，替代 `trailingIcon`；
 * - 焦点色、光标色、占位符透明度统一走 `accentColor`，深浅色主题一致。
 *
 * ## 输入法为什么以前弹不出来
 * 宿主 popup 是非 focusable 窗口，展开输入框时宿主会把窗口切成 focusable 再放行输入法，
 * 但**输入框自己从没请求过焦点**：没有焦点就没有 InputConnection，输入法不会弹。
 * 本函数用 `requestFocus()` + `keyboardController.show()`，并在 60ms 后重试一次 show
 * （窗口参数在同一次点击里刚 update，个别输入法在生效前收到 show 会忽略）。
 * 另一条链路在窗口级：[TextActionMenu] 的 `inputMethodMode` / `softInputMode`
 * 改为构造时即 NEEDED + ADJUST_RESIZE，与 NG 一致。
 *
 * @param onDone 完成编辑：收起输入框与键盘，草稿由宿主提交
 */
@Composable
internal fun HighlightNoteEditor(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    accentColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
        delay(60)
        // 窗口级 inputMethodMode 是 last-click 里刚 update 的，个别输入法会忽略
        // 参数生效前的那一次 show，所以下一拍再要一次
        keyboardController?.show()
    }

    // 收起输入框前先把焦点与键盘交回去：宿主要把窗口切回非 focusable，
    // 焦点还挂在输入框上时切窗口会让输入法卡在半收起状态。
    val finishEditing = {
        focusManager.clearFocus()
        keyboardController?.hide()
        onDone()
    }

    Column(
        modifier = modifier.widthIn(min = PanelMinWidth, max = PanelMaxWidth),
        horizontalAlignment = Alignment.Start,
    ) {
        // 顶部指针：指着被划线的那行字，而不是让输入框凭空悬在菜单里
        Canvas(
            modifier = Modifier
                .padding(start = AppDimens.highlightNotePointerInset)
                .size(
                    width = AppDimens.highlightNotePointerWidth,
                    height = AppDimens.highlightNotePointerHeight,
                ),
        ) {
            val path = Path().apply {
                moveTo(0f, size.height)
                lineTo(size.width, size.height)
                lineTo(size.width / 2f, 0f)
                close()
            }
            drawPath(path = path, color = accentColor.copy(alpha = 0.16f))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = AppDimens.highlightNoteCardHeight)
                .heightIn(max = AppDimens.highlightNoteEditorMaxHeight)
                .clip(composeActionShape())
                .background(accentColor.copy(alpha = 0.10f))
                .border(
                    width = AppDimens.highlightNoteCardBorderWidth,
                    color = accentColor.copy(alpha = 0.32f),
                    shape = composeActionShape(),
                )
                .padding(horizontal = AppDimens.highlightNoteCardPadding, vertical = 0.dp),
        ) {
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.weight(1f),
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = stringResource(R.string.note_content),
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor.copy(alpha = 0.40f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = contentColor,
                        lineHeight = 18.sp,
                    ),
                    cursorBrush = SolidColor(accentColor),
                    maxLines = 2,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { finishEditing() }),
                )
            }
            Spacer(Modifier.width(AppDimens.highlightChipSpacing))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(AppDimens.highlightNoteConfirmSize)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.18f))
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.ok),
                        onClick = finishEditing,
                    ),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = stringResource(R.string.ok),
                    modifier = Modifier.size(AppDimens.highlightNoteConfirmIconSize),
                    tint = accentColor,
                )
            }
        }
    }
}

/**
 * 备注按钮：展开时或已有备注时用强调色，其余情况跟随菜单文字色。
 */
@Composable
private fun HighlightNoteButton(
    expanded: Boolean,
    hasNote: Boolean,
    accentColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    val tint = if (expanded || hasNote) accentColor else contentColor.copy(alpha = 0.78f)
    val description = stringResource(R.string.note_content)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(AppDimens.highlightChipTouchSize)
            // 圆角走 composeActionShape()：与 XML 侧 UiCorner.actionRadius 同源
            .clip(composeActionShape())
            .background(if (expanded) accentColor.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(
                role = Role.Button,
                onClickLabel = description,
                onClick = onClick,
            ),
    ) {
        Icon(
            imageVector = Icons.Default.Edit,
            contentDescription = description,
            modifier = Modifier.size(16.dp),
            tint = tint,
        )
    }
}

/**
 * 高亮样式按钮，图标用 [Canvas] 自绘。
 *
 * 自绘而不是找现成图标：三种样式的区别就在「底色 / 直线 / 波浪线」这一笔上，
 * 现成图标里没有能同时表达「当前高亮颜色」的，而这里必须让用户看到
 * 换样式之后颜色还是原来那个。
 */
@Composable
private fun HighlightStyleChip(
    style: Int,
    selected: Boolean,
    highlightColor: Color,
    accentColor: Color,
    contentColor: Color,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(AppDimens.highlightChipTouchSize)
            .clip(composeActionShape())
            .background(if (selected) accentColor.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            ),
    ) {
        Canvas(modifier = Modifier.size(AppDimens.highlightChipIconSize)) {
            val inkColor = if (selected) accentColor else contentColor.copy(alpha = 0.62f)
            val textStroke = 1.5.dp.toPx()
            val midY = size.height * 0.42f
            val left = size.width * 0.1f
            val right = size.width * 0.9f
            when (style) {
                Bookmark.STYLE_BACKGROUND -> {
                    // 一整块底色盖住「文字」，底色用当前高亮色，选中与否只改透明度
                    drawRoundRect(
                        color = highlightColor.copy(alpha = if (selected) 0.62f else 0.42f),
                        topLeft = Offset(0f, size.height * 0.12f),
                        size = size.copy(height = size.height * 0.76f),
                        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                    )
                    drawLine(
                        color = inkColor,
                        start = Offset(left, midY),
                        end = Offset(right, midY),
                        strokeWidth = textStroke,
                        cap = StrokeCap.Round,
                    )
                }

                Bookmark.STYLE_UNDERLINE -> {
                    drawLine(
                        color = inkColor.copy(alpha = 0.7f),
                        start = Offset(left, midY),
                        end = Offset(right, midY),
                        strokeWidth = textStroke,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = if (selected) accentColor else highlightColor,
                        start = Offset(left, size.height * 0.82f),
                        end = Offset(right, size.height * 0.82f),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }

                else -> {
                    drawLine(
                        color = inkColor.copy(alpha = 0.7f),
                        start = Offset(left, midY),
                        end = Offset(right, midY),
                        strokeWidth = textStroke,
                        cap = StrokeCap.Round,
                    )
                    // 波浪线：四段二次贝塞尔交替上下，振幅固定 1.4dp
                    val waveY = size.height * 0.8f
                    val amplitude = 1.4.dp.toPx()
                    val segment = (right - left) / 4f
                    val path = Path().apply {
                        moveTo(left, waveY)
                        repeat(4) { index ->
                            val startX = left + segment * index
                            val controlY = waveY + if (index % 2 == 0) -amplitude else amplitude
                            quadraticBezierTo(
                                startX + segment / 2f,
                                controlY,
                                startX + segment,
                                waveY,
                            )
                        }
                    }
                    drawPath(
                        path = path,
                        color = if (selected) accentColor else highlightColor,
                        style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }
    }
}

/**
 * 颜色圆点。选中时外圈套一道强调色描边——只靠「变大一点」在 18dp 上看不出来。
 */
@Composable
private fun HighlightColorDot(
    color: Color,
    selected: Boolean,
    accentColor: Color,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(AppDimens.highlightColorDotTouchSize)
            .clip(CircleShape)
            .clickable(
                role = Role.RadioButton,
                onClickLabel = contentDescription,
                onClick = onClick,
            ),
    ) {
        if (selected) {
            Canvas(modifier = Modifier.size(AppDimens.highlightColorDotRingSize)) {
                drawCircle(
                    color = accentColor,
                    style = Stroke(width = AppDimens.highlightColorDotRingWidth.toPx()),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(
                    if (selected) AppDimens.highlightColorDotSizeSelected
                    else AppDimens.highlightColorDotSize
                )
                .clip(CircleShape)
                .background(color)
        )
    }
}