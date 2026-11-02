package io.legado.app.ui.book.bookmark

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivityForBook
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 所有书签。
 *
 * View 版（RecyclerView + BookmarkAdapter + BookmarkDecoration 自绘分组头）已换成 Compose：
 * [AllBookmarkScreen] 负责时间线分组、搜索与筛选，这里只做数据收集与动作落地。
 *
 * 入口不变：MyFragment 的 "bookmark" 项与 AndroidManifest 里的注册都指向本类。
 */
class AllBookmarkActivity : BaseComposeActivity() {

    private val viewModel by viewModels<AllBookmarkViewModel>()

    private val exportDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            when (it.requestCode) {
                1 -> viewModel.exportBookmark(uri)
                2 -> viewModel.exportBookmarkMd(uri)
            }
        }
    }

    @Composable
    override fun ComposeContent() {
        // 直接收 DAO 的 Flow：书签的增删改都走 insert/delete，Room 会自动重发，
        // 不需要再引一层 ViewModel 状态（导出逻辑仍在 AllBookmarkViewModel 里）
        val bookmarks by remember {
            appDb.bookmarkDao.flowAll()
                .catch {
                    AppLog.put("所有书签界面获取数据失败\n${it.localizedMessage}", it)
                }
                .flowOn(IO)
        }.collectAsState(initial = emptyList())
        var query by rememberSaveable { mutableStateOf("") }

        AllBookmarkScreen(
            bookmarks = bookmarks,
            query = query,
            onAction = { action -> handleAction(action) { query = it } },
        )
    }

    /** 页面动作 → Activity 侧的实际行为（跳转、弹窗、导出） */
    private fun handleAction(
        action: AllBookmarkScreenAction,
        setQuery: (String) -> Unit,
    ) {
        when (action) {
            AllBookmarkScreenAction.Back -> finish()
            is AllBookmarkScreenAction.QueryChanged -> setQuery(action.query)
            AllBookmarkScreenAction.ExportJson -> exportDir.launch { requestCode = 1 }
            AllBookmarkScreenAction.ExportMarkdown -> exportDir.launch { requestCode = 2 }
            is AllBookmarkScreenAction.Open -> openBookmark(action.bookmark, action.position)
            is AllBookmarkScreenAction.Edit -> showDialogFragment(
                BookmarkDialog(action.bookmark, action.position)
            )
        }
    }

    /**
     * 跳转到书签位置。
     *
     * 文字高亮也按起点 chapterPos 跳：Max 的阅读侧还没有"按书签 time 定位并滚动到高亮"的通道
     * （NG 是靠 chapterPos=0 + bookmarkTime extra 实现的），起点定位对高亮同样是正确落点，
     * 少改一处阅读页状态机。等阅读侧支持 bookmarkTime 再换成 NG 的方式。
     *
     * 书已被删除时退回编辑弹窗：用户至少还能看到原文与备注，不至于点了没反应。
     */
    private fun openBookmark(bookmark: Bookmark, position: Int) {
        lifecycleScope.launch {
            val book = withContext(IO) {
                appDb.bookDao.getBook(bookmark.bookName, bookmark.bookAuthor)
            }
            if (book == null) {
                showDialogFragment(BookmarkDialog(bookmark, position))
            } else {
                startActivityForBook(book) {
                    putExtra("index", bookmark.chapterIndex)
                    putExtra("chapterPos", bookmark.chapterPos)
                }
            }
        }
    }
}