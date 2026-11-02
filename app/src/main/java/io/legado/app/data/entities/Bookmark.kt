package io.legado.app.data.entities

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

/**
 * 书签 / 文字高亮。
 *
 * 一条记录有两种形态：
 * - [TYPE_POSITION] 位置书签：只记 [chapterIndex] + [chapterPos]，用于跳转；
 * - [TYPE_TEXT_HIGHLIGHT] / [TYPE_TEXT_HIGHLIGHT_CANONICAL] 文字高亮：
 *   用起止偏移框出一段正文，再按 [highlightStyle] / [highlightColor] 渲染。
 *
 * 新增字段全部带 defaultValue：旧库升级（DatabaseMigrations.migration_100_101）
 * 和旧备份包恢复都会落到"位置书签"的默认值上，行为与升级前保持一致。
 */
@Parcelize
@Entity(
    tableName = "bookmarks",
    indices = [(Index(value = ["bookName", "bookAuthor"], unique = false))]
)
data class Bookmark(
    @PrimaryKey
    val time: Long = System.currentTimeMillis(),
    val bookName: String = "",
    val bookAuthor: String = "",
    var chapterIndex: Int = 0,
    var chapterPos: Int = 0,
    var chapterName: String = "",
    var bookText: String = "",
    var content: String = "",
    @SerializedName("bookmarkType")
    @ColumnInfo(defaultValue = "0")
    var bookmarkType: Int = TYPE_POSITION,
    @SerializedName("endChapterIndex")
    @ColumnInfo(defaultValue = "0")
    var endChapterIndex: Int = 0,
    @SerializedName("endChapterPos")
    @ColumnInfo(defaultValue = "0")
    var endChapterPos: Int = 0,
    @SerializedName("highlightStyle")
    @ColumnInfo(defaultValue = "0")
    var highlightStyle: Int = STYLE_BACKGROUND,
    @SerializedName("highlightColor")
    @ColumnInfo(defaultValue = "-32885")
    var highlightColor: Int = DEFAULT_HIGHLIGHT_COLOR,
) : Parcelable {

    /**
     * 是否为可用的文字高亮。
     *
     * 只认"终点确实在起点之后"的记录：起止相同的脏数据（选区被清空、导入异常）
     * 一律退化成位置书签，避免排版层拿到零长度区间画出 0 宽度色块。
     */
    val isTextHighlight: Boolean
        get() = (bookmarkType == TYPE_TEXT_HIGHLIGHT || bookmarkType == TYPE_TEXT_HIGHLIGHT_CANONICAL) &&
            (endChapterIndex > chapterIndex || endChapterPos > chapterPos)

    /** 高亮是否覆盖到指定章节 */
    fun coversChapter(targetChapterIndex: Int): Boolean {
        return isTextHighlight && targetChapterIndex in chapterIndex..endChapterIndex
    }

    /**
     * 高亮在指定章节内的起始偏移。
     *
     * 跨章高亮只在首章从 [chapterPos] 起，其余被覆盖的章节都从 0（章首）起。
     * 调用前应先用 [coversChapter] 过滤，否则返回值没有意义。
     */
    fun highlightStartIn(targetChapterIndex: Int): Int {
        return if (targetChapterIndex == chapterIndex) chapterPos else 0
    }

    /**
     * 高亮在指定章节内的结束偏移（左闭右开）。
     *
     * 跨章高亮只在末章止于 [endChapterPos]，其余被覆盖的章节一直到章尾
     * （[Int.MAX_VALUE]，由排版层按正文长度截断）。
     */
    fun highlightEndIn(targetChapterIndex: Int): Int {
        return if (targetChapterIndex == endChapterIndex) endChapterPos else Int.MAX_VALUE
    }

    /** 指定章节内某个偏移是否落在高亮区间里（左闭右开） */
    fun containsChapterPosition(targetChapterIndex: Int, position: Int): Boolean {
        if (!coversChapter(targetChapterIndex)) return false
        return position >= highlightStartIn(targetChapterIndex) &&
            position < highlightEndIn(targetChapterIndex)
    }

    companion object {
        const val TYPE_POSITION = 0
        const val TYPE_TEXT_HIGHLIGHT = 1

        /**
         * 规范化高亮：偏移是章节正文里的 UTF-16 下标，
         * 不含视觉缩进 / 标题分段带来的偏移。
         */
        const val TYPE_TEXT_HIGHLIGHT_CANONICAL = 2

        const val STYLE_BACKGROUND = 0
        const val STYLE_UNDERLINE = 1
        const val STYLE_WAVY_UNDERLINE = 2

        /** 默认高亮色 #FF7F7F */
        const val DEFAULT_HIGHLIGHT_COLOR: Int = -32885

        /** 可选高亮色，顺序与筛选面板 / 编辑面板的色块一致 */
        val HIGHLIGHT_COLORS = intArrayOf(
            DEFAULT_HIGHLIGHT_COLOR,
            0xFFA78BFA.toInt(),
            0xFF62A5EF.toInt(),
            0xFF65CF81.toInt(),
            0xFFFFC474.toInt(),
        )
    }
}