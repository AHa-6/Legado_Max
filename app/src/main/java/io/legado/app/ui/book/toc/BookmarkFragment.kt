package io.legado.app.ui.book.toc

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.databinding.FragmentBookmarkBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.widget.recycler.UpLinearLayoutManager
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch


class BookmarkFragment : VMBaseFragment<TocViewModel>(R.layout.fragment_bookmark),
    BookmarkAdapter.Callback,
    TocViewModel.BookmarkCallBack {
    override val viewModel by activityViewModels<TocViewModel>()
    private val binding by viewBinding(FragmentBookmarkBinding::bind)
    private val mLayoutManager by lazy { UpLinearLayoutManager(requireContext()) }
    private val adapter by lazy { BookmarkAdapter(requireContext(), this) }
    private var durChapterIndex = 0
    /** 是否已加载过书签：首次加载才重算默认折叠，之后保留用户手动调整的展开状态 */
    private var hasLoaded = false
    private val viewScope get() = viewLifecycleOwner.lifecycleScope

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        viewModel.bookMarkCallBack = this
        initRecyclerView()
        viewModel.bookData.observe(this) {
            durChapterIndex = it.durChapterIndex
            // 分组默认只展开当前阅读章，需要先把进度交给 adapter
            adapter.setDurChapterIndex(durChapterIndex)
            upBookmark(null)
        }
    }

    private fun initRecyclerView() {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.layoutManager = mLayoutManager
        binding.recyclerView.addItemDecoration(VerticalDivider(requireContext()))
        binding.recyclerView.adapter = adapter
        binding.recyclerView.applyNavigationBarPadding()
    }

    override fun upBookmark(searchKey: String?) {
        val book = viewModel.bookData.value ?: return
        viewScope.launch {
            when {
                searchKey.isNullOrBlank() -> appDb.bookmarkDao.flowByBook(book.name, book.author)
                else -> {
                    val hasChapter = viewModel.searchChapterName
                    val hasBookText = viewModel.searchBookText
                    val hasContent = viewModel.searchContent
                    when {
                        hasChapter && hasBookText && hasContent -> appDb.bookmarkDao.flowSearch(book.name, book.author, searchKey)
                        hasChapter -> appDb.bookmarkDao.flowSearchChapter(book.name, book.author, searchKey)
                        hasBookText -> appDb.bookmarkDao.flowSearchBookText(book.name, book.author, searchKey)
                        hasContent -> appDb.bookmarkDao.flowSearchContent(book.name, book.author, searchKey)
                        else -> appDb.bookmarkDao.flowByBook(book.name, book.author)
                    }
                }
            }.catch {
                AppLog.put("目录界面获取书签数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect {
                // 搜索时强制展开全部章节，否则命中结果会被折叠组藏起来
                adapter.setSearchActive(!searchKey.isNullOrBlank())
                adapter.setBookmarks(it, resetCollapse = !hasLoaded)
                hasLoaded = true
                // 定位到当前阅读章（若该章处于折叠态会自动展开）。
                // 原实现滚到「当前章之前的最后一条书签」，分组后那个位置多半是折叠组头，
                // 滚过去看不见任何书签，故改为对准当前章本身。
                val scrollPos = adapter.rowIndexOfChapter(durChapterIndex)
                mLayoutManager.scrollToPositionWithOffset(scrollPos, 0)
            }
        }
    }


    override fun toggleBookmarkGroup() {
        if (adapter.isAllCollapsed()) adapter.expandAll() else adapter.collapseAll()
    }

    override fun isBookmarkAllCollapsed(): Boolean = adapter.isAllCollapsed()

    override fun onClick(bookmark: Bookmark) {
        activity?.run {
            setResult(Activity.RESULT_OK, Intent().apply {
                putExtra("index", bookmark.chapterIndex)
                putExtra("chapterPos", bookmark.chapterPos)
            })
            finish()
        }
    }

    override fun onLongClick(bookmark: Bookmark, pos: Int) {
        showDialogFragment(BookmarkDialog(bookmark, pos))
    }

}