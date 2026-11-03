package io.legado.app.ui.book.bookmark

import io.legado.app.data.entities.Bookmark

/**
 * 所有书签页的筛选模型与分组计算。
 *
 * 全部为纯函数：筛选、搜索、按书分组都在内存里对 [Bookmark] 列表做一次遍历，
 * 不进 DAO。DAO 侧的 `flowSearchAll` 只能表达"关键字"，无法与笔记/颜色条件组合，
 * 放这里算还能让 UI 在条件变化时零 IO 即时刷新。
 */

/** 笔记（content 字段）维度的筛选 */
internal enum class BookmarkNoteFilter {
    ALL,
    WITH_NOTE,
    WITHOUT_NOTE,
}

/**
 * 组合筛选条件。
 *
 * @param bookKey 限定某本书，null 表示不限（见 [bookmarkBookKey]）
 * @param noteFilter 笔记有无
 * @param color 高亮颜色（ARGB），null 表示不限
 */
internal data class AllBookmarkFilter(
    val bookKey: String? = null,
    val noteFilter: BookmarkNoteFilter = BookmarkNoteFilter.ALL,
    val color: Int? = null,
) {
    /** 是否有任一条件生效，用于顶栏筛选按钮的红点与"清空条件"入口 */
    val isActive: Boolean
        get() = bookKey != null || noteFilter != BookmarkNoteFilter.ALL || color != null
}

/** 筛选面板里的一个书籍选项 */
internal data class BookmarkBookOption(
    val key: String,
    val bookName: String,
    val bookAuthor: String,
    /** 该书在全部书签里的条数（不受筛选影响，用于"命中 x / 共 y"） */
    val count: Int,
)

/**
 * 同一本书下命中的书签，[IndexedValue.index] 是它在全量列表里的下标。
 *
 * 书下面再按章分一层（[chapters]）：所有书签页原本只按书折叠，一本书里几百条书签
 * 摊成长长一条，翻到某一章的书签只能靠滑——目录侧的书签栏早就按章折叠了，两处口径不一致。
 */
internal data class BookmarkTimelineGroup(
    val book: BookmarkBookOption,
    val matches: List<IndexedValue<Bookmark>>,
    /** 按阅读顺序（chapterIndex 升序）分好的章节组 */
    val chapters: List<BookmarkChapterGroup>,
) {
    /**
     * 是否按章分组展示。
     *
     * 只有一章的书不画章节头：书头下面紧跟一条「第 x 章」纯属重复，
     * 那一章的章节名继续由书签卡片自己显示（见 BookmarkTimelineItem.Entry.showChapterName）。
     */
    val hasMultipleChapters: Boolean get() = chapters.size > 1
}

/**
 * 一章内命中的书签。
 *
 * @param key 折叠键，全局唯一（书键 + 章下标）
 * @param totalCount 这一章在**不受筛选影响**的全量书签里的条数，用于「命中 x / 共 y」
 */
internal data class BookmarkChapterGroup(
    val key: String,
    val chapterIndex: Int,
    val chapterName: String,
    val matches: List<IndexedValue<Bookmark>>,
    val totalCount: Int,
)

/** 一次筛选 + 搜索的完整结果 */
internal data class AllBookmarkCollection(
    val books: List<BookmarkBookOption>,
    /** 实际出现过的高亮色，用于筛选面板只列出有用的色块 */
    val colors: List<Int>,
    val groups: List<BookmarkTimelineGroup>,
    val matchCount: Int,
)

/**
 * 书籍分组键。
 *
 * 带长度前缀 + \u0000 分隔：书名或作者本身含分隔符时，
 * 「A·B」+「C」与「A」+「B·C」不会撞成同一个键。
 */
internal fun bookmarkBookKey(bookName: String, bookAuthor: String): String =
    "group:${bookName.length}:$bookName\u0000$bookAuthor"

/**
 * 章节折叠键。
 *
 * 书键本身已经带长度前缀与 \u0000 分隔，这里再拼一个章下标就不会与任何书键或
 * 别的书的章键撞上——折叠状态是书键与章键混在同一个集合里存的。
 */
internal fun bookmarkChapterKey(bookKey: String, chapterIndex: Int): String =
    "chapter:$bookKey\u0000$chapterIndex"

/**
 * 按书籍分组并套用搜索词与筛选条件。
 *
 * 分组基于全量列表（[books] / [colors] 不受条件影响），
 * 只有 [groups] 是命中结果——这样筛选面板里的书籍选项和色块不会随选择自我收缩。
 *
 * @param searchBookMetadata 搜索词是否也匹配书名/作者。目录侧的书签搜索只搜章节名/原文/笔记，
 * 这里默认开启是因为"所有书签"页的书名就是分组标题，用户输入书名找书是主要用法之一。
 */
internal fun buildAllBookmarkCollection(
    bookmarks: List<Bookmark>,
    query: String,
    filter: AllBookmarkFilter,
    searchBookMetadata: Boolean = true,
): AllBookmarkCollection {
    val indexedGroups = bookmarks.withIndex().groupBy {
        bookmarkBookKey(it.value.bookName, it.value.bookAuthor)
    }
    val books = indexedGroups.map { (key, entries) ->
        val first = entries.first().value
        BookmarkBookOption(key, first.bookName, first.bookAuthor, entries.size)
    }
    val colors = bookmarks.asSequence()
        .filter { it.isTextHighlight }
        .map { it.highlightColor }
        .distinct()
        .toList()
    val normalizedQuery = query.trim()
    val groups = books.mapNotNull { book ->
        if (filter.bookKey != null && filter.bookKey != book.key) {
            return@mapNotNull null
        }
        // 全量（不受筛选影响）留着算每章的总条数，命中结果留着分组
        val all = indexedGroups.getValue(book.key)
        val matches = all.filter { (_, bookmark) ->
            bookmark.matchesNoteFilter(filter.noteFilter) &&
                (filter.color == null ||
                    bookmark.isTextHighlight && bookmark.highlightColor == filter.color) &&
                bookmark.matchesQuery(normalizedQuery, searchBookMetadata)
        }
        if (matches.isEmpty()) {
            null
        } else {
            BookmarkTimelineGroup(book, matches, buildChapterGroups(book.key, all, matches))
        }
    }
    return AllBookmarkCollection(books, colors, groups, groups.sumOf { it.matches.size })
}

/**
 * 把一本书的命中结果按章再分一层。
 *
 * @param all 这本书的全量书签，只用来统计每章的总条数（「命中 x / 共 y」的分母）
 * @param matches 命中的书签。DAO 的 flowAll 已经按 `chapterIndex, chapterPos` 排好，
 *   `groupBy` 用 LinkedHashMap 保序，所以章节顺序就是阅读顺序，组内顺序就是章内顺序
 */
private fun buildChapterGroups(
    bookKey: String,
    all: List<IndexedValue<Bookmark>>,
    matches: List<IndexedValue<Bookmark>>,
): List<BookmarkChapterGroup> {
    val totals = all.groupingBy { it.value.chapterIndex }.eachCount()
    return matches.groupBy { it.value.chapterIndex }.map { (chapterIndex, members) ->
        BookmarkChapterGroup(
            key = bookmarkChapterKey(bookKey, chapterIndex),
            chapterIndex = chapterIndex,
            chapterName = members.first().value.chapterName,
            matches = members,
            totalCount = totals[chapterIndex] ?: members.size,
        )
    }
}

private fun Bookmark.matchesNoteFilter(filter: BookmarkNoteFilter): Boolean = when (filter) {
    BookmarkNoteFilter.ALL -> true
    BookmarkNoteFilter.WITH_NOTE -> content.isNotBlank()
    BookmarkNoteFilter.WITHOUT_NOTE -> content.isBlank()
}

private fun Bookmark.matchesQuery(query: String, searchBookMetadata: Boolean): Boolean =
    query.isEmpty() ||
        searchBookMetadata && (bookName.contains(query, ignoreCase = true) ||
            bookAuthor.contains(query, ignoreCase = true)) ||
        chapterName.contains(query, ignoreCase = true) ||
        bookText.contains(query, ignoreCase = true) ||
        content.contains(query, ignoreCase = true)