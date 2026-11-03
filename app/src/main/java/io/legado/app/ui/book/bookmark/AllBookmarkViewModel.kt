package io.legado.app.ui.book.bookmark

import android.app.Application
import android.net.Uri
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.utils.FileDoc
import io.legado.app.utils.GSON
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeToOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AllBookmarkViewModel(application: Application) : BaseViewModel(application) {

    /**
     * 批量选中的书签主键（[Bookmark.time]）集合，非空即表示处于批量管理态。
     *
     * 用主键而不是整个 Bookmark 承载选中态：书签内容可被编辑弹窗改写，
     * 改写后旧对象与列表里的新对象不再 equals，选中态会静默丢失；time 不受编辑影响。
     */
    private val _selectedTimes = MutableStateFlow<Set<Long>>(emptySet())
    val selectedTimes: StateFlow<Set<Long>> = _selectedTimes.asStateFlow()

    /** 待确认删除的书签，null 表示确认弹窗不显示（弹窗由 ViewModel 状态条件渲染） */
    private val _deleteConfirm = MutableStateFlow<List<Bookmark>?>(null)
    val deleteConfirm: StateFlow<List<Bookmark>?> = _deleteConfirm.asStateFlow()

    fun toggleSelected(time: Long) {
        _selectedTimes.update { times -> if (time in times) times - time else times + time }
    }

    fun setSelected(times: Set<Long>) {
        _selectedTimes.value = times.toSet()
    }

    fun clearSelection() {
        _selectedTimes.value = emptySet()
    }

    /** 列表刷新后剔除已不存在的选中项，避免选中态里留着已被别处删掉的书签 */
    fun pruneInvalidSelection(bookmarks: List<Bookmark>) {
        val validTimes = bookmarks.mapTo(linkedSetOf()) { it.time }
        _selectedTimes.update { times -> times.filterTo(linkedSetOf()) { it in validTimes } }
    }

    /** 请求弹出删除确认弹窗 */
    fun requestDelete(bookmarks: List<Bookmark>) {
        _deleteConfirm.value = bookmarks
    }

    fun dismissDeleteConfirm() {
        _deleteConfirm.value = null
    }

    /**
     * 批量删除。
     *
     * DAO 的 delete 是 @Delete(vararg)，Room 会放进同一个事务，
     * 因此不做逐条循环：bookmarks 表没有级联关系，逐条只会放大 IO 与失效通知次数。
     * 删除结果由 flowAll() 自动回流，这里只需清掉对应的选中项。
     */
    fun deleteBookmarks(bookmarks: List<Bookmark>) {
        if (bookmarks.isEmpty()) {
            dismissDeleteConfirm()
            return
        }
        execute {
            appDb.bookmarkDao.delete(*bookmarks.toTypedArray())
        }.onSuccess {
            context.toastOnUi(R.string.delete_success)
            val deleted = bookmarks.mapTo(linkedSetOf()) { it.time }
            _selectedTimes.update { times -> times - deleted }
            dismissDeleteConfirm()
        }.onError {
            AppLog.put("删除书签失败\n${it.localizedMessage}", it, true)
            dismissDeleteConfirm()
        }
    }


    /**
     * 导出书签
     */
    fun exportBookmark(treeUri: Uri) {
        execute {
            val dateFormat = SimpleDateFormat("yyMMddHHmmss", Locale.getDefault())
            val fileName = "bookmark-${dateFormat.format(Date())}.json"
            val dirDoc = FileDoc.fromUri(treeUri, true)
            dirDoc.createFileIfNotExist(fileName).openOutputStream().getOrThrow().use {
                GSON.writeToOutputStream(it, appDb.bookmarkDao.all)
            }
        }.onError {
            AppLog.put("导出失败\n${it.localizedMessage}", it, true)
        }.onSuccess {
            context.toastOnUi("导出成功")
        }
    }


    fun exportBookmarkMd(treeUri: Uri) {
        execute {
            val dateFormat = SimpleDateFormat("yyMMddHHmmss", Locale.getDefault())
            val fileName = "bookmark-${dateFormat.format(Date())}.md"
            val dirDoc = FileDoc.fromUri(treeUri, true)
            val fileDoc = dirDoc.createFileIfNotExist(fileName).openOutputStream().getOrThrow()
            fileDoc.use { outputStream ->
                var name = ""
                var author = ""
                appDb.bookmarkDao.all.forEach {
                    if (it.bookName != name && it.bookAuthor != author) {
                        name = it.bookName
                        author = it.bookAuthor
                        outputStream.write("## ${it.bookName} ${it.bookAuthor}\n\n".toByteArray())
                    }
                    outputStream.write("#### ${it.chapterName}\n\n".toByteArray())
                    outputStream.write("###### 原文\n ${it.bookText}\n\n".toByteArray())
                    outputStream.write("###### 摘要\n ${it.content}\n\n".toByteArray())
                }
            }
        }.onError {
            AppLog.put("导出失败\n${it.localizedMessage}", it, true)
        }.onSuccess {
            context.toastOnUi("导出成功")
        }
    }

}