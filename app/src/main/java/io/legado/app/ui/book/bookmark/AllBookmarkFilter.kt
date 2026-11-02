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

/** 同一本书下命中的书签，[IndexedValue.index] 是它在全量列表里的下标 */
internal data class BookmarkTimelineGroup(
    val book: BookmarkBookOption,
    val matches: List<IndexedValue<Bookmark>>,
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
        val matches = indexedGroups.getValue(book.key).filter { (_, bookmark) ->
            bookmark.matchesNoteFilter(filter.noteFilter) &&
                (filter.color == null ||
                    bookmark.isTextHighlight && bookmark.highlightColor == filter.color) &&
                bookmark.matchesQuery(normalizedQuery, searchBookMetadata)
        }
        if (matches.isEmpty()) null else BookmarkTimelineGroup(book, matches)
    }
    return AllBookmarkCollection(books, colors, groups, groups.sumOf { it.matches.size })
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