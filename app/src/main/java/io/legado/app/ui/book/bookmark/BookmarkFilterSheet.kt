package io.legado.app.ui.book.bookmark

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.entities.Bookmark
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet

/**
 * 筛选面板。
 *
 * NG 用的是挂在顶栏按钮下的自定义 Popup（`AllBookmarkFilterMenu`，自带滑入动画与
 * 位置提供者）。这里换成 [AppModalBottomSheet]：Max 没有那套 Popup 基建，而底部弹窗
 * 已经是本仓所有"多段筛选"的既有形态（发现分类选择、主页模块管理都是它），
 * 手机上书籍列表也更放得开。
 *
 * 三段条件（书籍 / 笔记 / 颜色）互不影响，改任一段立即回调，页面侧不做"应用"按钮。
 */
@Composable
internal fun BookmarkFilterSheet(
    show: Boolean,
    onDismissRequest: () -> Unit,
    filter: AllBookmarkFilter,
    collection: AllBookmarkCollection,
    onFilterChanged: (AllBookmarkFilter) -> Unit,
) {
    val accent = bookmarkAccentColor()
    val secondary = bookmarkSecondaryTextColor()
    val selectedBook = remember(collection.books, filter.bookKey) {
        collection.books.firstOrNull { it.key == filter.bookKey }
    }

    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.all_bookmark_filter_title),
        endAction = {
            Text(
                text = stringResource(R.string.all_bookmark_count, collection.matchCount),
                color = secondary,
                fontSize = 12.sp,
                maxLines = 1,
            )
        },
    ) {
        BookmarkFilterSectionLabel(stringResource(R.string.all_bookmark_filter_books))
        BookmarkFilterBookRow(
            label = when {
                filter.bookKey == null -> stringResource(R.string.all_bookmark_filter_books)
                selectedBook != null -> selectedBook.bookName
                // 条件来自外部（例如以后支持从书架带书名进来）但选项列表里已没有这本书
                else -> stringResource(R.string.all_bookmark_filter_selected_book)
            },
            selected = filter.bookKey != null,
            books = collection.books,
            selectedKey = filter.bookKey,
            onBookSelected = { key ->
                onFilterChanged(filter.copy(bookKey = key))
            },
        )

        Spacer(Modifier.height(14.dp))
        BookmarkFilterSectionLabel(stringResource(R.string.note_content))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BookmarkNoteFilter.entries.forEach { noteFilter ->
                val selected = filter.noteFilter == noteFilter
                BookmarkFilterChip(
                    label = stringResource(
                        when (noteFilter) {
                            BookmarkNoteFilter.ALL -> R.string.all
                            BookmarkNoteFilter.WITH_NOTE -> R.string.all_bookmark_filter_with_note
                            BookmarkNoteFilter.WITHOUT_NOTE -> R.string.all_bookmark_filter_without_note
                        }
                    ),
                    selected = selected,
                    modifier = Modifier.weight(1f),
                    onClick = { onFilterChanged(filter.copy(noteFilter = noteFilter)) },
                )
            }
        }

        // 颜色段只在该书确实用过文字高亮时出现，否则是一排永远选不中的色块
        if (collection.colors.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            BookmarkFilterSectionLabel(stringResource(R.string.all_bookmark_filter_color))
            BookmarkFilterColorRow(
                colors = collection.colors,
                selectedColor = filter.color,
                onSelect = { onFilterChanged(filter.copy(color = it)) },
            )
        }

        if (filter.isActive) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button) {
                        onFilterChanged(AllBookmarkFilter())
                        onDismissRequest()
                    }
                    .heightIn(min = 46.dp)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.all_bookmark_filter_clear),
                    color = accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        // 面板本身可滚动，末尾留一点呼吸位，避免最后一项贴到导航条
        Spacer(Modifier.height(4.dp))
    }
}

/** 段标题：小号次要色，与内容拉开层级 */
@Composable
private fun BookmarkFilterSectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(bottom = 8.dp, start = 2.dp),
        color = bookmarkSecondaryTextColor(),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    )
}

/** 书籍选择：收起时是一行摘要，展开时是限高列表（面板外层已在滚动，这里必须自己限高） */
@Composable
private fun BookmarkFilterBookRow(
    label: String,
    selected: Boolean,
    books: List<BookmarkBookOption>,
    selectedKey: String?,
    onBookSelected: (String?) -> Unit,
) {
    val accent = bookmarkAccentColor()
    val secondary = bookmarkSecondaryTextColor()
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    var expanded by rememberSaveable { mutableStateOf(false) }
    val expandedDescription = stringResource(
        if (expanded) R.string.all_bookmark_expanded else R.string.all_bookmark_collapsed
    )

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = expandedDescription }
                .heightIn(min = 44.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(if (expanded) 180f else 0f),
                tint = secondary,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 264.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, outlineColor, RoundedCornerShape(10.dp))
                    .padding(4.dp)
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item(key = "all-books", contentType = "bookmark-filter-book") {
                    BookmarkFilterBookOption(
                        name = stringResource(R.string.all_bookmark_filter_books),
                        author = null,
                        count = null,
                        selected = selectedKey == null,
                        onClick = {
                            onBookSelected(null)
                            expanded = false
                        },
                    )
                }
                items(
                    items = books,
                    key = { "book:${it.key}" },
                    contentType = { "bookmark-filter-book" },
                ) { book ->
                    BookmarkFilterBookOption(
                        name = book.bookName,
                        author = book.bookAuthor,
                        count = book.count,
                        selected = selectedKey == book.key,
                        onClick = {
                            onBookSelected(book.key)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/** 书籍选项：书名 + 作者，右侧条数 */
@Composable
private fun BookmarkFilterBookOption(
    name: String,
    author: String?,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = bookmarkAccentColor()
    val secondary = bookmarkSecondaryTextColor()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) accent.copy(alpha = 0.14f) else Color.Transparent
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!author.isNullOrBlank()) {
                Text(
                    text = author,
                    color = secondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (count != null) {
            Text(
                text = count.toString(),
                color = secondary.copy(alpha = 0.8f),
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}

/** 笔记维度的单选块 */
@Composable
private fun BookmarkFilterChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accent = bookmarkAccentColor()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) accent.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 颜色段：首位是"不限"（描边空心圈），后面是实际用到的高亮色。
 *
 * 只列 [Bookmark.HIGHLIGHT_COLORS] 里出现过的颜色时可能一个都不剩（全是位置书签），
 * 那种情况调用方直接不渲染本段。
 */
@Composable
private fun BookmarkFilterColorRow(
    colors: List<Int>,
    selectedColor: Int?,
    onSelect: (Int?) -> Unit,
) {
    val accent = bookmarkAccentColor()
    val outlineColor = MaterialTheme.colorScheme.outline
    // 编辑面板固定给全五色，筛选面板只给出现过的，两者顺序保持一致
    val orderedColors = remember(colors) {
        Bookmark.HIGHLIGHT_COLORS.filter { it in colors } + colors.filterNot { it in Bookmark.HIGHLIGHT_COLORS }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val anyLabel = stringResource(R.string.all)
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .border(
                    width = if (selectedColor == null) 2.dp else 1.dp,
                    color = if (selectedColor == null) accent else outlineColor,
                    shape = CircleShape,
                )
                .selectable(
                    selected = selectedColor == null,
                    role = Role.RadioButton,
                    onClick = { onSelect(null) },
                )
                .semantics { contentDescription = anyLabel },
            contentAlignment = Alignment.Center,
        ) {
            if (selectedColor == null) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = accent,
                )
            }
        }
        orderedColors.forEach { color ->
            val selected = selectedColor == color
            val description = stringResource(
                R.string.all_bookmark_filter_color_value,
                String.format("#%06X", 0xFFFFFF and color),
            )
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(color))
                    .then(
                        if (selected) {
                            Modifier.border(2.dp, accent, CircleShape)
                        } else {
                            Modifier
                        }
                    )
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onSelect(if (selected) null else color) },
                    )
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        // 浅色块上黑勾、深色块上白勾，靠亮度判断比固定白色稳
                        tint = if (Color(color).luminance() > 0.55f) {
                            Color.Black.copy(alpha = 0.82f)
                        } else {
                            Color.White
                        },
                    )
                }
            }
        }
        Spacer(Modifier.width(2.dp))
    }
}
