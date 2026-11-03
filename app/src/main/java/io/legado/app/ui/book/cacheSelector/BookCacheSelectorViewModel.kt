package io.legado.app.ui.book.cacheSelector

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.BookTagHelper
import io.legado.app.help.storage.BookCacheIndex
import io.legado.app.help.storage.BookCacheSelectorConfig
import io.legado.app.help.storage.ChapterCacheInfo
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.isContentScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class BookCacheItem(
    val book: Book,
    val cacheSize: Long,
    val formattedSize: String,
    val isSelected: Boolean
)

// 缓存索引数据类（BookCacheIndex / ChapterCacheInfo）统一在 io.legado.app.help.storage 中定义，
// 此处不再重复声明，避免两份定义字段不一致导致备份/恢复解析失败

sealed class BookCacheSelectorUiState {
    object Loading : BookCacheSelectorUiState()
    data class Exporting(val message: String) : BookCacheSelectorUiState()
    object Idle : BookCacheSelectorUiState()
    data class Error(val message: String) : BookCacheSelectorUiState()
}

/** 分组筛选里的「未分组」哨兵。真实分组 id 都是非负位掩码，MIN_VALUE 不会撞上。 */
internal const val GROUP_FILTER_NONE = Long.MIN_VALUE

/**
 * 缓存选择页可见列表的裁剪：搜索关键词 + 分组 + 标签，三个条件按「与」叠加。
 *
 * 做成纯函数放在 ViewModel 文件里：搜索词是输入框的临时态，分组/标签是 ViewModel 的
 * 持久态，两个来源在屏幕层合并调用一次即可，省掉屏幕里嵌三层 filter。
 *
 * @param groupId null = 全部；[GROUP_FILTER_NONE] = 未分组；正数为分组位掩码
 * @param groupMask 全部用户分组的位掩码，未分组判定要排除内置的负数组位
 *                  （与书架标签栏 [io.legado.app.ui.main.bookshelf.filterBookshelfTagInfosByGroup] 同口径）
 * @param tag 标签名，大小写不敏感匹配，null = 全部标签
 */
internal fun filterBookCacheItems(
    items: List<BookCacheItem>,
    searchKey: String,
    groupId: Long?,
    groupMask: Long,
    tag: String?,
): List<BookCacheItem> {
    if (searchKey.isBlank() && groupId == null && tag == null) return items
    val key = searchKey.trim()
    return items.filter { item ->
        val book = item.book
        val matchSearch = key.isEmpty() ||
                book.name.contains(key, ignoreCase = true) ||
                book.getRealAuthor().contains(key, ignoreCase = true)
        val matchGroup = when {
            groupId == null -> true
            groupId == GROUP_FILTER_NONE -> (book.group and groupMask) == 0L
            else -> (book.group and groupId) > 0L
        }
        val matchTag = tag == null || BookTagHelper.has(book.customTag, tag)
        matchSearch && matchGroup && matchTag
    }
}

class BookCacheSelectorViewModel(application: Application) : BaseViewModel(application) {

    private val _uiState = MutableStateFlow<BookCacheSelectorUiState>(BookCacheSelectorUiState.Loading)
    val uiState: StateFlow<BookCacheSelectorUiState> = _uiState.asStateFlow()

    private val _bookItems = MutableStateFlow<List<BookCacheItem>>(emptyList())
    val bookItems: StateFlow<List<BookCacheItem>> = _bookItems.asStateFlow()

    private val _selectedCount = MutableStateFlow(0)
    val selectedCount: StateFlow<Int> = _selectedCount.asStateFlow()

    private val _totalSelectedSize = MutableStateFlow(0L)
    val totalSelectedSize: StateFlow<Long> = _totalSelectedSize.asStateFlow()

    // ── 分组 / 标签筛选 ───────────────────────────────────────────────────
    // 筛选只裁剪内存里的列表，不改 DAO 查询：缓存书籍通常几十到几百本，而 loadBooks()
    // 里每本书的体积都要对缓存目录做一次统计，为切个筛选重跑那套 IO 完全不划算。

    /** 选中分组：null = 全部，[GROUP_FILTER_NONE] = 未分组，其余为具体分组的位掩码。 */
    private val _selectedGroupId = MutableStateFlow<Long?>(null)
    val selectedGroupId: StateFlow<Long?> = _selectedGroupId.asStateFlow()

    /** 选中标签：null = 全部标签。 */
    private val _selectedTag = MutableStateFlow<String?>(null)
    val selectedTag: StateFlow<String?> = _selectedTag.asStateFlow()

    /**
     * 可供筛选的用户分组。
     *
     * [io.legado.app.data.dao.BookGroupDao.flowSelect] 已按 `order` 返回 `groupId >= 0` 的行；
     * 这里只留 `groupId > 0`——0 号行不是位掩码分组，`book.group and 0` 恒为 0，
     * 选中它只会得到空列表，摆出来是死芯片。
     */
    val groupFilters: StateFlow<List<BookGroup>> = appDb.bookGroupDao.flowSelect()
        .map { groups -> groups.filter { it.groupId > 0 && it.groupName.isNotBlank() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 全部用户分组的位掩码，判定「未分组」用。 */
    val userGroupMask: StateFlow<Long> = groupFilters
        .map { groups -> groups.fold(0L) { acc, group -> acc or group.groupId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /**
     * 当前缓存书籍里出现过的标签，按书量降序排（常用的排前面，横排芯片不用滚太远）。
     *
     * 数据源是 [_bookItems] 而不是全库标签：这一页只筛「有缓存的那些书」，
     * 摆出别的书的标签就是给用户造空结果。同一本书里重复的标签 [BookTagHelper.parse]
     * 已去重，所以每本书每个标签只计一次。
     */
    val tagFilters: StateFlow<List<String>> = _bookItems
        .map { items ->
            val counts = LinkedHashMap<String, Pair<String, Int>>()
            items.forEach { item ->
                BookTagHelper.parse(item.book.customTag).forEach { tag ->
                    val key = tag.lowercase(Locale.ROOT)
                    val old = counts[key]
                    counts[key] = if (old == null) tag to 1 else old.first to old.second + 1
                }
            }
            counts.values
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
                .map { it.first }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectGroup(groupId: Long?) {
        _selectedGroupId.value = groupId
    }

    fun selectTag(tag: String?) {
        _selectedTag.value = tag
    }

    init {
        loadBooks()
    }

    fun loadBooks() {
        execute {
            _uiState.value = BookCacheSelectorUiState.Loading
            try {
                val books = withContext(Dispatchers.IO) {
                    BookCacheSelectorConfig.getBooksWithCache()
                }

                val items = books.map { book ->
                    async(Dispatchers.IO) {
                        val size = calculateBookCacheSize(book)
                        BookCacheItem(
                            book = book,
                            cacheSize = size,
                            formattedSize = ConvertUtils.formatFileSize(size),
                            isSelected = BookCacheSelectorConfig.isSelected(book)
                        )
                    }
                }.awaitAll()

                _bookItems.value = items
                updateSelectionSummary()
                _uiState.value = BookCacheSelectorUiState.Idle
            } catch (e: Exception) {
                _uiState.value = BookCacheSelectorUiState.Idle
            }
        }
    }

    fun toggleSelect(book: Book) {
        val current = _bookItems.value.toMutableList()
        val index = current.indexOfFirst { it.book.bookUrl == book.bookUrl }
        if (index == -1) return

        val item = current[index]
        val newSelected = !item.isSelected
        current[index] = item.copy(isSelected = newSelected)
        BookCacheSelectorConfig.setSelected(book, newSelected)
        _bookItems.value = current
        updateSelectionSummary()
    }

    fun selectAll() {
        val current = _bookItems.value.map { item ->
            BookCacheSelectorConfig.setSelected(item.book, true)
            item.copy(isSelected = true)
        }
        _bookItems.value = current
        updateSelectionSummary()
    }

    fun selectBooks(books: List<Book>) {
        val bookUrls = books.map { it.bookUrl }.toHashSet()
        if (bookUrls.isEmpty()) return
        val current = _bookItems.value.map { item ->
            if (item.book.bookUrl in bookUrls) {
                BookCacheSelectorConfig.setSelected(item.book, true)
                item.copy(isSelected = true)
            } else {
                item
            }
        }
        _bookItems.value = current
        updateSelectionSummary()
    }

    fun deselectAll() {
        val current = _bookItems.value.map { item ->
            BookCacheSelectorConfig.setSelected(item.book, false)
            item.copy(isSelected = false)
        }
        _bookItems.value = current
        updateSelectionSummary()
    }

    fun deselectBooks(books: List<Book>) {
        val bookUrls = books.map { it.bookUrl }.toHashSet()
        if (bookUrls.isEmpty()) return
        val current = _bookItems.value.map { item ->
            if (item.book.bookUrl in bookUrls) {
                BookCacheSelectorConfig.setSelected(item.book, false)
                item.copy(isSelected = false)
            } else {
                item
            }
        }
        _bookItems.value = current
        updateSelectionSummary()
    }

    fun isAllSelected(): Boolean {
        return _bookItems.value.isNotEmpty() && _bookItems.value.all { it.isSelected }
    }

    fun isAllSelected(items: List<BookCacheItem>): Boolean {
        return items.isNotEmpty() && items.all { it.isSelected }
    }

    fun saveSelection() {
        BookCacheSelectorConfig.save()
    }

    fun exportSelectedBooks(context: Context, targetUri: Uri) {
        execute {
            _uiState.value = BookCacheSelectorUiState.Exporting("准备导出...")

            val selectedBooks = BookCacheSelectorConfig.getSelectedBooks()
            if (selectedBooks.isEmpty()) {
                _uiState.value = BookCacheSelectorUiState.Idle
                return@execute
            }

            val cacheDir = File(BookHelp.cachePath)
            if (!cacheDir.exists()) {
                _uiState.value = BookCacheSelectorUiState.Idle
                return@execute
            }

            val timestamp = LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            )
            val zipName = "书籍缓存${timestamp}.zip"

            // 临时目录：先拷贝缓存文件 + 索引，再打包
            val tempDir = File(appCtx.cacheDir, "book_cache_export")
                .createFolderIfNotExist()

            try {
                val bookCacheIndexList = mutableListOf<BookCacheIndex>()
                val bookCacheBooks = mutableListOf<Book>()
                val allChapters = mutableListOf<BookChapter>()
                val zipSources = mutableListOf<File>()
                // book_cache 子目录
                val tempCacheDir = File(tempDir, "book_cache").createFolderIfNotExist()

                selectedBooks.forEachIndexed { index, book ->
                    _uiState.value = BookCacheSelectorUiState.Exporting(
                        "导出中 (${index + 1}/${selectedBooks.size}): ${book.name}"
                    )

                    try {
                        val folderName = book.getFolderName()
                        val bookFolder = File(cacheDir, folderName)
                        if (!bookFolder.exists() || !bookFolder.isDirectory) return@forEachIndexed

                        val chapterList = appDb.bookChapterDao.getChapterList(book.bookUrl)
                        val chapterMap = chapterList.associateBy { it.index }
                        allChapters.addAll(chapterList)

                        val chapterCacheInfos = mutableListOf<ChapterCacheInfo>()
                        bookFolder.listFiles()?.forEach { file ->
                            if (file.isFile && file.name.endsWith(".nb")) {
                                parseChapterFileName(file.name, chapterMap)?.let {
                                    chapterCacheInfos.add(it)
                                }
                            }
                        }

                        bookCacheIndexList.add(
                            BookCacheIndex(
                                bookUrl = book.bookUrl,
                                bookName = book.name,
                                author = book.author ?: "",
                                folderName = folderName,
                                chapters = chapterCacheInfos.sortedBy { it.index }
                            )
                        )

                        // 拷贝到临时目录
                        val targetBookDir = File(tempCacheDir, folderName)
                            .createFolderIfNotExist()
                        bookFolder.copyRecursively(targetBookDir, overwrite = true)
                        bookCacheBooks.add(book)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // 写索引文件到临时目录
                if (tempCacheDir.exists()) {
                    zipSources.add(tempCacheDir)
                }

                if (bookCacheIndexList.isNotEmpty()) {
                    val indexFile = File(tempDir, "bookCacheIndex.json")
                    indexFile.writeText(GSON.toJson(bookCacheIndexList))
                    zipSources.add(indexFile)
                    val booksFile = File(tempDir, "bookCacheBooks.json")
                    booksFile.writeText(GSON.toJson(bookCacheBooks))
                    zipSources.add(booksFile)
                }
                if (allChapters.isNotEmpty()) {
                    val chapterFile = File(tempDir, "bookChapterCache.json")
                    chapterFile.writeText(GSON.toJson(allChapters))
                    zipSources.add(chapterFile)
                }

                // 打包为 zip
                _uiState.value = BookCacheSelectorUiState.Exporting("正在打包...")
                val tempZip = File(tempDir, zipName)
                ZipUtils.zipFiles(zipSources, tempZip)

                // 写入目标位置
                _uiState.value = BookCacheSelectorUiState.Exporting("正在写入文件...")
                writeZipToTarget(context, targetUri, tempZip, zipName)
            } finally {
                tempDir.deleteRecursively()
            }

            _uiState.value = BookCacheSelectorUiState.Idle
        }
    }

    private fun writeZipToTarget(
        context: Context, targetUri: Uri, zipFile: File, zipName: String
    ) {
        if (targetUri.isContentScheme()) {
            val targetDir = DocumentFile.fromTreeUri(context, targetUri)!!
            val docFile = targetDir.findFile(zipName)
                ?: targetDir.createFile("application/zip", zipName)
                ?: return
            context.contentResolver.openOutputStream(docFile.uri)!!.use { output ->
                zipFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
        } else {
            zipFile.copyTo(File(targetUri.path!!, zipName), overwrite = true)
        }
    }

    private fun parseChapterFileName(
        fileName: String,
        chapterMap: Map<Int, BookChapter>
    ): ChapterCacheInfo? {
        if (!fileName.endsWith(".nb")) return null

        val nameWithoutExt = fileName.removeSuffix(".nb")
        val parts = nameWithoutExt.split("-")
        if (parts.size != 2) return null

        val index = parts[0].toIntOrNull() ?: return null
        val titleMD5 = parts[1]
        val chapter = chapterMap[index] ?: return null

        return ChapterCacheInfo(
            index = index,
            title = chapter.title,
            titleMD5 = titleMD5,
            fileName = fileName
        )
    }

    private fun updateSelectionSummary() {
        val items = _bookItems.value
        val selected = items.filter { it.isSelected }
        _selectedCount.value = selected.size
        _totalSelectedSize.value = selected.sumOf { it.cacheSize }
    }

    private fun calculateBookCacheSize(book: Book): Long {
        val cacheDir = File(BookHelp.cachePath, book.getFolderName())
        return calculateDirSize(cacheDir)
    }

    private fun calculateDirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var size = 0L
        try {
            val stack = ArrayDeque<File>()
            stack.addLast(dir)
            while (stack.isNotEmpty()) {
                val current = stack.removeLast()
                current.listFiles()?.forEach { file: File ->
                    if (file.isFile) {
                        size += file.length()
                    } else if (file.isDirectory) {
                        stack.addLast(file)
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return size
    }
}
