package io.legado.app.ui.book.cacheSelector

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.data.entities.BookGroup
import io.legado.app.ui.book.cacheSelector.components.BookCacheItemCard
import io.legado.app.ui.theme.pageAccentColor
import io.legado.app.ui.theme.pageCardContainerColor
import io.legado.app.ui.widget.components.AppPageTopBar
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.navigationBarBottomInset
import io.legado.app.utils.ConvertUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookCacheSelectorScreen(
    viewModel: BookCacheSelectorViewModel = viewModel(),
    onBackClick: () -> Unit,
    onSaveClick: () -> Unit,
    onExportClick: () -> Unit,
    onHelpClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val bookItems by viewModel.bookItems.collectAsState()
    val selectedCount by viewModel.selectedCount.collectAsState()
    val totalSelectedSize by viewModel.totalSelectedSize.collectAsState()
    val groupFilters by viewModel.groupFilters.collectAsState()
    val tagFilters by viewModel.tagFilters.collectAsState()
    val userGroupMask by viewModel.userGroupMask.collectAsState()
    val selectedGroupId by viewModel.selectedGroupId.collectAsState()
    val selectedTag by viewModel.selectedTag.collectAsState()
    var searchKey by rememberSaveable { mutableStateOf("") }
    var searchVisible by rememberSaveable { mutableStateOf(false) }

    val accentColor = pageAccentColor()
    // 搜索词留在屏幕（输入框临时态），分组/标签放 ViewModel（旋转屏幕、进出页面都还在），
    // 三者在这里合成一份可见列表。「全选/取消全选」也作用在它上面，
    // 于是筛选之后全选天然只覆盖当前能看到的书。
    val visibleBookItems = filterBookCacheItems(
        items = bookItems,
        searchKey = searchKey,
        groupId = selectedGroupId,
        groupMask = userGroupMask,
        tag = selectedTag
    )
    val visibleAllSelected = viewModel.isAllSelected(visibleBookItems)

    AppScaffold(
        topBar = {
            AppPageTopBar(
                title = stringResource(R.string.bcs_title),
                onBackClick = onBackClick,
                actions = {
                    IconButton(onClick = {
                        if (searchVisible || searchKey.isNotEmpty()) {
                            searchKey = ""
                            searchVisible = false
                        } else {
                            searchVisible = true
                        }
                    }) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.action_search))
                    }
                    IconButton(onClick = onHelpClick) {
                        Icon(Icons.Default.Help, contentDescription = stringResource(R.string.help))
                    }
                    TextButton(onClick = {
                        if (visibleAllSelected) {
                            viewModel.deselectBooks(visibleBookItems.map { it.book })
                        } else {
                            viewModel.selectBooks(visibleBookItems.map { it.book })
                        }
                    }) {
                        Text(
                            text = if (visibleAllSelected) stringResource(R.string.bcs_deselect_all) else stringResource(R.string.select_all),
                            color = accentColor
                        )
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                // 背景延伸到导航条后面保持铺满，栏内按钮避开导航条以免被压住
                modifier = Modifier.navigationBarsPadding(),
                color = pageCardContainerColor(),
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onSaveClick,
                        modifier = Modifier.weight(1f),
                        enabled = selectedCount > 0,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accentColor,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                        )
                    ) {
                        Text(stringResource(R.string.bcs_save_selection))
                    }
                    OutlinedButton(
                        onClick = onExportClick,
                        modifier = Modifier.weight(1f),
                        enabled = selectedCount > 0,
                        border = BorderStroke(
                            1.dp,
                            if (selectedCount > 0) accentColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = accentColor,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                        )
                    ) {
                        Text(stringResource(R.string.bcs_export_selected))
                    }
                }
            }
        }
    ) { paddingValues ->
        when (uiState) {
            is BookCacheSelectorUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = accentColor)
                }
            }
            is BookCacheSelectorUiState.Idle -> {
                LazyColumn(
                    modifier = Modifier.padding(paddingValues),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp + navigationBarBottomInset),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 顶部区：搜索框（按需展开）→ 筛选芯片 → 汇总条
                    item {
                        if (searchVisible || searchKey.isNotEmpty()) {
                            BookCacheSearchField(
                                query = searchKey,
                                onQueryChange = { searchKey = it },
                                accentColor = accentColor
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        // 分组 / 标签筛选：数据为空就不占行，免得空分类留一条白带
                        if (groupFilters.isNotEmpty()) {
                            GroupFilterRow(
                                groups = groupFilters,
                                selectedGroupId = selectedGroupId,
                                accentColor = accentColor,
                                onSelect = viewModel::selectGroup
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        if (tagFilters.isNotEmpty()) {
                            TagFilterRow(
                                tags = tagFilters,
                                selectedTag = selectedTag,
                                accentColor = accentColor,
                                onSelect = viewModel::selectTag
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        SummaryBar(
                            selectedCount = selectedCount,
                            totalSize = totalSelectedSize
                        )
                        Spacer(Modifier.height(4.dp))
                    }

                    if (visibleBookItems.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.no_book),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(
                            items = visibleBookItems,
                            key = { it.book.bookUrl }
                        ) { item ->
                            BookCacheItemCard(
                                item = item,
                                onToggleSelect = { viewModel.toggleSelect(item.book) },
                                accentColor = accentColor
                            )
                        }
                    }

                    item {
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
            is BookCacheSelectorUiState.Exporting -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = accentColor)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = (uiState as BookCacheSelectorUiState.Exporting).message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            is BookCacheSelectorUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = (uiState as BookCacheSelectorUiState.Error).message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun BookCacheSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    accentColor: Color
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.action_search))
        },
        placeholder = {
            Text(stringResource(R.string.action_search))
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = accentColor,
            focusedLeadingIconColor = accentColor,
            cursorColor = accentColor
        )
    )
}

@Composable
private fun SummaryBar(
    selectedCount: Int,
    totalSize: Long
) {
    val accentColor = pageAccentColor()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        color = cacheSelectorTintContainerColor(accentColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.bcs_selected_count, selectedCount),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.bcs_total_size, ConvertUtils.formatFileSize(totalSize)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = accentColor
            )
        }
    }
}

@Composable
internal fun cacheSelectorTintContainerColor(accentColor: Color): Color {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.18f
    return if (isDark) {
        accentColor.copy(alpha = 0.16f)
    } else {
        accentColor.copy(alpha = 0.10f)
    }
}

/**
 * 分组筛选行。
 *
 * 顺序按用户排序（DAO 的 `ORDER BY order`），末尾固定挂「未分组」：
 * 位掩码判定下「没进任何分组」是个合法结果，但它不占 `book_groups` 的任何一行，
 * 只能自己补一个芯片。
 */
@Composable
private fun GroupFilterRow(
    groups: List<BookGroup>,
    selectedGroupId: Long?,
    accentColor: Color,
    onSelect: (Long?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CacheFilterChip(
            label = stringResource(R.string.all),
            selected = selectedGroupId == null,
            accentColor = accentColor,
            onClick = { onSelect(null) }
        )
        groups.forEach { group ->
            CacheFilterChip(
                label = group.groupName,
                selected = selectedGroupId == group.groupId,
                accentColor = accentColor,
                onClick = { onSelect(group.groupId) }
            )
        }
        CacheFilterChip(
            label = stringResource(R.string.no_group),
            selected = selectedGroupId == GROUP_FILTER_NONE,
            accentColor = accentColor,
            onClick = { onSelect(GROUP_FILTER_NONE) }
        )
    }
}

/**
 * 标签筛选行。
 *
 * 标签名统一加 `#` 前缀：它和分组行是两条长得一样的芯片带，靠前缀区分「这是标签」，
 * 也免得分组名和标签名撞成一样的字时看不出来。
 */
@Composable
private fun TagFilterRow(
    tags: List<String>,
    selectedTag: String?,
    accentColor: Color,
    onSelect: (String?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CacheFilterChip(
            label = stringResource(R.string.all),
            selected = selectedTag == null,
            accentColor = accentColor,
            onClick = { onSelect(null) }
        )
        tags.forEach { tag ->
            CacheFilterChip(
                label = "#$tag",
                selected = selectedTag == tag,
                accentColor = accentColor,
                onClick = { onSelect(tag) }
            )
        }
    }
}

/**
 * 筛选芯片。
 *
 * 选中态用页面强调色的低透明度容器 + 强调色文字，与 [SummaryBar]、[BookCacheItemCard]
 * 的选中态同一套取色（[cacheSelectorTintContainerColor]）；未选中走 surface 底配次要色文字。
 * 不额外传 border：FilterChip 的默认描边在日夜两套主题下都能看清，自己定反而要按夜/昼分叉，
 * 与卡片那边已有的描边取色重复。
 */
@Composable
private fun CacheFilterChip(
    label: String,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surface,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = cacheSelectorTintContainerColor(accentColor),
            selectedLabelColor = accentColor
        )
    )
}
