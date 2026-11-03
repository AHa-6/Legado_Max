package io.legado.app.ui.book.read

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.entities.Bookmark
import io.legado.app.ui.book.bookmark.BookmarkDialogColorRow
import io.legado.app.ui.book.bookmark.BookmarkDialogSectionLabel
import io.legado.app.ui.book.bookmark.BookmarkStyleRow

/**
 * 划线操作面板（[TextActionMenu] 的高亮形态）。
 *
 * 需求：点击已有划线或划线末端的备注标识时，改备注、改样式颜色、删除都在**菜单内**完成，
 * 不再弹 [io.legado.app.ui.book.bookmark.BookmarkDialog]。弹窗会把阅读页压到后面，
 * 一次「改个颜色」要经历 点划线 → 弹窗 → 确认 → 关窗 四步，且看不到改动效果。
 *
 * 形态与 NG 一致：同一个窗口内两态切换，见 [TextHighlightPopupMode]。
 * 样式行 / 颜色行直接复用书签弹窗里的那两个 Composable，保证菜单与弹窗视觉一致，
 * 也避免同一套「三选一 + 五色」出现两份实现。
 */

/**
 * 面板形态。
 *
 * - [TOOLBAR] 完整工具栏：备注输入、样式、颜色、删除都在这一层；
 * - [NOTE_PREVIEW] 备注预览：这条划线已经有备注时先只展示备注，避免菜单一上来就很高、
 *   把用户想看的上下文盖掉；点「编辑」再进 [TOOLBAR]。
 */
internal enum class TextHighlightPopupMode {
    TOOLBAR,
    NOTE_PREVIEW,
}

/** 首次打开时的形态：有备注先给预览，没备注直接给工具栏（NG 同规则） */
internal fun initialTextHighlightPopupMode(textHighlight: Bookmark?): TextHighlightPopupMode {
    return if (textHighlight?.content?.isNotBlank() == true) {
        TextHighlightPopupMode.NOTE_PREVIEW
    } else {
        TextHighlightPopupMode.TOOLBAR
    }
}

/** 面板最大宽度：popup 是 WRAP_CONTENT，不给上界时备注文本会把菜单撑到整屏宽 */
private val PanelMaxWidth = 300.dp

/** 面板最小宽度：样式行是三个等分块，太窄时「波浪线」会被挤成两行 */
private val PanelMinWidth = 220.dp

/**
 * 备注预览态。
 *
 * @param onEdit 打开工具栏并直接展开备注输入框
 * @param onDetail 打开工具栏（不展开输入框），用于改样式颜色 / 删除
 */
@Composable
internal fun HighlightNotePreview(
    note: String,
    contentColor: Color,
    onEdit: () -> Unit,
    onDetail: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(min = PanelMinWidth, max = PanelMaxWidth)
            .padding(2.dp)
    ) {
        Text(
            text = note,
            color = contentColor,
            fontSize = 14.sp,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelActionButton(
                icon = Icons.Default.Edit,
                label = stringResource(R.string.note_content),
                contentColor = contentColor,
                onClick = onEdit,
            )
            Spacer(Modifier.weight(1f))
            PanelActionButton(
                icon = Icons.Default.Settings,
                label = stringResource(R.string.bookmark_highlight_style),
                contentColor = contentColor,
                onClick = onDetail,
            )
            // 关闭只给图标不给文字：这一行已经有两个带文字的按钮，
            // 再塞一个「关闭」会挤到换行。close 用 contentDescription 保证无障碍可读。
            PanelIconButton(
                icon = Icons.Default.Close,
                contentDescription = stringResource(R.string.close),
                contentColor = contentColor,
                onClick = onDismiss,
            )
        }
    }
}

/**
 * 工具栏态：备注 + 样式 + 颜色 + 删除。
 *
 * 备注输入框默认收起，只在 [noteEditorVisible] 时出现。展开时宿主会把 popup 切成
 * focusable 并放行输入法（见 TextActionMenu.setNoteEditorVisible），收起时要提交草稿。
 *
 * @param onNoteDraftChange 草稿变化；提交由 TextActionMenu 在收起 / 关窗时统一做
 * @param onDelete 删除这条划线，不做二次确认——菜单里点「删除」本身就是明确意图，
 *   再加一层确认等于把弹窗又请回来了
 */
@Composable
internal fun HighlightToolbar(
    highlight: Bookmark,
    contentColor: Color,
    noteDraft: String,
    noteEditorVisible: Boolean,
    onNoteEditorVisibleChange: (Boolean) -> Unit,
    onNoteDraftChange: (String) -> Unit,
    onStyleChange: (Int) -> Unit,
    onColorChange: (Int) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(min = PanelMinWidth, max = PanelMaxWidth)
            .padding(2.dp)
    ) {
        // ── 动作行 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelActionButton(
                icon = Icons.Default.Edit,
                label = stringResource(R.string.note_content),
                contentColor = contentColor,
                selected = noteEditorVisible,
                onClick = { onNoteEditorVisibleChange(!noteEditorVisible) },
            )
            Spacer(Modifier.weight(1f))
            PanelActionButton(
                icon = Icons.Default.Delete,
                label = stringResource(R.string.delete),
                contentColor = MaterialTheme.colorScheme.error,
                onClick = onDelete,
            )
            Spacer(Modifier.width(4.dp))
            PanelActionButton(
                icon = Icons.Default.Close,
                label = stringResource(R.string.close),
                contentColor = contentColor,
                onClick = onDismiss,
            )
        }

        if (noteEditorVisible) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = noteDraft,
                onValueChange = onNoteDraftChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 88.dp),
                label = { Text(stringResource(R.string.note_content)) },
                textStyle = MaterialTheme.typography.bodySmall,
                maxLines = 5,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { onNoteEditorVisibleChange(false) }) {
                    Text(stringResource(R.string.ok))
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        BookmarkDialogSectionLabel(stringResource(R.string.bookmark_highlight_style))
        BookmarkStyleRow(
            style = highlight.highlightStyle,
            onStyleSelected = onStyleChange,
        )
        Spacer(Modifier.height(12.dp))
        BookmarkDialogSectionLabel(stringResource(R.string.bookmark_highlight_color))
        BookmarkDialogColorRow(
            color = highlight.highlightColor,
            onColorSelected = onColorChange,
        )
    }
}

/**
 * 面板里的纯图标按钮（关闭一类次要动作）。
 *
 * 尺寸与 [PanelActionButton] 的图标一致，点击区用 28dp 方框保证手指好按。
 */
@Composable
private fun PanelIconButton(
    icon: ImageVector,
    contentDescription: String,
    contentColor: Color,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * 面板里的图标 + 文字按钮。
 *
 * 尺寸口径对齐 [TextActionMenu] 的菜单项（18dp 图标 / 14sp 文字 / 4dp 横向内边距），
 * 让「划线面板」与「文字菜单」看着像同一个东西，而不是塞进菜单里的另一套控件。
 */
@Composable
private fun PanelActionButton(
    icon: ImageVector,
    label: String,
    contentColor: Color,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else contentColor
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (selected) tint.copy(alpha = 0.12f) else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}


