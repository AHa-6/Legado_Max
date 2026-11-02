package io.legado.app.ui.book.bookmark

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.entities.Bookmark
import io.legado.app.lib.theme.eInkGrayscale
import io.legado.app.ui.widget.components.AppPageTopBar
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.AppSearchBar
import io.legado.app.ui.widget.components.navigationBarBottomInset

/**
 * 「所有书签」页。
 *
 * 结构沿用 NG：顶栏（搜索 / 筛选 / 溢出菜单）+ 按书分组的时间线列表。
 * 视觉体系换成 Max 自己的页面级配色（[BookmarkColors]）与脚手架
 * （[AppScaffold] / [AppPageTopBar] / [AppSearchBar] / [AppModalBottomSheet]），
 * NG 的 `NgVisualSurface` / `NgFloatingSearchToolbar` / `NgExpandableActionMenu`
 * 在本仓不存在，也不打算为此引入一套并行的主题层。
 */

/** 页面能发出的全部动作，Activity 侧统一处理（导出、跳转、弹窗） */
internal sealed interface AllBookmarkScreenAction {
    data object Back : AllBookmarkScreenAction
    data object ExportJson : AllBookmarkScreenAction
    data object ExportMarkdown : AllBookmarkScreenAction
    data class QueryChanged(val query: String) : AllBookmarkScreenAction

    /** @param position 该书签在全量列表里的下标，编辑弹窗用它判断是否"可删除" */
    data class Open(val bookmark: Bookmark, val position: Int) : AllBookmarkScreenAction
    data class Edit(val bookmark: Bookmark, val position: Int) : AllBookmarkScreenAction
}

/** 时间线列表项：分组头或书签条目 */
private sealed interface BookmarkTimelineItem {
    /** LazyColumn 的稳定 key */
    val stableKey: String

    data class Header(
        val book: BookmarkBookOption,
        val matchedCount: Int,
        val filtered: Boolean,
        val collapsed: Boolean,
        val firstGroup: Boolean,
    ) : BookmarkTimelineItem {
        override val stableKey: String = book.key
    }

    data class Entry(
        val bookmark: Bookmark,
        val position: Int,
        val firstInGroup: Boolean,
        val lastInGroup: Boolean,
    ) : BookmarkTimelineItem {
        override val stableKey: String = "bookmark:${bookmark.time}"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AllBookmarkScreen(
    bookmarks: List<Bookmark>,
    query: String,
    onAction: (AllBookmarkScreenAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 筛选条件用三个独立字段而不是一个 data class 装进 rememberSaveable：
    // AllBookmarkFilter 是 internal data class，进程重建时 Bundle 里没有它的 Parcelable/Saver
    var selectedBookKey by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedNoteFilter by rememberSaveable { mutableStateOf(BookmarkNoteFilter.ALL) }
    var selectedColor by rememberSaveable { mutableStateOf<Int?>(null) }
    val filter = remember(selectedBookKey, selectedNoteFilter, selectedColor) {
        AllBookmarkFilter(selectedBookKey, selectedNoteFilter, selectedColor)
    }
    val normalizedQuery = query.trim()
    val hasConditions = filter.isActive || normalizedQuery.isNotEmpty()

    // 折叠状态分两套：有条件时的折叠不应污染无条件的视图，清空条件后要回到原样
    var collapsedBookKeys by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var filteredCollapsedBookKeys by rememberSaveable(
        selectedBookKey, selectedNoteFilter, selectedColor, normalizedQuery,
    ) { mutableStateOf(emptyList<String>()) }
    val collapsedKeys = if (hasConditions) filteredCollapsedBookKeys else collapsedBookKeys

    val collection = remember(bookmarks, normalizedQuery, filter) {
        buildAllBookmarkCollection(bookmarks, normalizedQuery, filter)
    }
    val timelineItems = remember(collection.groups, collapsedKeys, hasConditions) {
        buildBookmarkTimelineItems(collection.groups, collapsedKeys.toSet(), hasConditions)
    }

    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showFilterSheet by rememberSaveable { mutableStateOf(false) }
    var showOverflowMenu by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // 条件变化后滚回顶部：分组结构整体换掉，停留在旧偏移会落到不相干的书上
    var previousConditions by remember { mutableStateOf(filter to normalizedQuery) }
    LaunchedEffect(filter, normalizedQuery) {
        val conditions = filter to normalizedQuery
        if (previousConditions != conditions) {
            previousConditions = conditions
            listState.scrollToItem(0)
        }
    }

    fun updateCollapsed(keys: List<String>) {
        if (hasConditions) filteredCollapsedBookKeys = keys else collapsedBookKeys = keys
    }

    fun closeTransientUi() {
        focusManager.clearFocus()
        keyboardController?.hide()
        showOverflowMenu = false
        showFilterSheet = false
    }

    AppScaffold(
        modifier = modifier,
        topBar = {
            AppPageTopBar(
                title = stringResource(R.string.all_bookmark),
                subtitle = stringResource(R.string.all_bookmark_count, bookmarks.size),
                onBackClick = { onAction(AllBookmarkScreenAction.Back) },
                actions = {
                    IconButton(onClick = {
                        showSearch = !showSearch
                        if (!showSearch && normalizedQuery.isNotEmpty()) {
                            onAction(AllBookmarkScreenAction.QueryChanged(""))
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = stringResource(R.string.search),
                        )
                    }
                    // 筛选按钮：条件生效时图标染色 + 右上角红点
                    IconButton(onClick = {
                        closeTransientUi()
                        showFilterSheet = true
                    }) {
                        Box {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = stringResource(
                                    if (filter.isActive) R.string.all_bookmark_filter_active
                                    else R.string.all_bookmark_filter_title
                                ),
                                tint = if (filter.isActive) {
                                    bookmarkAccentColor()
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            if (filter.isActive) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 5.dp, end = 5.dp)
                                        .size(5.dp)
                                        .background(bookmarkAccentColor(), CircleShape)
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = {
                            closeTransientUi()
                            showOverflowMenu = true
                        }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.menu),
                            )
                        }
                        DropdownMenu(
                            modifier = Modifier.eInkGrayscale(),
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                        ) {
                            val allCollapsed = collection.groups.isNotEmpty() &&
                                collection.groups.all { it.book.key in collapsedKeys }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (allCollapsed) R.string.expand_all else R.string.collapse_all
                                        )
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (allCollapsed) {
                                            Icons.Default.ExpandLess
                                        } else {
                                            Icons.Default.ExpandMore
                                        },
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    updateCollapsed(
                                        if (allCollapsed) emptyList()
                                        else collection.groups.map { it.book.key }
                                    )
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export)) },
                                onClick = {
                                    showOverflowMenu = false
                                    onAction(AllBookmarkScreenAction.ExportJson)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_md)) },
                                onClick = {
                                    showOverflowMenu = false
                                    onAction(AllBookmarkScreenAction.ExportMarkdown)
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            AnimatedVisibility(visible = showSearch) {
                AppSearchBar(
                    query = query,
                    onQueryChange = { onAction(AllBookmarkScreenAction.QueryChanged(it)) },
                    hint = stringResource(R.string.all_bookmark_search_hint),
                )
            }
            if (timelineItems.isEmpty()) {
                BookmarkEmptyState(
                    searching = hasConditions,
                    onClearConditions = if (hasConditions) {
                        {
                            selectedBookKey = null
                            selectedNoteFilter = BookmarkNoteFilter.ALL
                            selectedColor = null
                            onAction(AllBookmarkScreenAction.QueryChanged(""))
                        }
                    } else null,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 14.dp,
                        top = 6.dp,
                        end = 14.dp,
                        bottom = 14.dp + navigationBarBottomInset,
                    ),
                ) {
                    items(
                        items = timelineItems,
                        key = BookmarkTimelineItem::stableKey,
                        contentType = {
                            when (it) {
                                is BookmarkTimelineItem.Header -> "bookmark_group"
                                is BookmarkTimelineItem.Entry -> "bookmark_entry"
                            }
                        },
                    ) { item ->
                        when (item) {
                            is BookmarkTimelineItem.Header -> BookmarkBookHeader(item) {
                                updateCollapsed(
                                    if (item.collapsed) collapsedKeys - item.book.key
                                    else collapsedKeys + item.book.key
                                )
                            }

                            is BookmarkTimelineItem.Entry -> BookmarkTimelineCard(
                                item = item,
                                onAction = onAction,
                            )
                        }
                    }
                }
            }
        }
    }

    BookmarkFilterSheet(
        show = showFilterSheet,
        onDismissRequest = { showFilterSheet = false },
        filter = filter,
        collection = collection,
        onFilterChanged = { value ->
            selectedBookKey = value.bookKey
            selectedNoteFilter = value.noteFilter
            selectedColor = value.color
        },
    )
}

/** 分组头：书名 · 作者 + 命中计数 + 折叠箭头 */
@Composable
private fun BookmarkBookHeader(item: BookmarkTimelineItem.Header, onToggle: () -> Unit) {
    val accent = bookmarkAccentColor()
    val secondary = bookmarkSecondaryTextColor()
    val expandedDescription = stringResource(
        if (item.collapsed) R.string.all_bookmark_collapsed else R.string.all_bookmark_expanded
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .semantics { stateDescription = expandedDescription }
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(
                start = 2.dp,
                top = if (item.firstGroup) 4.dp else 12.dp,
                end = 2.dp,
                bottom = 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = bookmarkGroupTitle(
                bookName = item.book.bookName,
                bookAuthor = item.book.bookAuthor,
                primary = accent,
                secondary = secondary,
            ),
            modifier = Modifier.weight(1f),
            fontSize = 16.sp,
            lineHeight = 21.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (item.filtered) {
                stringResource(
                    R.string.all_bookmark_filtered_count,
                    item.matchedCount,
                    item.book.count,
                )
            } else {
                stringResource(R.string.all_bookmark_count, item.book.count)
            },
            color = secondary.copy(alpha = 0.72f),
            fontSize = 11.sp,
            maxLines = 1,
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = if (item.collapsed) {
                Icons.AutoMirrored.Filled.ArrowRight
            } else {
                Icons.Default.ExpandMore
            },
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = secondary.copy(alpha = 0.72f),
        )
    }
}

/** 书名加粗强调色、作者常规次要色，拼成一行 */
private fun bookmarkGroupTitle(
    bookName: String,
    bookAuthor: String,
    primary: Color,
    secondary: Color,
): AnnotatedString = buildAnnotatedString {
    pushStyle(SpanStyle(color = primary, fontWeight = FontWeight.Bold))
    append(bookName)
    pop()
    if (bookAuthor.isNotBlank()) {
        pushStyle(SpanStyle(color = secondary, fontWeight = FontWeight.Normal))
        append(" · ")
        append(bookAuthor)
        pop()
    }
}

/** 书签卡片：左侧时间线导轨 + 右侧内容卡，点击跳转、长按编辑 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkTimelineCard(
    item: BookmarkTimelineItem.Entry,
    onAction: (AllBookmarkScreenAction) -> Unit,
) {
    val bookmark = item.bookmark
    val cardShape = RoundedCornerShape(10.dp)
    val contentColor = MaterialTheme.colorScheme.onSurface
    val mutedColor = bookmarkSecondaryTextColor()
    val accentColor = bookmarkAccentColor()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        BookmarkTimelineRail(
            firstInGroup = item.firstInGroup,
            lastInGroup = item.lastInGroup,
            modifier = Modifier
                .width(30.dp)
                .fillMaxHeight(),
        )
        Surface(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 8.dp)
                .clip(cardShape)
                .combinedClickable(
                    onClick = {
                        onAction(AllBookmarkScreenAction.Open(bookmark, item.position))
                    },
                    onLongClickLabel = stringResource(R.string.edit),
                    onLongClick = {
                        onAction(AllBookmarkScreenAction.Edit(bookmark, item.position))
                    },
                ),
            shape = cardShape,
            color = bookmarkCardContainerColor(),
            contentColor = contentColor,
            border = bookmarkCardBorder(),
            shadowElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.padding(
                    start = 14.dp,
                    top = 11.dp,
                    end = 14.dp,
                    bottom = 10.dp,
                ),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = bookmark.chapterName,
                        modifier = Modifier.weight(1f, fill = false),
                        color = contentColor,
                        fontSize = 16.sp,
                        lineHeight = 21.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 高亮条目在标题右侧挂一个色点，和筛选面板的色块同色
                    if (bookmark.isTextHighlight) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(bookmark.highlightColor))
                        )
                    }
                }
                if (bookmark.bookText.isNotBlank()) {
                    Text(
                        text = bookmark.bookText.replace('\n', ' ').trim(),
                        modifier = Modifier.padding(top = 5.dp),
                        color = contentColor.copy(alpha = 0.82f),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (bookmark.content.isNotBlank()) {
                    Text(
                        text = bookmark.content.replace('\n', ' ').trim(),
                        modifier = Modifier.padding(top = 5.dp),
                        color = accentColor.copy(alpha = 0.86f),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.all_bookmark_position,
                        bookmark.chapterIndex.coerceAtLeast(0) + 1,
                        bookmark.chapterPos.coerceAtLeast(0),
                    ),
                    modifier = Modifier.padding(top = 5.dp),
                    color = mutedColor.copy(alpha = 0.72f),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 时间线导轨：一条竖线 + 一个圆点。
 *
 * 组内首项竖线从圆点起、末项到圆点止，中间项贯穿，视觉上把同书书签串成一条线。
 * 节点内圈用卡片色盖住竖线，避免线穿过圆心。
 */
@Composable
private fun BookmarkTimelineRail(
    firstInGroup: Boolean,
    lastInGroup: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = bookmarkAccentColor()
    val nodeSurface = bookmarkCardContainerColor()
    Canvas(modifier = modifier) {
        val x = size.width / 2f
        val nodeY = 22.dp.toPx().coerceAtMost(size.height / 2f)
        drawLine(
            color = accent.copy(alpha = 0.74f),
            start = Offset(x, if (firstInGroup) nodeY else 0f),
            end = Offset(x, if (lastInGroup) nodeY else size.height),
            strokeWidth = 1.25.dp.toPx(),
        )
        drawCircle(
            color = nodeSurface,
            radius = 7.dp.toPx(),
            center = Offset(x, nodeY),
        )
        drawCircle(
            color = accent,
            radius = 5.dp.toPx(),
            center = Offset(x, nodeY),
        )
        drawCircle(
            color = nodeSurface.copy(alpha = 0.70f),
            radius = 5.dp.toPx(),
            center = Offset(x, nodeY),
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

/** 空状态：区分"没书签"与"条件太窄"，后者给出清空入口 */
@Composable
private fun BookmarkEmptyState(searching: Boolean, onClearConditions: (() -> Unit)?) {
    val mutedColor = bookmarkSecondaryTextColor()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_bookmark),
            contentDescription = null,
            modifier = Modifier.size(30.dp),
            tint = bookmarkAccentColor(),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(
                if (searching) R.string.all_bookmark_filter_empty else R.string.all_bookmark_empty
            ),
            color = mutedColor,
            fontSize = 14.sp,
        )
        if (onClearConditions != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onClearConditions) {
                Text(
                    text = stringResource(R.string.all_bookmark_filter_clear_conditions),
                    color = bookmarkAccentColor(),
                )
            }
        }
    }
}

/** 分组 + 折叠状态 → 扁平列表项 */
private fun buildBookmarkTimelineItems(
    groups: List<BookmarkTimelineGroup>,
    collapsedKeys: Set<String>,
    filtered: Boolean,
): List<BookmarkTimelineItem> {
    return buildList {
        groups.forEachIndexed { groupIndex, group ->
            val collapsed = group.book.key in collapsedKeys
            add(
                BookmarkTimelineItem.Header(
                    book = group.book,
                    matchedCount = group.matches.size,
                    filtered = filtered,
                    collapsed = collapsed,
                    firstGroup = groupIndex == 0,
                )
            )
            if (collapsed) return@forEachIndexed
            group.matches.forEachIndexed { itemIndex, indexedBookmark ->
                add(
                    BookmarkTimelineItem.Entry(
                        bookmark = indexedBookmark.value,
                        position = indexedBookmark.index,
                        firstInGroup = itemIndex == 0,
                        lastInGroup = itemIndex == group.matches.lastIndex,
                    )
                )
            }
        }
    }
}