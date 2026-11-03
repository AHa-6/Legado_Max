package io.legado.app.ui.book.read

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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

/** 面板最大宽度：popup 是 WRAP_CONTENT，不给上界时备注文本会把菜单撑到整屏宽 */
internal val PanelMaxWidth = 300.dp

/** 面板最小宽度：太窄时样式条的五个色块会挤成一团 */
internal val PanelMinWidth = 220.dp

/** 样式条宽度，[TextActionMenu] 估算 popup 居中位置时用同一个值 */
internal val HighlightStyleBarWidth = 268.dp

/** 样式条高度：与第一层菜单项的视觉重量对齐，不要比菜单还高 */
private val StyleBarHeight = 34.dp

/** 样式 / 备注按钮的点击区边长 */
private val StyleChipSize = 30.dp

/** 面板各段之间的竖向间距：第一层菜单、样式条、备注摘要、备注输入框共用一个口径 */
internal val HighlightSectionGap = 7.dp

/** 备注输入框与样式条之间多留一点，避免输入框贴着色块 */
internal val HighlightNoteEditorGap = 9.dp

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
            .height(StyleBarHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
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
        Spacer(Modifier.width(5.dp))
        // 颜色条吃掉剩余宽度：五个色块均分，以后要加色块也不会撑破这一行
        Row(
            modifier = Modifier
                .weight(1f)
                .height(26.dp)
                .clip(RoundedCornerShape(13.dp))
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
            .clip(RoundedCornerShape(8.dp))
            .background(accentColor.copy(alpha = 0.07f))
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.note_content),
                onClick = onClick,
            )
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_check),
            contentDescription = null,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(12.dp),
            tint = accentColor,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = note,
            modifier = Modifier.weight(1f),
            color = contentColor.copy(alpha = 0.86f),
            fontSize = 12.sp,
            lineHeight = 17.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 备注输入框。
 *
 * ## 输入法为什么以前弹不出来
 * 宿主 popup 是非 focusable 窗口（`isFocusable = false` + `INPUT_METHOD_NOT_NEEDED`），
 * 展开输入框时宿主会先把窗口切成 focusable 再放行输入法，但**输入框自己从没请求过焦点**：
 * 没有焦点就没有 InputConnection，输入法不会弹；同样因为没有焦点，
 * 长按输入框也唤不出系统的复制 / 全选浮条。
 *
 * 这里补上 `requestFocus()` + `keyboardController.show()`，并在下一帧重试一次 show：
 * 窗口参数（focusable / inputMethodMode）是在同一次点击里刚 update 的，
 * 个别输入法在参数生效前收到 show 会把它忽略掉。
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
        keyboardController?.show()
    }

    // 收起输入框前先把焦点与键盘交回去：宿主要把窗口切回非 focusable，
    // 焦点还挂在输入框上时切窗口会让输入法卡在半收起状态。
    val finishEditing = {
        focusManager.clearFocus()
        keyboardController?.hide()
        onDone()
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .widthIn(min = PanelMinWidth, max = PanelMaxWidth)
            .heightIn(min = 76.dp, max = 150.dp)
            .focusRequester(focusRequester),
        placeholder = {
            Text(
                text = stringResource(R.string.note_content),
                fontSize = 13.sp,
                color = contentColor.copy(alpha = 0.42f),
            )
        },
        trailingIcon = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.14f))
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.ok),
                        onClick = finishEditing,
                    ),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = stringResource(R.string.ok),
                    modifier = Modifier.size(16.dp),
                    tint = accentColor,
                )
            }
        },
        textStyle = MaterialTheme.typography.bodySmall.copy(color = contentColor),
        maxLines = 5,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { finishEditing() }),
        // 光标色走 colors 而不是 cursorBrush：OutlinedTextField 没有 cursorBrush 这个参数，
        // 它是 BasicTextField 的口子。
        colors = OutlinedTextFieldDefaults.colors(
            cursorColor = accentColor,
            focusedBorderColor = accentColor,
            unfocusedBorderColor = contentColor.copy(alpha = 0.22f),
            focusedContainerColor = accentColor.copy(alpha = 0.05f),
            unfocusedContainerColor = Color.Transparent,
        ),
    )
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
            .size(StyleChipSize)
            .clip(RoundedCornerShape(9.dp))
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
            .size(StyleChipSize)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) accentColor.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            ),
    ) {
        Canvas(modifier = Modifier.size(18.dp)) {
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
            .size(22.dp)
            .clip(CircleShape)
            .clickable(
                role = Role.RadioButton,
                onClickLabel = contentDescription,
                onClick = onClick,
            ),
    ) {
        if (selected) {
            Canvas(modifier = Modifier.size(20.dp)) {
                drawCircle(
                    color = accentColor,
                    style = Stroke(width = 1.2.dp.toPx()),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(if (selected) 13.dp else 11.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}