package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.PopupWindow
import androidx.annotation.RequiresApi
import androidx.appcompat.view.SupportMenuInflater
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.view.menu.MenuItemImpl
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.toastOnUi
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 卡片圆角，对应 shape_card_view.xml 的 3dp */
private val CardCornerRadius = 3.dp

/** 卡片内边距，对应 popup_action_menu.xml 根节点的 5dp */
private val CardPadding = 5.dp

/** 菜单项图标尺寸，旧实现按 18 * density 手工绘制位图 */
private val MenuIconSize = 18.dp

/** 菜单项字号，对应 item_text.xml 的 14sp */
private val MenuTextSize = 14.sp

/** 菜单项左右内边距，旧实现用 setPaddingRelative(4dp, 0, 4dp, 0) 覆盖了 XML 的 5dp */
private val MenuCellHorizontalPadding = 4.dp

/** 「更多」按钮尺寸，对应 iv_menu_more 的 24dp */
private val MoreButtonSize = 24.dp

/**
 * `PopupWindow.update(x, y, width, height)` 的「保持原尺寸」取值。
 *
 * 拖动菜单时只改位置，尺寸必须留给 WRAP_CONTENT 自己算——菜单内容会随「更多」
 * 展开而变高，写死宽高会把展开后的内容裁掉。
 */
private const val KEEP_SIZE = -1

/**
 * 划线面板的最大宽度（dp），必须与 TextHighlightPanel 的 `PanelMaxWidth` 一致。
 *
 * 这里需要它来把面板横向居中：popup 是 WRAP_CONTENT，showAtLocation 之前量不到实际宽度，
 * 只能按上界估。估大了只是居中略偏，不会跑出屏幕（后面还有 clamp）。
 */
private const val PANEL_MAX_WIDTH_DP = 300

/** 面板与点击位置之间留的间隙（dp），避免面板正好压住被点的那行字 */
private const val highlightAnchorGap = 8

/**
 * 文本操作菜单
 *
 * 功能说明：
 * 1. 长按文本后显示的弹出菜单，提供复制、分享、朗读、书签、替换、搜索等操作
 * 2. 支持集成系统文本处理菜单（Android 6.0+），如翻译、搜索等第三方应用
 * 3. 支持展开/收起更多菜单项
 * 4. 继承自 PopupWindow，以弹出窗口形式显示
 *
 * 使用场景：
 * - 阅读界面长按文本选择后显示
 * - 提供对选中文本的各种操作功能
 *
 * ## 为什么外壳仍是 PopupWindow 而非 Compose 的 DropdownMenu / Popup
 * [show] 的两套坐标（上方空间充裕时 `Gravity.BOTTOM` + `windowHeight - startTopY` 向上生长，
 * 否则 `Gravity.TOP` + `startBottomY` 向下生长）是「菜单永不遮挡选中文字」的全部实现。
 * 换成 Compose 的 `PopupPositionProvider` 会改变测量与定位时机，需要把这三段锚点推算重推一遍，
 * 而收益为零——所以外壳保持原样，**只把内容层换成一个 [ComposeView]**。
 *
 * ## 状态为什么放在 [UiState]（组合之外）
 * `ComposeView` 在 popup dismiss 时随窗口分离、组合销毁，下次 show 重新组合。状态放在组合之外，
 * 既能跨 attach/detach 存活，又因为 [reloadMenuItems] 每次 show 都整体覆写五个字段，
 * 保证「在 [TextMenuConfig] 里改了设置、下次长按立刻生效」的行为不丢。
 */
@SuppressLint("RestrictedApi")
class TextActionMenu(private val context: Context, private val callBack: CallBack) :
    PopupWindow(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {

    companion object {
        /** 获取内置菜单项的图标资源 ID */
        fun getMenuItemIconId(itemId: Int): Int? {
            return when (itemId) {
                R.id.menu_replace -> R.drawable.ic_cfg_replace
                R.id.menu_copy -> R.drawable.ic_copy
                R.id.menu_bookmark -> R.drawable.ic_bookmark
                R.id.menu_aloud -> R.drawable.ic_read_aloud
                R.id.menu_dict -> R.drawable.ic_translate
                R.id.menu_share_image -> R.drawable.ic_image
                R.id.menu_web_search -> R.drawable.ic_web
                R.id.menu_text_menu_config -> R.drawable.ic_settings
                R.id.menu_highlight_rule -> R.drawable.ic_interface_setting
                R.id.menu_search_content -> R.drawable.ic_search
                R.id.menu_browser -> R.drawable.ic_web_outline
                R.id.menu_share_str -> R.drawable.ic_share
                else -> null
            }
        }
    }

    /** 内容层容器，整块菜单的 Compose 根节点 */
    private val composeView = ComposeView(context)

    /**
     * popup 窗口真正的根视图（`PopupWindow` 的 decor），在 [show] 里赋值。
     *
     * Compose 建 recomposer 时并不是从 [composeView] 自己往上找 owner，而是先定位
     * `findViewTreeComposeViewRoot()`——它会走到本窗口里**带 owner 标签的最高一层**，
     * 一个都找不到就一路走到窗口根。PopupWindow 的 decor 天生不带任何 owner 标签，
     * 于是 owner 必须装在 decor 上，而不是装在 [composeView] 上：装在 [composeView]
     * 上时 Compose 往上一跳就越过了它，最终从 decor 那里查不到，抛出
     * `ViewTreeLifecycleOwner not found from android.widget.PopupWindow$PopupDecorView`。
     */
    private var popupRoot: View? = null
/** 菜单展示状态，刻意活在组合之外，见类注释 */
    private val uiState = UiState()

    // ─────────────── 划线操作态 ───────────────
    // 点击已有划线时菜单换成「划线面板」：改备注、改样式颜色、删除都在菜单内完成，
    // 不再弹 BookmarkDialog。形态与 NG 一致（TextHighlightPopupMode 两态），
    // 面板 UI 见 TextHighlightPanel.kt。
    //
    // 状态同样放在组合之外：popup 内容跨「拖动移窗」「更多展开」多次重组，
    // 草稿若活在 remember 里会在重组时丢。

    /** 当前正在操作的划线，null 表示菜单处于普通文字选择形态 */
    private val textHighlightState = mutableStateOf<Bookmark?>(null)

    /** 面板形态：工具栏 / 备注预览 */
    private val textHighlightPopupModeState = mutableStateOf(TextHighlightPopupMode.TOOLBAR)

    /** 备注输入框是否展开（展开时要放行输入法，见 [setNoteEditorVisible]） */
    private val noteEditorVisibleState = mutableStateOf(false)

    /** 备注草稿，收起输入框或关窗时统一提交 */
    private val noteDraftState = mutableStateOf("")

    /**
     * 划线菜单「代号」，每次开一个新的划线菜单 / 每次关窗都自增。
     *
     * 用途见 [createTextHighlight]：存盘是挂起操作，回来时必须确认菜单还是原来那个。
     */
    private var highlightMenuGeneration = 0


    /** 是否展开文本菜单（展开时不分页、不显示更多按钮） */
    private val expandTextMenu get() = context.getPrefBoolean(PreferKey.expandTextMenu)

    /** 隐藏的菜单项 ID 集合，每次都从配置中读取，保证改动立即生效 */
    private val hiddenMenuItemIds: Set<Int>
        get() = TextMenuConfig.getHiddenMenuItemIds(context)

    // ─────────────── 拖动 ───────────────
    // 菜单可以拖着走：选中一段文字后菜单常常正好压住要看的上下文，
    // 只能「关掉菜单 → 翻页 → 重新选择」三步才能看到被盖住的那几行。
    //
    // 实现分工与 NG 一致：Compose 侧只识别手势、回报**增量**（[-dragBy]），
    // 真正移窗交给 [PopupWindow.update]。不自己挪 ComposeView 的 translate，
    // 因为 popup 的窗口位置才是触摸分发的输入，只改视图偏移会出现「看着移了、
    // 点击还落在原处」的错位。
    //
    // 坐标口径：PopupWindow 的 y 随 gravity 变号。`Gravity.BOTTOM` 下 y 从父容器
    // 底部往上量，手指往下拖反而要减 y；`Gravity.TOP` 下才与屏幕方向一致。
    // 因此记住 [menuAnchoredToBottom]，在 [-dragBy] 里翻转 dy。

    /** show 时用的定位 gravity 是否为 `Gravity.BOTTOM`（决定 dy 是否翻转） */
    private var menuAnchoredToBottom = false

    /** 当前窗口偏移，口径与 showAtLocation 的 x/y 完全一致 */
    private var menuOffsetX = 0
    private var menuOffsetY = 0

    /** 拖动累加器，浮点保存，避免整数取整后拖动变涩 */
    private var dragAccumX = 0f
    private var dragAccumY = 0f

    /**
     * 本次显示的初始偏移。
     *
     * 用途是把初始位置纳入 clamp 上界：选区贴近屏幕右缘时 `startX` 本身就可能超出
     * 「屏宽 - 菜单宽」，若不放宽，第一次拖动会把菜单从原位硬拽回范围内，看着像跳了一下。
     * 放宽后菜单始终至少能停在它被弹出时的位置。
     */
    private var dragInitialX = 0
    private var dragInitialY = 0

    /** 拖动边界，show 时记录：可用宽度与父容器高度 */
    private var dragRootWidth = 0
    private var dragParentHeight = 0

    init {
        contentView = composeView

        // 设置弹出窗口属性
        isTouchable = true      // 可触摸
        isOutsideTouchable = false  // 点击外部不关闭
        isFocusable = false     // 不获取焦点

        // 菜单消失时回到第一页。旧实现要复位图标、隐藏更多列表、恢复主列表三件事，
        // 在声明式写法里这三件都由 showingMore 一个布尔推导出来
        setOnDismissListener {
            uiState.showingMore = false
            // 关窗时把备注草稿提交掉：用户可能输到一半就点了菜单外面，
            // 那段输入不该丢。提交会触发一次重排版，但内容没变时
            // commitTextHighlightNote 内部会跳过。
            //
            // 顺序上必须先提交再换代/清状态：提交要读 textHighlightState，
            // 清了就等于没提交。
            commitTextHighlightNote()
            highlightMenuGeneration++
            textHighlightState.value = null
            textHighlightPopupModeState.value = TextHighlightPopupMode.TOOLBAR
            noteEditorVisibleState.value = false
            noteDraftState.value = ""
            isFocusable = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            // 不及时放开会对已 dismiss 的窗口根保持强引用
            popupRoot = null
        }

        composeView.setContent {
            LegadoTheme {
                TextActionMenuContent()
            }
        }

        reloadMenuItems()
    }

    /**
     * 重新加载菜单项
     * 从配置中读取隐藏的菜单项，重新构建菜单列表
     */
    private fun reloadMenuItems() {
        // 构建菜单项
        val myMenu = MenuBuilder(context)      // 自定义菜单
        val otherMenu = MenuBuilder(context)    // 系统菜单（Android 6.0+）
        SupportMenuInflater(context).inflate(R.menu.content_select_action, myMenu)
        myMenu.visibleItems.forEach { menuItem ->
            TextMenuConfig.getCustomMenuTitle(context, menuItem.itemId)?.let { customTitle ->
                menuItem.title = customTitle
            }
        }

        // Android 6.0+ 支持系统文本处理菜单
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            onInitializeMenu(otherMenu)
        }

        // 合并自定义菜单和系统菜单
        val allMenuItems = myMenu.visibleItems + otherMenu.visibleItems

        // 根据用户自定义排序重排菜单项
        val order = TextMenuConfig.getMenuItemOrder(context)
        val orderedMenuItems = if (order.isNotEmpty()) {
            val itemMap = allMenuItems.associateBy { it.itemId }
            val ordered = order.mapNotNull { id -> itemMap[id] }
            val remaining = allMenuItems.filter { it.itemId !in order }
            ordered + remaining
        } else {
            allMenuItems
        }

        // 过滤掉被隐藏的菜单项，并一次性解析成展示条目（图标解析在组合之外做，避免每次重组重复解码）
        val hidden = hiddenMenuItemIds
        val entries = orderedMenuItems
            .filter { it.itemId !in hidden }
            .map { it.toMenuEntry() }

        // 将菜单项分为可见项（前 N 项）和更多项（第 N 项之后）
        val visibleCount = TextMenuConfig.getTextMenuVisibleCount(context)
        uiState.allItems = entries
        if (entries.size > visibleCount) {
            uiState.visibleItems = entries.subList(0, visibleCount).toList()
            uiState.moreItems = entries.subList(visibleCount, entries.size).toList()
        } else {
            // 如果菜单项少于 N 个，全部显示在主菜单
            uiState.visibleItems = entries
            uiState.moreItems = emptyList()
        }
        uiState.expanded = expandTextMenu
    }

    /**
     * 更新菜单显示状态
     * 根据配置决定是展开显示所有菜单项，还是折叠显示前 N 项。
     *
     * 菜单正在显示时也会被 [io.legado.app.ui.book.read.config.MoreConfigDialog] 调用，
     * 因此这里只重算状态、不碰视图——重组会自动把新状态画出来。
     */
    fun upMenu() {
        reloadMenuItems()
    }

    /**
     * 显示文本操作菜单
     *
     * @param view 父视图
     * @param windowHeight 窗口高度
     * @param startX 选择起始点X坐标
     * @param startTopY 选择起始点顶部Y坐标
     * @param startBottomY 选择起始点底部Y坐标
     * @param endX 选择结束点X坐标
     * @param endBottomY 选择结束点底部Y坐标
     *
     * 显示策略（不依赖 contentView.measure，避免测量不准）：
     * - 上方空间充裕（>300px）→ Gravity.BOTTOM，菜单向上生长，绝不遮挡选中文字
     * - 接近页眉 → Gravity.TOP at startBottomY，菜单紧贴选中文字起始位置下方
     */
    fun show(
        view: View,
        windowHeight: Int,
        startX: Int,
        startTopY: Int,
        startBottomY: Int,
        endX: Int,
        endBottomY: Int
    ) {
        upMenu()
        if (startTopY > 300) {
            // 上方空间充裕：菜单底部对齐选中文字顶部，向上生长，绝不遮挡
            menuAnchoredToBottom = true
            menuOffsetX = startX
            menuOffsetY = windowHeight - startTopY
            showAtLocation(
                view,
                Gravity.BOTTOM or Gravity.START,
                menuOffsetX,
                menuOffsetY
            )
        } else {
            // 接近页眉：菜单紧贴选中文字起始位置下方，向下生长
            menuAnchoredToBottom = false
            menuOffsetX = startX
            menuOffsetY = startBottomY
            showAtLocation(
                view,
                Gravity.TOP or Gravity.START,
                menuOffsetX,
                menuOffsetY
            )
        }
        // 每次显示都把拖动累加器对齐到**当前实际位置**，而不是清零。
        // 清零会让第一次拖动直接把菜单甩到 (0,0)：菜单初始在 startX，累加器从 0 起步，
        // 两者不是一回事。NG 用的是 `toolbarDragX = toolbarX.toFloat()`，同一个道理。
        dragAccumX = menuOffsetX.toFloat()
        dragAccumY = menuOffsetY.toFloat()
        dragInitialX = menuOffsetX
        dragInitialY = menuOffsetY
        dragParentHeight = windowHeight
        dragRootWidth = view.rootView.width.takeIf { it > 0 }
            ?: context.resources.displayMetrics.widthPixels
        // owner 必须装在 popup 自己的窗口根（decor）上，原因见 popupRoot 注释。
        //
        // 时序是安全的：decor 只在 showAtLocation 内部才被创建并接管 composeView，而 Compose
        // 的首次组合发生在下一次 traversal（Choreographer 帧回调，正是崩溃栈里
        // ViewRootImpl.performTraversals → dispatchAttachedToWindow 那条路径），晚于本方法返回，
        // 所以这里补装 owner 赶得上，不必提前。
        popupRoot = composeView.rootView
        popupRoot?.installPopupViewTreeOwners(view)
    }

    /**
     * 拖动菜单窗口。
     *
     * @param deltaX 本次手势增量（屏幕坐标，向右为正）
     * @param deltaY 本次手势增量（屏幕坐标，向下为正）
     *
     * 增量累加进 [dragAccumX]/[dragAccumY] 后再取整，保证「每帧移动 1.5px」这类
     * 慢速拖动不会被取整吃掉（NG 用同一个办法）。
     *
     * 尺寸两个参数传 `-1`：`update(x, y, width, height)` 的文档约定是
     * 「-1 表示保持原尺寸」。菜单是 WRAP_CONTENT，展开「更多」时要能自己长高，
     * 所以绝不能在这里写死某个时刻量到的宽高，否则展开后会被裁掉。
     * （传 `WRAP_CONTENT`(-2) 在当前实现里同样会被忽略，但那依赖内部判断，不是契约。）
     */
    private fun dragBy(deltaX: Float, deltaY: Float) {
        if (!isShowing) return
        dragAccumX += deltaX
        // `Gravity.BOTTOM` 下 y 从底部往上量，方向与屏幕相反，需要翻转
        dragAccumY += if (menuAnchoredToBottom) -deltaY else deltaY

        // 横向夹在屏幕内。菜单自身宽度在拖动中可能变化（展开「更多」），
        // 以当前 decor 实测宽度为准，取不到时退化为不夹，宁可拖出去也不卡死拖动。
        val measuredWidth = composeView.width.takeIf { it > 0 }
        val maxOffsetX = if (measuredWidth != null) {
            (dragRootWidth - measuredWidth).coerceAtLeast(0).coerceAtLeast(dragInitialX)
        } else {
            Int.MAX_VALUE
        }
        val targetX = dragAccumX.roundToInt().coerceIn(0, maxOffsetX)

        // 纵向夹在父容器内。两种 gravity 下 offset 的取值范围其实相同：
        // `Gravity.TOP` 时 offset 是菜单顶边到容器顶边的距离，`Gravity.BOTTOM` 时是
        // 菜单底边到容器底边的距离，两者都落在 [0, 容器高 - 菜单高]，差别只在 dy 的符号。
        val measuredHeight = composeView.height.takeIf { it > 0 }
        val maxOffsetY = if (measuredHeight != null) {
            (dragParentHeight - measuredHeight).coerceAtLeast(0).coerceAtLeast(dragInitialY)
        } else {
            Int.MAX_VALUE
        }
        val targetY = dragAccumY.roundToInt().coerceIn(0, maxOffsetY)

        if (targetX == menuOffsetX && targetY == menuOffsetY) return
        menuOffsetX = targetX
        menuOffsetY = targetY
        update(menuOffsetX, menuOffsetY, KEEP_SIZE, KEEP_SIZE)
    }

    // ─────────────── 划线操作 ───────────────

    /**
     * 以「划线操作面板」形态显示菜单。
     *
     * 定位策略与 [show] 同源（上方空间充裕就 `Gravity.BOTTOM` 向上生长，否则向下），
     * 差别是锚点只有一个点击位置，没有选区手柄那一套。
     *
     * 横向刻意**居中于点击点**再夹回屏幕：面板宽度被 PanelMaxWidth 限定，
     * 若照 [show] 那样直接把 x 当左边，点在屏幕右半边时面板会有一半在屏幕外。
     *
     * @param x 点击位置 X（视图坐标，与 [show] 的 startX 同口径）
     * @param y 点击位置 Y（视图坐标），面板贴着它上/下沿生长，中间留 [highlightAnchorGap] 不遮住被点的字
     */
    fun showTextHighlight(
        view: View,
        windowHeight: Int,
        x: Int,
        y: Int,
        textHighlight: Bookmark,
    ) {
        upMenu()
        textHighlightState.value = textHighlight
        textHighlightPopupModeState.value = initialTextHighlightPopupMode(textHighlight)
        noteDraftState.value = textHighlight.content
        // 直接复位而不是走 setNoteEditorVisible：那一对方法要动 isFocusable，
        // 而此刻窗口还没显示，动了也白动
        noteEditorVisibleState.value = false
        isFocusable = false

        val density = context.resources.displayMetrics.density
        val gap = (highlightAnchorGap * density).roundToInt()
        val panelWidth = (PANEL_MAX_WIDTH_DP * density).roundToInt()
        val rootWidth = view.rootView.width.takeIf { it > 0 }
            ?: context.resources.displayMetrics.widthPixels

        if (y > 300) {
            // 上方空间充裕：面板底边停在点击位置上方 gap 处，向上生长
            menuAnchoredToBottom = true
            menuOffsetX = (x - panelWidth / 2).coerceIn(0, (rootWidth - panelWidth).coerceAtLeast(0))
            menuOffsetY = windowHeight - y + gap
            showAtLocation(view, Gravity.BOTTOM or Gravity.START, menuOffsetX, menuOffsetY)
        } else {
            // 贴近页眉：面板顶边停在点击位置下方 gap 处，向下生长
            menuAnchoredToBottom = false
            menuOffsetX = (x - panelWidth / 2).coerceIn(0, (rootWidth - panelWidth).coerceAtLeast(0))
            menuOffsetY = y + gap
            showAtLocation(view, Gravity.TOP or Gravity.START, menuOffsetX, menuOffsetY)
        }

        // 拖动基准对齐到实际位置，理由与 [show] 末尾那段完全一致
        dragAccumX = menuOffsetX.toFloat()
        dragAccumY = menuOffsetY.toFloat()
        dragInitialX = menuOffsetX
        dragInitialY = menuOffsetY
        dragParentHeight = windowHeight
        dragRootWidth = rootWidth
        popupRoot = composeView.rootView
        popupRoot?.installPopupViewTreeOwners(view)
    }

    /**
     * 改样式 / 颜色：立即回写宿主，菜单不关——用户要边点边看效果。
     *
     * 与原值相同时直接返回，避免「点了当前已选中的色块」也触发一次整章重排版。
     */
    private fun updateTextHighlight(style: Int, color: Int) {
        val current = textHighlightState.value ?: return
        if (current.highlightStyle == style && current.highlightColor == color) return
        val updated = current.copy(highlightStyle = style, highlightColor = color)
        textHighlightState.value = updated
        callBack.onTextHighlightUpdate(updated)
    }

    /**
     * 删除这条划线。
     *
     * 必须**先清状态再关窗**：关窗时 [commitTextHighlightNote] 会拿 textHighlightState
     * 提交备注，顺序反了会把刚删掉的书签又写回库里。
     */
    private fun deleteTextHighlight(highlight: Bookmark) {
        textHighlightState.value = null
        textHighlightPopupModeState.value = TextHighlightPopupMode.TOOLBAR
        noteEditorVisibleState.value = false
        noteDraftState.value = ""
        isFocusable = false
        callBack.onTextHighlightDelete(highlight)
        dismiss()
        callBack.onMenuActionFinally()
    }

    /**
     * 展开 / 收起备注输入框。
     *
     * 顺序不能换：`isFocusable` 的 setter 只是改字段、不会重新下发窗口参数，
     * 而 `inputMethodMode` 的 setter 在窗口已显示时会调 `update()`，
     * 由那次 update 顺带把新的 focusable 标志带上。所以「先设 focusable、
     * 再设 inputMethodMode」才生效（NG 同序）。
     *
     * 收起时提交草稿：输入到一半点别处，备注也该留住。
     */
    private fun setNoteEditorVisible(visible: Boolean) {
        if (textHighlightState.value == null || noteEditorVisibleState.value == visible) return
        if (visible) {
            isFocusable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            noteEditorVisibleState.value = true
        } else {
            commitTextHighlightNote()
            noteEditorVisibleState.value = false
            isFocusable = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        }
    }

    /**
     * 把备注草稿提交给宿主。内容没变就不提交，否则每次关窗都会白跑一次整章重排版。
     */
    private fun commitTextHighlightNote() {
        val current = textHighlightState.value ?: return
        val note = noteDraftState.value
        if (current.content == note) return
        val updated = current.copy(content = note)
        textHighlightState.value = updated
        callBack.onTextHighlightUpdate(updated)
    }

    /**
     * 关闭划线面板。
     *
     * 必须**先收起备注输入框再关窗**：setNoteEditorVisible(false) 内部会
     * commitTextHighlightNote，而关窗时的 setOnDismissListener 也会提交一次，
     * 两次提交内容相同时第二次会被跳过，不会重复触发重排版。
     *
     * 这里刻意不走 [CallBack.onMenuActionFinally]：那个方法会顺带
     * `readView.cancelSelect()`，而划线面板打开时选区早已撤掉，白跑一遍。
     */
    private fun closeHighlightMenu() {
        setNoteEditorVisible(false)
        dismiss()
    }

    /**
     * 从备注预览态进入工具栏态。
     *
     * @param openNoteEditor true 时顺带展开备注输入框（预览态点「备注」走这条）
     */
    private fun showTextHighlightToolbar(openNoteEditor: Boolean) {
        if (!isShowing || textHighlightState.value == null) return
        if (textHighlightPopupModeState.value != TextHighlightPopupMode.NOTE_PREVIEW) {
            if (openNoteEditor) setNoteEditorVisible(true)
            return
        }
        textHighlightPopupModeState.value = TextHighlightPopupMode.TOOLBAR
        if (openNoteEditor) setNoteEditorVisible(true)
    }

    /**
     * 菜单内容
     *
     * 布局沿用旧 XML 的三段结构：第一行横向放菜单项与「更多」按钮，第二行纵向放更多项；
     * 展开模式（[UiState.expanded]）则把全部条目一次性横向铺开、不出现按钮。
     */
    @Composable
    private fun TextActionMenuContent() {
        val localContext = LocalContext.current
        // 卡片底色取 @color/background_card（昼 md_grey_100 / 夜 md_grey_850），
        // 与 colorScheme.surface 不是同一个值，用 surface 会让卡片颜色偏离原设计
        val containerColor = Color(ContextCompat.getColor(localContext, R.color.background_card))
        // 文字与图标颜色取 @color/primaryText，与旧布局取值口径一致
        val contentColor = Color(ContextCompat.getColor(localContext, R.color.primaryText))

        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(CardCornerRadius))
                .background(containerColor)
                // 拖动手势挂在整张卡片上（padding 之前）：放在 padding 之后的话，
                // 5dp 的卡片内边距就不属于可拖区域，手指压在边缘那条上拖不动。
                //
                // 与子项点击不冲突：detectDragGestures 内部用
                // `awaitFirstDown(requireUnconsumed = false)`，即便子项的 combinedClickable
                // 在 Main pass 里先消费掉 down，父层仍能接管；只有位移超过 touch slop
                // 才真正夺取手势，没超过就交还给子项当点击处理。
                //
                // 与 NG 一致：备注输入框展开时不挂拖动手势。IME 会把窗口顶起来，
                // 拖动基准（dragAccum / menuOffset）在那一刻全部失效，
                // 继续响应拖动会让菜单跟着手指乱跳。
                .then(
                    if (noteEditorVisibleState.value) {
                        Modifier
                    } else {
                        Modifier.pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                dragBy(dragAmount.x, dragAmount.y)
                            }
                        }
                    }
                )
                .padding(CardPadding)
        ) {
            // 划线形态：整个卡片换成划线操作面板，不显示文字菜单项
            val textHighlight = textHighlightState.value
            if (textHighlight != null) {
                if (textHighlightPopupModeState.value == TextHighlightPopupMode.NOTE_PREVIEW) {
                    HighlightNotePreview(
                        note = textHighlight.content,
                        contentColor = contentColor,
                        onEdit = { showTextHighlightToolbar(openNoteEditor = true) },
                        onDetail = { showTextHighlightToolbar(openNoteEditor = false) },
                        onDismiss = ::closeHighlightMenu,
                    )
                } else {
                    HighlightToolbar(
                        highlight = textHighlight,
                        contentColor = contentColor,
                        noteDraft = noteDraftState.value,
                        noteEditorVisible = noteEditorVisibleState.value,
                        onNoteEditorVisibleChange = ::setNoteEditorVisible,
                        onNoteDraftChange = { noteDraftState.value = it },
                        onStyleChange = { style ->
                            updateTextHighlight(style, textHighlight.highlightColor)
                        },
                        onColorChange = { color ->
                            updateTextHighlight(textHighlight.highlightStyle, color)
                        },
                        onDelete = { deleteTextHighlight(textHighlight) },
                        onDismiss = ::closeHighlightMenu,
                    )
                }
                return@Column
            }
            if (uiState.expanded) {
                MenuItemFlow(uiState.allItems, contentColor)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!uiState.showingMore) {
                        MenuItemFlow(uiState.visibleItems, contentColor)
                    }
                    if (uiState.moreItems.isNotEmpty()) {
                        MoreButton(
                            contentColor = contentColor,
                            showingMore = uiState.showingMore,
                            onToggle = { uiState.showingMore = !uiState.showingMore }
                        )
                    }
                }
                if (uiState.showingMore) {
                    Column {
                        uiState.moreItems.forEach { entry ->
                            MenuItemCell(entry, contentColor)
                        }
                    }
                }
            }
        }
    }

    /** 横向换行铺开菜单项，顶替旧的 FlexboxLayoutManager(row + wrap) */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun MenuItemFlow(items: List<MenuEntry>, contentColor: Color) {
        FlowRow {
            items.forEach { entry ->
                MenuItemCell(entry, contentColor)
            }
        }
    }

    /**
     * 单个菜单项：可选图标 + 标题。
     *
     * 点击与长按都走 [combinedClickable]，指示层用 [ripple]——按下态由绘制层接管，
     * 不依赖一次重组（快速点击时按下/抬起落在同一帧，自带底色方案看不到反馈）。
     */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun MenuItemCell(entry: MenuEntry, contentColor: Color) {
        val interactionSource = remember { MutableInteractionSource() }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = contentColor.copy(alpha = 0.12f)),
                    onClick = { onEntryClick(entry) },
                    onLongClick = { toggleSpeakMode() }
                )
                .padding(horizontal = MenuCellHorizontalPadding)
        ) {
            when {
                entry.iconResId != null -> Image(
                    painter = painterResource(entry.iconResId),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(contentColor),
                    modifier = Modifier.size(MenuIconSize)
                )

                entry.iconBitmap != null -> Image(
                    bitmap = entry.iconBitmap,
                    contentDescription = null,
                    modifier = Modifier.size(MenuIconSize)
                )
            }
            Text(
                text = entry.title,
                color = contentColor,
                fontSize = MenuTextSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    /** 「更多 / 返回」按钮，顶替 iv_menu_more 的 setImageResource 切换 */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun MoreButton(contentColor: Color, showingMore: Boolean, onToggle: () -> Unit) {
        val interactionSource = remember { MutableInteractionSource() }
        Image(
            painter = painterResource(
                if (showingMore) R.drawable.ic_arrow_back else R.drawable.ic_more_vert
            ),
            contentDescription = stringResource(R.string.more_menu),
            colorFilter = ColorFilter.tint(contentColor),
            modifier = Modifier
                .size(MoreButtonSize)
                .combinedClickable(
                    interactionSource = interactionSource,
                    // 对应旧布局的 ?attr/selectableItemBackgroundBorderless
                    indication = ripple(bounded = false, color = contentColor.copy(alpha = 0.12f)),
                    onClick = onToggle,
                    onLongClick = null
                )
        )
    }

    /**
     * 菜单项点击：先问宿主，宿主不处理再由菜单自己处理，最后无条件通知收尾。
     *
     * 这个三段顺序与旧实现一致，不能调换——[CallBack.onMenuActionFinally] 负责关闭菜单、
     * 取消选择，任何一条分支都必须走到。
     */
    private fun onEntryClick(entry: MenuEntry) {
        // 「书签」这一项在划线形态下含义不同：
        // - 普通形态（textHighlight == null）：把当前选区存成划线，然后**菜单不关**，
        //   原地切到划线面板继续改备注 / 样式 / 颜色；
        // - 划线形态（点已有划线进来的）：整张卡片已经被面板替换
        //   （见 TextActionMenuContent 的高亮分支），文字菜单项一个都不显示，
        //   所以这一项根本点不到，这里的 null 判断只是双保险。
        if (entry.itemId == R.id.menu_bookmark && textHighlightState.value == null) {
            createTextHighlight()
            return
        }
        commitTextHighlightNote()
        if (!callBack.onMenuItemSelected(entry.itemId)) {
            onMenuItemSelected(entry.item)
        }
        callBack.onMenuActionFinally()
    }

    /**
     * 创建本次的高亮。
     *
     * 用代号（[highlightMenuGeneration]）而不是简单布尔来防重入：
     * 存盘是挂起操作，期间用户完全可能已经把菜单关掉、甚至又开了一个新菜单。
     * 回来时如果菜单已经换代，这次结果就必须丢弃，否则会把新菜单的高亮态
     * 覆盖成上一条（NG 用同一套办法）。
     *
     * 作用域取 popup 自己的 LifecycleOwner：[context] 的静态类型是 Context，
     * 直接 `context.lifecycleScope` 解析不到扩展。窗口已经在显示，
     * popupRoot 上装着 owner（见 show 末尾），取不到就说明菜单已关，直接收尾。
     */
    private fun createTextHighlight() {
        val mine = ++highlightMenuGeneration
        val scope = popupRoot?.findViewTreeLifecycleOwner()?.lifecycleScope
        if (scope == null) {
            callBack.onMenuActionFinally()
            return
        }
        scope.launch {
            try {
                val created = callBack.onTextHighlightCreate()
                // 菜单已换代 / 已关闭：结果作废，连收尾都不要做（新菜单自己会收尾）
                if (mine != highlightMenuGeneration || !isShowing) return@launch
                if (created == null) {
                    context.toastOnUi(R.string.create_bookmark_error)
                    callBack.onMenuActionFinally()
                    return@launch
                }
                textHighlightState.value = created
                noteDraftState.value = created.content
                textHighlightPopupModeState.value = initialTextHighlightPopupMode(created)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLog.put("创建划线失败", error)
                if (mine == highlightMenuGeneration && isShowing) {
                    context.toastOnUi(R.string.create_bookmark_error)
                    callBack.onMenuActionFinally()
                }
            }
        }
    }

    /** 长按任意菜单项：切换朗读模式（朗读选中内容 ↔ 从选择位置开始朗读） */
    private fun toggleSpeakMode() {
        if (AppConfig.contentSelectSpeakMod == 0) {
            AppConfig.contentSelectSpeakMod = 1
            context.toastOnUi("切换为从选择的地方开始一直朗读")
        } else {
            AppConfig.contentSelectSpeakMod = 0
            context.toastOnUi("切换为朗读选择内容")
        }
    }

    /** 把 MenuItemImpl 转成展示条目，图标只在这里解析一次 */
    private fun MenuItemImpl.toMenuEntry(): MenuEntry {
        val iconResId = getMenuItemIconId(itemId)
        val iconBitmap = if (iconResId == null) resolveAppIcon() else null
        return MenuEntry(title.toString(), itemId, this, iconResId, iconBitmap)
    }

    /**
     * 读取其他应用菜单项的图标。
     * 旧实现用 Canvas 把 Drawable 手工画进 18dp 的位图，这里等价的活儿交给 Drawable.toBitmap。
     */
    private fun MenuItemImpl.resolveAppIcon(): ImageBitmap? {
        val icon: Drawable? = icon ?: runCatching {
            // 尝试从 intent 获取应用图标
            intent?.resolveActivityInfo(context.packageManager, 0)?.loadIcon(context.packageManager)
        }.getOrNull()
        if (icon == null) return null
        val density = context.resources.displayMetrics.density
        val sizePx = (MenuIconSize.value * density).toInt()
        return runCatching { icon.toBitmap(sizePx, sizePx).asImageBitmap() }.getOrNull()
    }

    /**
     * 处理菜单项点击事件
     * 处理复制、分享、浏览器搜索等基础操作
     *
     * @param item 被点击的菜单项
     */
    private fun onMenuItemSelected(item: MenuItemImpl) {
        when (item.itemId) {
            // 复制文本到剪贴板
            R.id.menu_copy -> context.sendToClip(callBack.selectedText)

            // 分享文本
            R.id.menu_share_str -> context.share(callBack.selectedText)

            // 使用浏览器打开或搜索
            R.id.menu_browser -> {
                kotlin.runCatching {
                    val intent = if (callBack.selectedText.isAbsUrl()) {
                        // 如果是URL，直接打开
                        Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse(callBack.selectedText)
                        }
                    } else {
                        // 否则使用搜索引擎搜索
                        Intent(Intent.ACTION_WEB_SEARCH).apply {
                            putExtra(SearchManager.QUERY, callBack.selectedText)
                        }
                    }
                    context.startActivity(intent)
                }.onFailure {
                    it.printOnDebug()
                    context.toastOnUi(it.localizedMessage ?: "ERROR")
                }
            }

            // 其他菜单项：系统文本处理菜单（Android 6.0+）
            else -> item.intent?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    kotlin.runCatching {
                        // 将选中的文本传递给目标应用
                        it.putExtra(Intent.EXTRA_PROCESS_TEXT, callBack.selectedText)
                        context.startActivity(it)
                    }.onFailure { e ->
                        AppLog.put("执行文本菜单操作出错\n$e", e, true)
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun createProcessTextIntent(): Intent {
        return Intent()
            .setAction(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun getSupportedActivities(): List<ResolveInfo> {
        return context.packageManager
            .queryIntentActivities(createProcessTextIntent(), 0)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun createProcessTextIntentForResolveInfo(info: ResolveInfo): Intent {
        return createProcessTextIntent()
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
            .setClassName(info.activityInfo.packageName, info.activityInfo.name)
    }

    /**长按文字菜单
     * 首先设置一个足够大的菜单项排序值
     * 确保你的"PROCESS_TEXT"菜单项显示在
     * 剪切、复制、粘贴等标准选择菜单项之后。
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun onInitializeMenu(menu: Menu) {
        kotlin.runCatching {
            val hiddenItems = TextMenuConfig.getHiddenProcessTextItems(context)
            var menuItemOrder = 100
            for (resolveInfo in getSupportedActivities()) {
                val packageName = resolveInfo.activityInfo.packageName
                val className = resolveInfo.activityInfo.name
                val itemKey = TextMenuConfig.getProcessTextItemKey(packageName, className)
                if (itemKey !in hiddenItems) {
                    val title = TextMenuConfig.getCustomProcessTextTitle(context, itemKey)
                        ?: resolveInfo.loadLabel(context.packageManager)
                    menu.add(
                        Menu.NONE, Menu.NONE,
                        menuItemOrder++, title
                    ).apply {
                        intent = createProcessTextIntentForResolveInfo(resolveInfo)
                        // 设置应用图标
                        setIcon(resolveInfo.loadIcon(context.packageManager))
                    }
                }
            }
        }.onFailure {
            context.toastOnUi("获取文字操作菜单出错:${it.localizedMessage}")
        }
    }

    /**
     * 已经解析好的展示条目。
     *
     * @param title 展示文字（已套用用户自定义标题）
     * @param itemId 菜单项 ID，回传给宿主
     * @param item 原始菜单项，点击时从中取 intent 执行
     * @param iconResId 内置项图标资源；非内置项为 null
     * @param iconBitmap 其他应用图标，仅内置项为 null 时才尝试解析
     */
    private class MenuEntry(
        val title: String,
        val itemId: Int,
        val item: MenuItemImpl,
        val iconResId: Int?,
        val iconBitmap: ImageBitmap?
    )

    /** 菜单展示状态，刻意放在组合之外，见类注释 */
    private class UiState {
        /** 过滤与排序后的全部条目，展开模式下直接铺开这一份 */
        var allItems by mutableStateOf<List<MenuEntry>>(emptyList())

        /** 折叠模式第一页 */
        var visibleItems by mutableStateOf<List<MenuEntry>>(emptyList())

        /** 折叠模式「更多」页 */
        var moreItems by mutableStateOf<List<MenuEntry>>(emptyList())

        /** 展开模式：全部条目一次铺开、不显示更多按钮 */
        var expanded by mutableStateOf(false)

        /** 当前是否停在「更多」页 */
        var showingMore by mutableStateOf(false)
    }

    /**
     * 文本操作菜单回调接口
     */
    interface CallBack {
        /** 获取当前选中的文本 */
        val selectedText: String

        /**
         * 菜单项被选中时的回调
         * @param itemId 菜单项ID
         * @return true表示已处理，false表示未处理需要菜单自己处理
         */
        fun onMenuItemSelected(itemId: Int): Boolean

        /**
         * 把当前选区存成一条文字高亮（菜单内点「书签」时调用）。
         *
         * 挂起函数：宿主需要落库。菜单拿到返回值后**不关窗**，直接切到划线面板，
         * 用户接着改备注 / 样式 / 颜色，全部在菜单内完成——这是「划线操作不弹窗」
         * 的关键一步。返回 null 表示没存成（没有有效选区），菜单会走正常收尾。
         */
        suspend fun onTextHighlightCreate(): Bookmark?

        /**
         * 划线被修改（备注 / 样式 / 颜色）。宿主负责落库并触发当前章重排版。
         *
         * 会在菜单还开着的时候被调用（改样式颜色要边点边看效果），
         * 也会在菜单关闭时被调用一次（提交备注草稿），实现方不要假设菜单已经关了。
         */
        fun onTextHighlightUpdate(bookmark: Bookmark)

        /**
         * 划线被删除。宿主负责落库并触发当前章重排版。
         * 调用后菜单会自行关闭，实现方不必再 dismiss。
         */
        fun onTextHighlightDelete(bookmark: Bookmark)

        /**
         * 菜单操作完成后的回调
         * 用于执行清理工作，如关闭菜单、取消文本选择等
         */
        fun onMenuActionFinally()
    }
}

/**
 * 为 popup 的**窗口根**补上 ViewTreeOwner。
 *
 * ## 为什么必须装在 decor 上，装在 ComposeView 上无效
 * Compose 建 recomposer 时不是从 ComposeView 自己就近往上找，而是两步走：
 *
 * 1. `View.contentChild` 沿父链往上，遇到 id 为 `android.R.id.content` 的父就返回其直接子 View；
 *    PopupWindow 没有 content 容器，于是一路走到窗口根 —— 即 `PopupDecorView`。
 * 2. `createLifecycleAwareWindowRecomposer(decor)` 在 **decor** 上执行
 *    `findViewTreeLifecycleOwner()`，取不到就抛
 *    `ViewTreeLifecycleOwner not found from android.widget.PopupWindow$PopupDecorView`
 *    （崩溃栈里那个 `$this` 正是 decor，不是 ComposeView，即为此证据）。
 *
 * owner 装在 ComposeView 上时，第 1 步已经把查询点抬到 decor，第 2 步自然查不到。
 *
 * ## 三个 owner 都要装
 * `findViewTreeComposeViewRoot()` 用
 * `min(lifecycleOwnerDepth, savedStateRegistryOwnerDepth)` 决定在哪一层查 owner：
 * 只装 Lifecycle 而漏掉 SavedStateRegistry，maxDepth 会变成
 * `Int.MAX_VALUE`，查询点同样落到 decor 上失败（报错文案不同，成因一致）。
 *
 * owner 从 `anchor`（宿主 Activity 自己的视图，位于 Activity 视图树内）取，
 * 取不到再退到 [Context] 里找。
 */
private fun View.installPopupViewTreeOwners(anchor: View) {
    val ownerContext = context.findPopupOwnerContext()
    val lifecycleOwner = anchor.findViewTreeLifecycleOwner() ?: ownerContext as? LifecycleOwner
    val viewModelStoreOwner =
        anchor.findViewTreeViewModelStoreOwner() ?: ownerContext as? ViewModelStoreOwner
    val savedStateRegistryOwner =
        anchor.findViewTreeSavedStateRegistryOwner() ?: ownerContext as? SavedStateRegistryOwner
    lifecycleOwner?.let { setViewTreeLifecycleOwner(it) }
    viewModelStoreOwner?.let { setViewTreeViewModelStoreOwner(it) }
    savedStateRegistryOwner?.let { setViewTreeSavedStateRegistryOwner(it) }
}

private tailrec fun Context.findPopupOwnerContext(): Context {
    return when (this) {
        is LifecycleOwner, is ViewModelStoreOwner, is SavedStateRegistryOwner -> this
        is android.content.ContextWrapper -> baseContext.findPopupOwnerContext()
        else -> this
    }
}