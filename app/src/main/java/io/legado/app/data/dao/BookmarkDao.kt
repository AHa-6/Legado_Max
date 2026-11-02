package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.Bookmark
import kotlinx.coroutines.flow.Flow

/**
 * 书签数据访问接口
 */
@Dao
interface BookmarkDao {

    @get:Query(
        """
        select * from bookmarks order by bookName collate localized, bookAuthor collate localized, chapterIndex, chapterPos
    """
    )
    val all: List<Bookmark>

    @get:Query("select count(*) from bookmarks")
    val count: Int

    @Query("select * from bookmarks order by bookName collate localized, bookAuthor collate localized, chapterIndex, chapterPos")
    fun flowAll(): Flow<List<Bookmark>>

    @Query(
        """SELECT * FROM bookmarks 
        where bookName like '%'||:key||'%' or bookAuthor like '%'||:key||'%'
        or chapterName like '%'||:key||'%' or bookText like '%'||:key||'%'
        or content like '%'||:key||'%'
        order by bookName collate localized, bookAuthor collate localized, chapterIndex, chapterPos"""
    )
    fun flowSearchAll(key: String): Flow<List<Bookmark>>

    @Query(
        """select * from bookmarks 
        where bookName = :bookName and bookAuthor = :bookAuthor 
        order by chapterIndex"""
    )
    fun flowByBook(bookName: String, bookAuthor: String): Flow<List<Bookmark>>

    @Query(
        """SELECT * FROM bookmarks 
        where bookName = :bookName and bookAuthor = :bookAuthor 
        and (chapterName like '%'||:key||'%' or content like '%'||:key||'%')
        order by chapterIndex"""
    )
    fun flowSearch(bookName: String, bookAuthor: String, key: String): Flow<List<Bookmark>>

    @Query(
        """select * from bookmarks
        where bookName = :bookName and bookAuthor = :bookAuthor
        order by chapterIndex"""
    )
    fun getByBook(bookName: String, bookAuthor: String): List<Bookmark>

    /**
     * 取覆盖到指定章节的文字高亮书签（含跨章覆盖到本章的部分），按起点排序。
     *
     * 排版层每章只调一次，直接在 SQL 里过滤掉位置书签与其它章节，
     * 避免把整本书的书签全查出来再在内存里筛。
     * `bookmarkType != 0` 排除位置书签：它们没有区间，渲染层用不上。
     */
    @Query(
        """select * from bookmarks
        where bookName = :bookName and bookAuthor = :bookAuthor
        and bookmarkType != 0
        and chapterIndex <= :chapterIndex and endChapterIndex >= :chapterIndex
        order by chapterIndex, chapterPos"""
    )
    fun getHighlightsByChapter(bookName: String, bookAuthor: String, chapterIndex: Int): List<Bookmark>

    @Query(
        """SELECT * FROM bookmarks 
        where bookName = :bookName and bookAuthor = :bookAuthor 
        and (chapterName like '%'||:key||'%' or content like '%'||:key||'%')
        order by chapterIndex"""
    )
    fun search(bookName: String, bookAuthor: String, key: String): List<Bookmark>

    @Query(
        """SELECT * FROM bookmarks 
        where bookName = :bookName and bookAuthor = :bookAuthor 
        and chapterName like '%'||:key||'%'
        order by chapterIndex"""
    )
    fun flowSearchChapter(bookName: String, bookAuthor: String, key: String): Flow<List<Bookmark>>

    @Query(
        """SELECT * FROM bookmarks 
        where bookName = :bookName and bookAuthor = :bookAuthor 
        and bookText like '%'||:key||'%'
        order by chapterIndex"""
    )
    fun flowSearchBookText(bookName: String, bookAuthor: String, key: String): Flow<List<Bookmark>>

    @Query(
        """SELECT * FROM bookmarks 
        where bookName = :bookName and bookAuthor = :bookAuthor 
        and content like '%'||:key||'%'
        order by chapterIndex"""
    )
    fun flowSearchContent(bookName: String, bookAuthor: String, key: String): Flow<List<Bookmark>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg bookmark: Bookmark)

    @Update
    fun update(bookmark: Bookmark)

    @Delete
    fun delete(vararg bookmark: Bookmark)

    @Query("delete from bookmarks")
    fun deleteAll()

}
