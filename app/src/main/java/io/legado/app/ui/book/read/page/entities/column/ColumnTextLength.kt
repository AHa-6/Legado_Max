package io.legado.app.ui.book.read.page.entities.column

/**
 * 一个列在章节正文坐标里占多少个 UTF-16 字符。
 *
 * 章节坐标（`TextLine.chapterPosition`、`Bookmark.chapterPos`）是对 **`TextLine.text`**
 * 这个字符串编号的，而 `text` 的构建方式是 `lineText.append(原字符)`——行内图片、
 * 段评、自定义标签这些在排版时会被换成 `ImageColumn`/`ButtonColumn` 之类的占位列，
 * **但它们在 `text` 里仍然各占一个字符**（源码里那一格是 `srcReplaceStr`、`reviewStr`、
 * `HR_PLACE_CHAR` 这类占位符）。
 *
 * 所以列的字符宽度不能只看 `TextBaseColumn`：那样算出来的偏移每遇一张行内图片就少一格，
 * 整个点往后所有偏移都错位。这里的口径是：
 * - 文字列按 [TextBaseColumn.charData] 的实际长度（通常 1，全角符号/emoji 可能更多）；
 * - 图片、按钮、段评列各算 1（它们只替换了一个占位字符）；
 * - 其他返回 0。
 *
 * 两处必须共用同一份实现，否则「写入」与「渲染」两套坐标会悄悄分叉：
 * - `TextPage.getPosByLineColumn`（选区 → 章节坐标，书签写入侧）；
 * - `TextChapterLayout`（章节坐标 → 页面 x，高亮与备注标识的渲染侧）。
 */
internal fun BaseColumn.textLengthInChapter(): Int = when (this) {
    is TextBaseColumn -> charData.length
    is ImageColumn, is ReviewColumn, is ButtonColumn -> 1
    else -> 0
}