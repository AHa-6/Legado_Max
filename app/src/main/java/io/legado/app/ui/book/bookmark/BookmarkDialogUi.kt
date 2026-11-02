package io.legado.app.ui.book.bookmark

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.entities.Bookmark
import io.legado.app.ui.widget.components.dialog.AppAdaptiveDialog

/**
 * 书签编辑弹窗内容。
 *
 * 用 [AppAdaptiveDialog] 而不是自己搭 Surface：它是本仓 Compose 弹窗的统一外观
 * （extraLarge 圆角、surfaceContainerHigh 底色、按钮右对齐、自带墨水屏灰阶层），
 * 主题编辑、智能标签管理都走它，书签不该是唯一的例外。
 *
 * 原文 / 备注两个输入框沿用旧 View 版的字段（bookText / content），
 * 下面的高亮段只在 [Bookmark.isTextHighlight] 时出现：位置书签没有区间可渲染，
 * 给它色块只会误导。类型切换不在这里做——高亮由阅读页选区生成，
 * 编辑框只改样式，避免用户在列表页把位置书签改成零长度高亮。
 */
@Composable
internal fun BookmarkDialogContent(
    bookmark: Bookmark,
    showDelete: Boolean,
    onCancel: () -> Unit,
    onConfirm: (bookText: String, content: String, highlightStyle: Int, highlightColor: Int) -> Unit,
    onDelete: () -> Unit,
) {
    // 以 time（主键）为 key：换一条书签复用同一 composition 时要重置输入
    var bookText by rememberSaveable(bookmark.time) { mutableStateOf(bookmark.bookText) }
    var note by rememberSaveable(bookmark.time) { mutableStateOf(bookmark.content) }
    var style by rememberSaveable(bookmark.time) { mutableIntStateOf(bookmark.highlightStyle) }
    var color by rememberSaveable(bookmark.time) { mutableIntStateOf(bookmark.highlightColor) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AppAdaptiveDialog(
            title = stringResource(R.string.delete),
            onDismiss = { showDeleteConfirm = false },
            description = stringResource(R.string.all_bookmark_delete_confirm),
            buttons = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) {
                    Text(
                        text = stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
        ) {}
        return
    }

    AppAdaptiveDialog(
        title = stringResource(
            if (bookmark.isTextHighlight) R.string.bookmark_highlight_title else R.string.bookmark
        ),
        onDismiss = onCancel,
        description = bookmark.chapterName.ifBlank { null },
        buttons = {
            if (showDelete) {
                TextButton(onClick = { showDeleteConfirm = true }) {
                    Text(
                        text = stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            // 删除靠左、取消/确认靠右：按钮行本身右对齐，用占位 weight 把删除顶到行首
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
            TextButton(onClick = { onConfirm(bookText, note, style, color) }) {
                Text(stringResource(R.string.ok))
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = bookText,
                onValueChange = { bookText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.content)) },
                minLines = 2,
                maxLines = 6,
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.note_content)) },
                minLines = 2,
                maxLines = 6,
                textStyle = MaterialTheme.typography.bodyMedium,
            )

            if (bookmark.isTextHighlight) {
                Spacer(Modifier.height(16.dp))
                BookmarkDialogSectionLabel(stringResource(R.string.bookmark_highlight_style))
                BookmarkStyleRow(style = style, onStyleSelected = { style = it })
                Spacer(Modifier.height(14.dp))
                BookmarkDialogSectionLabel(stringResource(R.string.bookmark_highlight_color))
                BookmarkDialogColorRow(color = color, onColorSelected = { color = it })
            }
        }
    }
}

@Composable
private fun BookmarkDialogSectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(bottom = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    )
}

/**
 * 高亮风格三选一。
 *
 * 取值与 [Bookmark.STYLE_BACKGROUND] / [Bookmark.STYLE_UNDERLINE] /
 * [Bookmark.STYLE_WAVY_UNDERLINE] 一一对应，排版层再映射成 CharStyle 的
 * bgColor / underlineMode（见 TextChapterLayout 的书签高亮分支）。
 */
@Composable
private fun BookmarkStyleRow(style: Int, onStyleSelected: (Int) -> Unit) {
    val accent = bookmarkAccentColor()
    val options = listOf(
        Bookmark.STYLE_BACKGROUND to R.string.bookmark_style_background,
        Bookmark.STYLE_UNDERLINE to R.string.bookmark_style_underline,
        Bookmark.STYLE_WAVY_UNDERLINE to R.string.bookmark_style_wavy,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, labelRes) ->
            val selected = style == value
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (selected) accent.copy(alpha = 0.16f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
                    )
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onStyleSelected(value) },
                    )
                    .heightIn(min = 42.dp)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(labelRes),
                    color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 高亮颜色：固定给 [Bookmark.HIGHLIGHT_COLORS] 全量五色，必选中其一 */
@Composable
private fun BookmarkDialogColorRow(color: Int, onColorSelected: (Int) -> Unit) {
    val accent = bookmarkAccentColor()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Bookmark.HIGHLIGHT_COLORS.forEach { value ->
            val selected = color == value
            val description = stringResource(
                R.string.all_bookmark_filter_color_value,
                String.format("#%06X", 0xFFFFFF and value),
            )
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(value))
                    .then(
                        if (selected) Modifier.border(2.dp, accent, CircleShape) else Modifier
                    )
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onColorSelected(value) },
                    )
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = if (Color(value).luminance() > 0.55f) {
                            Color.Black.copy(alpha = 0.82f)
                        } else {
                            Color.White
                        },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
    }
}