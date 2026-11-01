package io.legado.app.ui.video.player

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import io.legado.app.R
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.videoTime
import kotlinx.coroutines.launch

/** 剧集数据项包装器 */
data class VideoEpisodeItem(
    val chapter: BookChapter,
    val volumeIndex: Int,
    val episodeIndex: Int,
)

/** 剧集分组 */
data class VideoEpisodeGroup(
    val volume: BookChapter?,  // 卷标题章节
    val items: List<VideoEpisodeItem>,
)

/**
 * 根据全量目录与卷列表生成剧集分组。
 *
 * 与数据层 VideoPlay 完全同口径：
 * - 卷由 [BookChapter.isVolume] 标记，不是靠标题/tag 里的“卷/部/季”关键词猜；
 * - 分组序号就是 `VideoPlay.volumes` 的下标，即 `VideoPlay.durVolumeIndex`；
 * - 组内 `episodeIndex` 就是 `VideoPlay.chapterInVolumeIndex`，
 *   对应 `episodes = toc.subList(durVolume.index + 1, nextVolume.index)` 的相对偏移；
 * - 卷标题章节只做分组标签，不进入 items。
 *
 * 注意必须传 [VideoPlay.toc] 全量目录：`VideoPlay.episodes` 已经被切片成当前卷，
 * 拿它来分组会丢掉卷锚点，有分卷的源会退化成单组。
 */
fun generateVideoEpisodeGroups(
    toc: List<BookChapter>,
    volumes: List<BookChapter>,
): List<VideoEpisodeGroup> {
    // 兜底：volumes 未填充时，用实体上的 isVolume 标记自行挑出卷章节
    val anchors = volumes.ifEmpty { toc.filter { it.isVolume } }

    if (anchors.isEmpty()) {
        // 没有分卷：episodes 就是整份 toc，episodeIndex 即 toc 下标
        return listOf(
            VideoEpisodeGroup(
                null,
                toc.mapIndexed { index, chapter ->
                    VideoEpisodeItem(chapter, 0, index)
                }
            )
        )
    }

    return anchors.mapIndexed { volumeIndex, volume ->
        val start = volume.index.coerceIn(0, toc.size)
        val end = (anchors.getOrNull(volumeIndex + 1)?.index ?: toc.size)
            .coerceIn(start, toc.size)
        val items = if (end > start + 1) {
            (start + 1 until end).map { index ->
                VideoEpisodeItem(toc[index], volumeIndex, index - start - 1)
            }
        } else {
            emptyList()
        }
        VideoEpisodeGroup(volume, items)
    }
}

/**
 * 过滤剧集：搜索时跨所有卷，普通浏览只在当前卷
 */
fun filterVideoEpisodes(
    groups: List<VideoEpisodeGroup>,
    volumeIndex: Int,
    query: String,
    descending: Boolean,
): List<VideoEpisodeItem> {
    val keyword = query.trim()
    val items = if (keyword.isEmpty()) {
        groups.getOrNull(volumeIndex)?.items.orEmpty()
    } else {
        groups.asSequence()
            .flatMap { it.items.asSequence() }
            .filter {
                val title = it.chapter.title
                title.contains(keyword, ignoreCase = true) ||
                (it.episodeIndex + 1).toString() == keyword
            }
            .toList()
    }
    return if (descending) items.asReversed() else items
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoEpisodeSheet(
    groups: List<VideoEpisodeGroup>,
    initialVolume: Int,
    currentVolume: Int,
    currentEpisode: Int,
    currentDuration: Long = 0,
    onSelect: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var browsingVolume by rememberSaveable { mutableIntStateOf(initialVolume) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var descending by rememberSaveable { mutableStateOf(false) }
    
    // 自动判断是否使用网格视图（标题长度都较短）
    var grid by rememberSaveable {
        mutableStateOf(groups.all { group -> 
            group.items.all { it.chapter.title.length <= 10 } 
        })
    }
    
    var locateRequest by rememberSaveable { mutableIntStateOf(0) }
    
    val groupIndex = browsingVolume.coerceIn(0, groups.lastIndex.coerceAtLeast(0))
    val group = groups.getOrNull(groupIndex)
    val totalCount = remember(groups) { groups.sumOf { it.items.size } }
    val hasTools = totalCount > 1 || groups.size > 1
    
    val items = remember(groups, groupIndex, query, descending) {
        filterVideoEpisodes(groups, groupIndex, query, descending)
    }
    
    val selectedIndex = remember(items, currentVolume, currentEpisode) {
        items.indexOfFirst { it.volumeIndex == currentVolume && it.episodeIndex == currentEpisode }
    }
    
    val showGrid = grid && items.size > 1 && query.isBlank()
    
    val currentItem = remember(groups, currentVolume, currentEpisode) {
        groups.getOrNull(currentVolume)?.items?.firstOrNull { it.episodeIndex == currentEpisode }
    }
    
    val listState = rememberLazyListState(selectedIndex.coerceAtLeast(0))
    val gridState = rememberLazyGridState(selectedIndex.coerceAtLeast(0))
    val volumeState = rememberLazyListState(groupIndex)
    
    val scope = rememberCoroutineScope()
    val colors = io.legado.app.ui.theme.videoContentColors()
    val focus = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    
    val scrollRequest = "$groupIndex:$descending:$locateRequest:$query"
    var handledScrollRequest by rememberSaveable { mutableStateOf(scrollRequest) }
    
    LaunchedEffect(groupIndex, query, descending, locateRequest, showGrid) {
        if (handledScrollRequest == scrollRequest) return@LaunchedEffect
        val index = selectedIndex.coerceAtLeast(0)
        if (items.isNotEmpty()) {
            if (showGrid) gridState.scrollToItem(index) else listState.scrollToItem(index)
        }
        if (query.isBlank() && groups.size > 1) {
            volumeState.animateScrollToItem(groupIndex)
        }
        handledScrollRequest = scrollRequest
    }
    
    fun locateCurrent() {
        browsingVolume = currentVolume
        query = ""
        locateRequest++
        focus.clearFocus()
    }
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false)
    ) {
        val window = (LocalView.current.parent as DialogWindowProvider).window
        DisposableEffect(window) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            onDispose { }
        }
        
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
        ) {
            val compactHeight = maxHeight < 280.dp
            val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            
            Box(Modifier.fillMaxSize().clickable(onClick = onDismiss))
            
            val shape = if (landscape) {
                RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)
            } else {
                RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
            }
            
            Column(
                modifier = (if (landscape) {
                    Modifier
                        .align(Alignment.CenterEnd)
                        .width(minOf(420.dp, maxWidth * 0.55f))
                        .fillMaxHeight()
                } else {
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .heightIn(max = if (keyboardVisible) maxHeight else maxHeight * 0.72f)
                })
                .clip(shape)
                .background(colors.surface)
                .semantics { 
                    dismiss { 
                        onDismiss()
                        true 
                    } 
                }
                .pointerInput(Unit) { detectTapGestures {} }
            ) {
                val dismissOnDrag = if (landscape) Modifier else Modifier.pointerInput(onDismiss) {
                    var drag = 0f
                    detectVerticalDragGestures(
                        onDragStart = { drag = 0f },
                        onDragCancel = { drag = 0f },
                        onDragEnd = { if (drag > 48.dp.toPx()) onDismiss() }
                    ) { _, amount ->
                        drag += amount
                    }
                }
                
                Column(dismissOnDrag) {
                    // 标题栏
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .padding(start = 18.dp, end = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "选集",
                            color = colors.content,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium
                        )
                        
                        Text(
                            if (query.isBlank()) {
                                "${group?.items?.size ?: 0}集"
                            } else {
                                "${items.size}/$totalCount"
                            },
                            modifier = Modifier.padding(start = 10.dp),
                            color = colors.muted,
                            fontSize = 12.sp
                        )
                        
                        Spacer(Modifier.weight(1f))
                        
                        if (hasTools) {
                            // 搜索按钮
                            IconButton(onClick = {
                                searching = !searching
                                if (!searching) {
                                    query = ""
                                    focus.clearFocus()
                                }
                            }) {
                                Icon(
                                    painterResource(R.drawable.ic_search),
                                    contentDescription = "搜索剧集",
                                    tint = if (searching) colors.accent else colors.content
                                )
                            }
                            
                            // 排序/视图菜单
                            Box {
                                IconButton(onClick = { menu = true }) {
                                    Icon(
                                        painterResource(R.drawable.ic_sort),
                                        contentDescription = "选集选项",
                                        tint = colors.content
                                    )
                                }
                                
                                DropdownMenu(
                                    expanded = menu,
                                    onDismissRequest = { menu = false },
                                    containerColor = colors.cell
                                ) {
                                    DropdownMenuItem(
                                        text = { 
                                            Text(
                                                if (descending) "正序" else "倒序",
                                                color = colors.content
                                            ) 
                                        },
                                        onClick = { 
                                            descending = !descending
                                            menu = false 
                                        }
                                    )
                                    
                                    DropdownMenuItem(
                                        text = { 
                                            Text(
                                                if (grid) "列表" else "网格",
                                                color = colors.content
                                            ) 
                                        },
                                        onClick = {
                                            val index = if (showGrid) {
                                                gridState.firstVisibleItemIndex
                                            } else {
                                                listState.firstVisibleItemIndex
                                            }
                                            grid = !grid
                                            menu = false
                                            
                                            val useGrid = grid && items.size > 1 && query.isBlank()
                                            scope.launch {
                                                if (items.isNotEmpty()) {
                                                    if (useGrid) {
                                                        gridState.scrollToItem(index)
                                                    } else {
                                                        listState.scrollToItem(index)
                                                    }
                                                }
                                            }
                                        }
                                    )
                                    
                                    DropdownMenuItem(
                                        text = { 
                                            Text("定位当前集", color = colors.content) 
                                        },
                                        onClick = { 
                                            menu = false
                                            locateCurrent()
                                        }
                                    )
                                }
                            }
                        }
                    }
                    
                    // 卷标签栏（仅在没有搜索时显示）
                    if (query.isBlank() && !searching && groups.any { it.volume != null }) {
                        LazyRow(
                            state = volumeState,
                            contentPadding = PaddingValues(horizontal = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            itemsIndexed(
                                groups,
                                key = { index, value -> "${index}:${value.volume?.url}" }
                            ) { index, value ->
                                val active = groupIndex == index
                                Box(
                                    modifier = Modifier
                                        .height(48.dp)
                                        .widthIn(min = 48.dp, max = 200.dp)
                                        .semantics { selected = active }
                                        .clickable(role = Role.Tab) { 
                                            browsingVolume = index
                                            focus.clearFocus()
                                        }
                                ) {
                                    Text(
                                        value.volume?.title ?: "",
                                        modifier = Modifier.padding(vertical = 10.dp),
                                        color = if (active) colors.content else colors.muted,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    
                                    if (active) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .fillMaxWidth()
                                                .height(2.dp)
                                                .background(colors.accent)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    // 搜索框
                    if (searching) {
                        LaunchedEffect(Unit) { searchFocus.requestFocus() }
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 18.dp)
                                .fillMaxWidth()
                                .height(48.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_search),
                                null,
                                modifier = Modifier.size(20.dp),
                                tint = colors.muted
                            )
                            
                            EpisodeSearchInput(
                                query = query,
                                onQueryChange = { query = it },
                                focusRequester = searchFocus,
                                modifier = Modifier.weight(1f).padding(start = 8.dp)
                            )
                        }
                        
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 18.dp),
                            color = colors.content.copy(alpha = 0.12f)
                        )
                    }
                    
                    // 剧集列表
                    if (items.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f, fill = landscape)
                                .fillMaxWidth()
                                .heightIn(min = if (compactHeight) 48.dp else 120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (query.isBlank()) "暂无可播放剧集" else "未找到相关剧集",
                                color = colors.muted,
                                fontSize = 14.sp
                            )
                        }
                    } else if (showGrid) {
                        BoxWithConstraints(
                            modifier = Modifier
                                .weight(1f, fill = landscape)
                                .fillMaxWidth()
                        ) {
                            val columns = minOf(
                                4,
                                ((maxWidth - 28.dp) / 88.dp).toInt().coerceAtLeast(1)
                            )
                            
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(columns),
                                state = gridState,
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                gridItems(
                                    items,
                                    key = { "${it.chapter.index}:${it.chapter.url}" }
                                ) { item ->
                                    val isCurrent = item.volumeIndex == currentVolume && 
                                                   item.episodeIndex == currentEpisode
                                    EpisodeTile(
                                        item = item,
                                        isCurrent = isCurrent,
                                        isGrid = true,
                                        duration = 0,
                                        volumeTitle = null,
                                        onClick = {
                                            onSelect(item.volumeIndex, item.episodeIndex)
                                            onDismiss()
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f, fill = landscape)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(
                                start = 18.dp,
                                end = 18.dp,
                                top = 8.dp,
                                bottom = if (hasTools) 12.dp else 24.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                items,
                                key = { "${it.chapter.index}:${it.chapter.url}" }
                            ) { item ->
                                val isCurrent = item.volumeIndex == currentVolume && 
                                               item.episodeIndex == currentEpisode
                                EpisodeTile(
                                    item = item,
                                    isCurrent = isCurrent,
                                    isGrid = false,
                                    duration = if (isCurrent) currentDuration else 0,
                                    volumeTitle = if (query.isNotBlank()) {
                                        groups.getOrNull(item.volumeIndex)?.volume?.title
                                    } else null,
                                    onClick = {
                                        onSelect(item.volumeIndex, item.episodeIndex)
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    }
                    
                    // 底部当前集信息
                    if (hasTools && currentItem != null && !compactHeight) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 18.dp),
                            color = colors.content.copy(alpha = 0.1f)
                        )
                        
                        Text(
                            "第${currentEpisode + 1}集 · ${currentItem.chapter.title}",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(
                                    onClickLabel = "定位当前集",
                                    onClick = ::locateCurrent
                                )
                                .padding(horizontal = 18.dp, vertical = 12.dp),
                            color = colors.muted,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeTile(
    item: VideoEpisodeItem,
    isCurrent: Boolean,
    isGrid: Boolean,
    duration: Long,
    volumeTitle: String?,
    onClick: () -> Unit,
) {
    // 获取视频主题色
    val videoColors = io.legado.app.ui.theme.videoContentColors()
    val accent = videoColors.accent
    val muted = videoColors.muted
    val content = videoColors.content
    
    val modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = if (isGrid) 72.dp else 56.dp)
        .clip(RoundedCornerShape(if (isGrid) 8.dp else 12.dp))
        .background(if (isCurrent && isGrid) videoColors.selectedContainer else videoColors.cell)
        .semantics { selected = isCurrent }
        .clickable(role = Role.Button, onClick = onClick)
    
    if (isGrid) {
        Box(
            modifier = modifier.semantics { 
                contentDescription = "第${item.episodeIndex + 1}集 ${item.chapter.title}" 
            },
            contentAlignment = Alignment.Center
        ) {
            val ink = if (isCurrent) videoColors.onAccent else content
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "${item.episodeIndex + 1}".padStart(2, '0'),
                    color = ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    item.chapter.title,
                    modifier = Modifier.padding(top = 4.dp),
                    color = ink,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
            
            if (item.chapter.isVip && !item.chapter.isPay) {
                Icon(
                    painterResource(R.drawable.ic_lock_outline),
                    "VIP",
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(10.dp),
                    tint = ink
                )
            }
        }
    } else {
        Row(
            modifier = modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${item.episodeIndex + 1}".padStart(2, '0'),
                color = if (isCurrent) accent else muted,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            
            Box(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .width(1.dp)
                    .height(20.dp)
                    .background(content.copy(alpha = 0.16f))
            )
            
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // VIP 标识
                    if (item.chapter.isVip && !item.chapter.isPay) {
                        Icon(
                            painterResource(R.drawable.ic_lock_outline),
                            "VIP",
                            modifier = Modifier.padding(end = 4.dp).size(16.dp),
                            tint = muted
                        )
                    }
                    
                    Text(
                        item.chapter.title,
                        color = content,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                // 卷名和标签
                listOfNotNull(volumeTitle, item.chapter.tag)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                    .takeIf { it.isNotBlank() }
                    ?.let {
                        Text(
                            it,
                            modifier = Modifier.padding(top = 3.dp),
                            color = muted,
                            fontSize = 10.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
            }
            
            // 时长显示
            if (duration > 0 && item.chapter.tag.isNullOrBlank()) {
                Text(
                    videoTime(duration),
                    modifier = Modifier.padding(start = 12.dp),
                    color = muted,
                    fontSize = 12.sp
                )
            }
            
            // 当前集标识
            if (isCurrent) {
                Icon(
                    Icons.Default.Equalizer,
                    "当前集",
                    modifier = Modifier.padding(start = 12.dp).size(20.dp),
                    tint = accent
                )
            }
        }
    }
}

@Composable
private fun EpisodeSearchInput(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    val videoColors = io.legado.app.ui.theme.videoContentColors()
    
    Row(
        modifier = modifier.height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .semantics { contentDescription = "搜索集名或集数" },
            textStyle = TextStyle(color = videoColors.content, fontSize = 14.sp),
            singleLine = true,
            cursorBrush = SolidColor(videoColors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            decorationBox = { field ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            "搜索集名或集数",
                            color = videoColors.muted,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    field()
                }
            }
        )
        
        if (query.isNotEmpty()) {
            TextButton(
                onClick = { onQueryChange("") },
                modifier = Modifier.height(48.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text(
                    "清空",
                    color = videoColors.muted,
                    fontSize = 12.sp
                )
            }
        }
    }
}