package io.legado.app.ui.book.toc

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.entities.Bookmark
import io.legado.app.databinding.ItemBookmarkBinding
import io.legado.app.databinding.ItemBookmarkGroupBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.gone
import io.legado.app.utils.getCompatColor
import splitties.views.onClick

/**
 * 目录抽屉「书签」栏的列表：按章节分组，组头可点击折叠 / 展开。
 *
 * 与 [io.legado.app.ui.book.bookmark.AllBookmarkScreen] 的时间线分组是同一套交互语义
 * （折叠集合 + 扁平化行），区别在那边按书分组且已是 Compose，这边按章分组、
 * 仍留在 View 体系（目录抽屉整体尚未迁移）。折叠语义同样参考同抽屉的
 * [ChapterListAdapter]（collapsedVolumes + applyFilter）。
 *
 * 没有沿用 base 的 RecyclerAdapter：它的 getViewBinding 不接收 viewType，
 * 单一 ViewBinding 无法同时承载组头与书签条目两种行。
 *
 * @param callback 点击跳转 [Callback.onClick]，长按编辑 [Callback.onLongClick]。
 *   长按回传的 pos 是书签在**原始列表**里的下标，不是含组头的行号——
 *   BookmarkDialog 用它判断是否处于编辑态，语义上应指向数据本身而非视图位置。
 */
class BookmarkAdapter(
    private val context: Context,
    private val callback: Callback,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** 一行是组头还是书签条目 */
    private sealed interface Row {
        /**
         * 分组头。
         *
         * [collapsed] 为 true 时其下条目不会出现在 [rows] 里（真折叠，不是隐藏视图），
         * 所以不会像旧的 ItemDecoration 方案那样留下空行。
         */
        data class Header(
            val chapterIndex: Int,
            val chapterName: String,
            val count: Int,
            val collapsed: Boolean,
        ) : Row

        /** 书签条目。[sourceIndex] 是它在 [allBookmarks] 里的下标 */
        data class Item(
            val bookmark: Bookmark,
            val sourceIndex: Int,
        ) : Row
    }

    /** 原始书签（未分组、未过滤折叠），用于按章重建分组 */
    private var allBookmarks: List<Bookmark> = emptyList()

    /** 折叠中的章节下标；不在集合里即为展开 */
    private val collapsedChapters = HashSet<Int>()

    /** 扁平化后的行，下标直接对应 RecyclerView 的 position */
    private var rows: List<Row> = emptyList()

    /** 当前阅读章，用于默认只展开这一章。-1 表示还没拿到进度 */
    private var durChapterIndex = -1

    /** 搜索态：为 true 时无视折叠集合，全部章节展开 */
    private var searchActive = false

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows.getOrNull(position) is Row.Header) TYPE_HEADER else TYPE_ITEM

    /**
     * 设置书签数据。
     *
     * 数据来自 Room flow，任何增删改都会重新回调整份列表，所以折叠状态必须存在
     * [collapsedChapters] 里而不是依赖行列表——否则用户折叠后一点编辑就全展开了。
     *
     * @param resetCollapse 是否重算默认折叠。换书 / 首次加载传 true；
     *   编辑单条书签引起的回流传 false，保留用户当前的展开折叠状态。
     */
    @SuppressLint("NotifyDataSetChanged")
    fun setBookmarks(bookmarks: List<Bookmark>, resetCollapse: Boolean) {
        allBookmarks = bookmarks
        if (resetCollapse) resetCollapsedChapters()
        rebuildRows()
        // 折叠会改变行结构（组头下的条目直接消失），无法用 DiffUtil 增量描述，只能整表刷新
        notifyDataSetChanged()
    }

    /** 记录当前阅读章；仅首次拿到进度时据此决定默认展开哪一章 */
    @SuppressLint("NotifyDataSetChanged")
    fun setDurChapterIndex(chapterIndex: Int) {
        if (durChapterIndex == chapterIndex) return
        val firstLoad = durChapterIndex < 0
        durChapterIndex = chapterIndex
        // 之后的进度变化不打扰用户已手动调整过的折叠状态
        if (firstLoad) {
            resetCollapsedChapters()
            rebuildRows()
            notifyDataSetChanged()
        }
    }

    /**
     * 定位到包含指定章节的行号，供 Fragment 滚动。
     *
     * 目标章若处于折叠态会先展开，否则滚过去只看得见组头、看不见书签。
     * 没有恰好等于该章的书签时退到「第一个不早于该章」的行，与原实现语义一致。
     */
    @SuppressLint("NotifyDataSetChanged")
    fun rowIndexOfChapter(chapterIndex: Int): Int {
        if (!searchActive && collapsedChapters.contains(chapterIndex)) {
            collapsedChapters.remove(chapterIndex)
            rebuildRows()
            notifyDataSetChanged()
        }
        val exact = rows.indexOfFirst {
            when (it) {
                is Row.Header -> it.chapterIndex == chapterIndex
                is Row.Item -> it.bookmark.chapterIndex == chapterIndex
            }
        }
        if (exact >= 0) return exact
        return rows.indexOfFirst {
            when (it) {
                is Row.Header -> it.chapterIndex >= chapterIndex
                is Row.Item -> it.bookmark.chapterIndex >= chapterIndex
            }
        }.coerceAtLeast(0)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun expandAll() {
        if (collapsedChapters.isEmpty()) return
        collapsedChapters.clear()
        rebuildRows()
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun collapseAll() {
        allBookmarks.mapTo(collapsedChapters) { it.chapterIndex }
        rebuildRows()
        notifyDataSetChanged()
    }

    /** 是否已无任何展开的书签条目，用于顶栏切换「全部展开 / 全部折叠」文案 */
    fun isAllCollapsed(): Boolean =
        !searchActive && rows.isNotEmpty() && rows.none { it is Row.Item }

    /**
     * 搜索态开关。
     *
     * 搜索时强制展开全部章节：命中结果往往散落在多章，若仍按「只展开当前章」折叠，
     * 用户搜到的书签会被藏进折叠组里，看起来像没搜到。与 [AllBookmarkScreen]
     * 在筛选条件生效时绕过折叠是同一个考虑。
     */
    @SuppressLint("NotifyDataSetChanged")
    fun setSearchActive(active: Boolean) {
        if (searchActive == active) return
        searchActive = active
        rebuildRows()
        notifyDataSetChanged()
    }

    fun isSearchActive(): Boolean = searchActive

    /** 默认折叠：只展开当前阅读章；拿不到进度时全部展开 */
    private fun resetCollapsedChapters() {
        collapsedChapters.clear()
        if (durChapterIndex < 0) return
        allBookmarks.forEach { bookmark ->
            if (bookmark.chapterIndex != durChapterIndex) {
                collapsedChapters.add(bookmark.chapterIndex)
            }
        }
    }

    /** 按章重建扁平化行。用 LinkedHashMap 保留首次出现顺序，不依赖 SQL 排序细节 */
    private fun rebuildRows() {
        val grouped = LinkedHashMap<Int, MutableList<Pair<Int, Bookmark>>>()
        allBookmarks.forEachIndexed { sourceIndex, bookmark ->
            grouped.getOrPut(bookmark.chapterIndex) { ArrayList() }
                .add(sourceIndex to bookmark)
        }
        val result = ArrayList<Row>(allBookmarks.size + grouped.size)
        grouped.forEach { (chapterIndex, members) ->
            val collapsed = !searchActive && collapsedChapters.contains(chapterIndex)
            val name = members.firstOrNull()?.second?.chapterName.orEmpty()
            result.add(
                Row.Header(
                    chapterIndex = chapterIndex,
                    chapterName = name.ifEmpty {
                        "${context.getString(R.string.chapter)} ${chapterIndex + 1}"
                    },
                    count = members.size,
                    collapsed = collapsed,
                )
            )
            if (collapsed) return@forEach
            members.forEach { (sourceIndex, bookmark) ->
                result.add(Row.Item(bookmark, sourceIndex))
            }
        }
        rows = result
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun toggleChapter(chapterIndex: Int) {
        if (!collapsedChapters.remove(chapterIndex)) {
            collapsedChapters.add(chapterIndex)
        }
        rebuildRows()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(ItemBookmarkGroupBinding.inflate(inflater, parent, false))
        } else {
            ItemViewHolder(ItemBookmarkBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows.getOrNull(position)) {
            is Row.Header -> (holder as HeaderViewHolder).bind(row)
            is Row.Item -> (holder as ItemViewHolder).bind(row)
            null -> Unit
        }
    }

    private inner class HeaderViewHolder(
        private val binding: ItemBookmarkGroupBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.tvGroupTitle.setTextColor(context.accentColor)
            binding.tvGroupCount.setTextColor(context.getCompatColor(R.color.secondaryText))
            binding.root.onClick {
                val position = layoutPosition
                if (position == RecyclerView.NO_POSITION) return@onClick
                (rows.getOrNull(position) as? Row.Header)?.let { toggleChapter(it.chapterIndex) }
            }
        }

        fun bind(row: Row.Header) {
            binding.tvGroupTitle.text = row.chapterName
            binding.tvGroupCount.text = row.count.toString()
            // 展开朝上、折叠朝下，与 ChapterListAdapter 的卷箭头一致
            binding.ivGroupArrow.setImageResource(
                if (row.collapsed) R.drawable.ic_expand_more else R.drawable.ic_expand_less
            )
        }
    }

    private inner class ItemViewHolder(
        private val binding: ItemBookmarkBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.onClick {
                val position = layoutPosition
                if (position == RecyclerView.NO_POSITION) return@onClick
                (rows.getOrNull(position) as? Row.Item)?.let { callback.onClick(it.bookmark) }
            }
            // 用原生 setOnLongClickListener 而非 splitties 的 onLongClick：
            // 后者 lambda 返回 Unit、消费与否由参数决定，这里需要显式返回 false，
            // 让长按未命中书签条目时事件继续交给系统处理。
            binding.root.setOnLongClickListener {
                val position = layoutPosition
                if (position == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                val row = rows.getOrNull(position) as? Row.Item
                    ?: return@setOnLongClickListener false
                callback.onLongClick(row.bookmark, row.sourceIndex)
                true
            }
        }

        fun bind(row: Row.Item) {
            val bookmark = row.bookmark
            // 章节名已由组头承载，条目内不再重复显示
            binding.tvChapterName.gone(true)
            binding.tvBookText.gone(bookmark.bookText.isEmpty())
            binding.tvBookText.text = bookmark.bookText
            binding.tvContent.gone(bookmark.content.isEmpty())
            binding.tvContent.text = bookmark.content
        }
    }

    interface Callback {
        fun onClick(bookmark: Bookmark)
        fun onLongClick(bookmark: Bookmark, pos: Int)
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1
    }
}