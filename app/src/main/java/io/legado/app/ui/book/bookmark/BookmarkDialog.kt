package io.legado.app.ui.book.bookmark

import android.os.Bundle
import android.view.View
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.ui.widget.components.dialog.BaseComposeDialogFragment
import io.legado.app.utils.postEvent
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书签编辑对话框。
 *
 * 调用方式与旧 View 版完全一致：`BookmarkDialog(bookmark)` 新建、
 * `BookmarkDialog(bookmark, editPos)` 编辑（editPos >= 0 时显示删除按钮），
 * 阅读页、目录页书签列表、所有书签页三处调用点都不用改。
 *
 * UI 见 [BookmarkDialogContent]，这里只负责取参数与落库。
 */
class BookmarkDialog() : BaseComposeDialogFragment() {

    constructor(bookmark: Bookmark, editPos: Int = -1) : this() {
        arguments = Bundle().apply {
            putInt("editPos", editPos)
            putParcelable("bookmark", bookmark)
        }
    }

    private var bookmark: Bookmark? = null
    private var isEditing = false

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val arguments = arguments ?: run {
            dismiss()
            return
        }
        @Suppress("DEPRECATION")
        val currentBookmark = arguments.getParcelable<Bookmark>("bookmark") ?: run {
            dismiss()
            return
        }
        bookmark = currentBookmark
        isEditing = arguments.getInt("editPos", -1) >= 0
    }

    @Composable
    override fun DialogContent() {
        // 组合期直接读字段：onFragmentCreated 在 onViewCreated 里跑，早于首次组合，
        // 走到这里为 null 只可能是参数本身丢了（那种情况已经 dismiss）
        val currentBookmark = bookmark ?: return
        BookmarkDialogContent(
            bookmark = currentBookmark,
            showDelete = isEditing,
            onCancel = { dismiss() },
            onConfirm = ::saveBookmark,
            onDelete = ::deleteBookmark,
        )
    }

    private fun saveBookmark(bookText: String, content: String, style: Int, color: Int) {
        val currentBookmark = bookmark ?: return
        currentBookmark.bookText = bookText
        currentBookmark.content = content
        // 位置书签不写样式：没有区间可渲染，写进去只会让导出 JSON 里多出无意义的字段变化
        if (currentBookmark.isTextHighlight) {
            currentBookmark.highlightStyle = style
            currentBookmark.highlightColor = color
        }
        lifecycleScope.launch {
            withContext(IO) {
                appDb.bookmarkDao.insert(currentBookmark)
            }
            notifyReaderRefresh()
            dismiss()
        }
    }

    private fun deleteBookmark() {
        val currentBookmark = bookmark ?: return
        lifecycleScope.launch {
            withContext(IO) {
                appDb.bookmarkDao.delete(currentBookmark)
            }
            notifyReaderRefresh()
            dismiss()
        }
    }

    /**
     * 通知阅读页重排版。
     *
     * 书签高亮是在**排版期**并入字符样式数组的（见 TextChapterLayout.mergeBookmarkStyles），
     * 已排好的页面不会自己重画，所以增删改后必须让当前章重新排版。
     * UP_CONFIG 的 5 走 `ReadBook.loadContent(forceReload = true)`，与切换高亮规则、
     * 夜间模式用的是同一条通道。
     *
     * 只在弹窗是被阅读页拉起来时才有意义（其它入口如「所有书签」页没有 curTextChapter），
     * postEvent 本身对没有观察者的场景是空操作，不用额外判断。
     */
    private fun notifyReaderRefresh() {
        postEvent(EventBus.UP_CONFIG, arrayListOf(5))
    }
}