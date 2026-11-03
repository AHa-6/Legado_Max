package io.legado.app.ui.book.read.page.entities

import androidx.annotation.Keep

/**
 * 「这条划线带备注」的行末标识。
 *
 * 排版期由 TextChapterLayout 算好并挂到所属 [TextLine] 上，绘制期 TextLine 只按这里的
 * 坐标画一个折角三角，点击命中也用它做判定——三处共用同一份数据，
 * 不会出现"看得见点不着"或"点得着看不见"。
 *
 * 只在**高亮真正结束的那一行**挂一个：跨行、跨章的长划线如果每行都画，
 * 页面右侧会有一整列三角，反而看不出哪个是末端。跨章高亮只在末章
 * （chapterIndex == endChapterIndex）出标识，见 TextChapterLayout.collectNoteMarks。
 *
 * @param time 所属书签的主键，点击后据此定位记录
 * @param color 书签的高亮色，标识与划线同色，视觉上属于同一条
 * @param tipX 三角尖角的 x（划线末字符的右边缘，页面绝对坐标，与列的 start/end 同口径）
 * @param top 三角上边的 y。**行内坐标系**：以行盒顶部为 0，
 *        排版期取 `height - size`，即三角贴着行盒下沿（正文基线以下的降部带），
 *        与 NG 的 `lineBottom - markerSize` 等价，只是 NG 用的是页面绝对坐标
 * @param size 三角边长（px）
 * @param hitLeft/hitTop/hitRight/hitBottom 点击热区，同样是**行内坐标系**。
 *        三角本身只有 5dp 见方，远小于手指，热区按 [MIN_HIT_SIZE_DP] 向四周外扩。
 *
 * 坐标口径提醒：x 是页面绝对坐标，y 是行内相对坐标——这与 [TextLine] 的绘制口径一致
 * （`TextPage.drawPage` 对每行做了 `translate(0, lineTop)`）。所以命中测试时
 * x 可直接比，y 必须先加上该行的 `lineTop`（[TextLine.isTouch] 还要再加 `relativeOffset`），
 * 直接拿视图坐标的 y 和这里的 hitTop 比会整页偏移一个行距。
 */
@Keep
data class BookmarkNoteMark(
    val time: Long,
    val color: Int,
    val tipX: Float,
    val top: Float,
    val size: Float,
    val hitLeft: Float,
    val hitTop: Float,
    val hitRight: Float,
    val hitBottom: Float,
) {

    /** 命中判定：热区内即算点中标识（优先于划线本体的命中） */
    fun isTouch(x: Float, y: Float): Boolean {
        return x in hitLeft..hitRight && y in hitTop..hitBottom
    }

    companion object {
        /** 三角边长（dp）：再小看不清，再大会盖住正文 */
        const val SIZE_DP = 5f

        /** 点击热区最小边长（dp）。三角只有 [SIZE_DP] 见方，远小于手指，
         *  热区必须外扩；但标识贴着行末，热区再大就会吃掉相邻字符的点击，
         *  故取 18dp——比 Material 建议的 48dp 触摸目标小，是在"点得到"与
         *  "不误触正文"之间的折中。 */
        const val MIN_HIT_SIZE_DP = 18f
    }
}