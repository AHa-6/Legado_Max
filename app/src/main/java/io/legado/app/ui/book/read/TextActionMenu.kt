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
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import io.legado.app.ui.theme.AppDimens
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.composeActionShape
import io.legado.app.ui.theme.composePanelShape
import io.legado.app.ui.theme.glassPanelBorderColor
import io.legado.app.ui.theme.glassPanelContainerTopColor
import io.legado.app.ui.theme.pageAccentColor
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.toastOnUi
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 卡片发丝边宽度。
 *
 * ## 为什么菜单没有阴影
 * 浮层阴影要么走 `Modifier.shadow`，要么走 [PopupWindow.setElevation]，两条路在这里都不通：
 * popup 是 WRAP_CONTENT，窗口尺寸正好等于内容尺寸，Compose 画的阴影落在内容边界之外，
 * 全部被窗口裁掉；窗口级 elevation 则要求 popup 的 background 能给出一个不透明轮廓，
 * 而这里刻意用透明背景（换成不透明就会看到一块方角底板）。
 *
 * 要阴影就得让窗口比内容大一圈（给 background 加 padding 当留白），代价是菜单定位、
 * 拖动 clamp 全都要跟着补偿这一圈偏移。菜单是浮在正文上的小浮层，一层淡阴影换不回这个复杂度，
 * 所以质感改由「面板圆角 + 发丝描边 + 分隔线 + 分段间距」表达。
 */
private val MenuCardBorderWidth = AppDimens.textMenuCardBorderWidth

// 尺寸与字号不再在本文件定义（theme-styles.md §7.2 / §7.4）：
// 边长、内边距、间隙一律取 AppDimens，字号取 MaterialTheme.typography 档位，
// 圆角取 composePanelShape() / composeActionShape()（与 XML 侧 UiCorner 同源）。
// 旧值 18dp 图标 + 4dp 水平内边距 + 裸 14.sp 正是「按钮太密」与「字号游离于档位之外」的根源。

/**
 * `PopupWindow.update(x, y, width, height)` 的「保持原尺寸」取值。
 *
 * 拖动菜单时只改位置，尺寸必须留给 WRAP_CONTENT 自己算——菜单内容会随「更多」
 * 展开而变高，写死宽高会把展开后的内容裁掉。
 */
private const val KEEP_SIZE = -1

/**
 * 菜单与点击位置之间留的间隙（dp），避免菜单正好压住被点的那行字
 */
private const val highlightAnchorGap = 8

/**
 * 划线菜单的两种形态（与 NG 的 `TextHighlightPopupMode` 同名同义）。
 *
 * ## 为什么要点已有划线时先看备注
 * 点开一条**带备注**的划线，用户此刻想知道的几乎都是「我上次在这儿记了什么」，
 * 而不是「这段文字还能做什么」。直接铺工具条等于把答案藏在第二层：
 * 得先点备注按钮、再等输入框展开，才看到自己写的那句话。
 *
 * 于是拆成两步：有备注先给一整卡可滚动的备注预览（[NOTE_PREVIEW]），
 * 右上角一个按钮进工具条（[TOOLBAR]）；没有备注就跳过预览直接进工具条，
 * 不给用户多看一眼空卡片的机会。
 *
 * 与 2026-11 之前那版「整卡替换」的区别在于**触发条件**：那版是任何划线都替换掉
 * 第一层菜单，复制 / 朗读 / 搜索凭空消失；这版只在「有备注」时替换，
 * 而且预览卡上有一个明确的出口回到全功能工具条，不是死胡同。
 */
internal enum class TextHighlightPopupMode {
    /** 全功能形态：第一层动作菜单 + 划线操作区（样式 / 颜色 / 备注） */
    TOOLBAR,

    /** 备注预览形态：整卡只显示这条划线的备注，右上角按钮切回 [TOOLBAR] */
    NOTE_PREVIEW,
}

/**
 * 点开一条划线时该进哪种形态。
 *
 * 判据是备注**非空白**而不是非 null：只敲了空格的备注预览出来是一张空卡，
 * 比直接进工具条更让人困惑。
 */
internal fun initialTextHighlightPopupMode(
    textHighlight: Bookmark?
): TextHighlightPopupMode = if (textHighlight?.content?.isNotBlank() == true) {
    TextHighlightPopupMode.NOTE_PREVIEW
} else {
    TextHighlightPopupMode.TOOLBAR
}

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
    // 点击已有划线时，菜单**不换形态**：第一层照常显示，只在它下面追加一条划线操作区
    // （备注开关 / 样式 / 颜色），「书签」槽位换成「删除划线」。
    // 不再弹 BookmarkDialog，也没有「第一层菜单 → 第二层编辑」的两步跳转。
    // 操作区 UI 见 TextHighlightPanel.kt。
    //
    // 状态同样放在组合之外：popup 内容跨「拖动移窗」「更多展开」多次重组，
    // 草稿若活在 remember 里会在重组时丢。

    /** 当前正在操作的划线，null 表示菜单处于普通文字选择形态 */
    private val textHighlightState = mutableStateOf<Bookmark?>(null)

    /**
     * 当前划线菜单的形态，见 [TextHighlightPopupMode]。
     *
     * 默认 [TextHighlightPopupMode.TOOLBAR]：普通文字选择态压根不读这个字段，
     * 只有 `textHighlightState != null` 时它才参与渲染分支。
     *
     * 与其它划线态字段一样放在组合之外，理由见 [noteDraftState] 上方那段：
     * 拖动移窗、展开「更多」都会触发重组，形态若活在 `remember` 里会被重置回默认值，
     * 表现为「拖一下菜单，备注预览自己变回工具条」。
     */
    private val textHighlightPopupModeState =
        mutableStateOf(TextHighlightPopupMode.TOOLBAR)

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
        //
        // ## 「点空白关闭」为什么不靠 isOutsideTouchable
        // 这里刻意与 NG 保持 false。置 true 虽然能让 PopupDecorView 收到 ACTION_OUTSIDE 自己关掉，
        // 但代价是：备注框展开时窗口是 focusable 的，输入法也是另一个窗口，
        // 部分设备会把「点输入法面板」一并判成窗外触摸，正在输入的备注当场被关掉。
        // 关闭通道因此放在宿主侧（[ReadView.onTouchEvent] 的 ACTION_DOWN →
        // `callBack.dismissTextActionMenuOnTouch()`），那里能区分触摸落在正文上还是别处，
        // 与 NG 的宿主回调 `onTextHighlightMenuDismissed` 是同一个思路。
        //
        // 旧实现并非没有这条通道，而是它只挂在两个选区手柄上、入口还要求
        // `readView.isTextSelected == true`；划线面板打开时选区早已撤销，
        // 于是点空白什么都不会发生（第 1 项的根因）。
        isTouchable = true                  // 可触摸
        isOutsideTouchable = false          // 关闭由宿主判定，理由见上
        isFocusable = false                 // 不获取焦点

        // 输入法：NG 在构造时就设为 NEEDED + ADJUST_RESIZE，Max 原来初始是 NOT_NEEDED、
        // 展开备注框时才改成 NEEDED + ADJUST_PAN。差异落在窗口级参数上：ADJUST_PAN 只平移不重算
        // 窗口尺寸，NEEDED 又与 NOT_NEEDED 的首次下发时机错位，个别输入法因此拿不到可用的
        // InputConnection（第 2 项「唤不起输入法」的窗口级成因，Compose 侧焦点链是另一条）。
        // 这里与 NG 对齐；非聚焦态带 FLAG_ALT_FOCUSABLE_IM，不会平白抬键盘。
        inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
        // SOFT_INPUT_ADJUST_RESIZE 自 API 30 起标废（推荐走 WindowInsets），但 PopupWindow
        // 的软键盘行为至今仍只能通过 softInputMode 这个入口设定，没有等价替代；
        // 本仓对这类"废弃但仍是唯一途径"的系统 API 统一用 @Suppress("DEPRECATION")，
        // 见 AppContextWrapper / PermissionActivity。
        @Suppress("DEPRECATION")
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

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
            // 放在自己的状态清干净之后：宿主的 selectedText 会回退成读选区，
            // 反过来先通知宿主就可能让它在切换瞬间读到半新半旧的值
            callBack.onTextActionMenuClosed()
            // 不再把 inputMethodMode 拨回 NOT_NEEDED：那样下次 show 就回到「初始 NOT_NEEDED」，
            // 展开备注框时再切成 NEEDED，首次下发时机与窗口 attach 错位，正是输入法弹不出的
            // 窗口级成因。NG 全程 NEEDED + ADJUST_RESIZE，只切 isFocusable。
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

        // 一行能放下几个格子：由可用屏宽反算，不是写死的常量。
        //
        // popup 是 WRAP_CONTENT，旧实现让每格按内容自适应宽，7 个可见项 + 「更多」
        // 在本机（392dp 宽）要 400dp 以上，FlowRow 只能折行——一排变两排，
        // 这就是「长按菜单太胖」的成因。改成定宽格子后，宽度由格子数决定，
        // 于是格子数必须反过来由屏宽决定。
        //
        // 两种容量都要算：不挂按钮时整行都给条目，挂按钮时得先扣掉那 48dp。
        val capacityFullRow = measureRowCapacity(withMoreButton = false)
        val capacityWithMore = measureRowCapacity(withMoreButton = true)
        uiState.rowCapacity = capacityFullRow

        // 将菜单项分为可见项（第一行）和更多项（纵向面板）
        //
        // 用户配置的可见项数量（3~10）在放得下时照旧生效；放不下时以容量为准，
        // 多出来的自动落进「更多」。这是唯一能保证「第一行永不折行」的分法——
        // 尊重配置而超宽，就会退回到折行那个老问题。
        val configured = TextMenuConfig.getTextMenuVisibleCount(context)
        val rowSlots = if (entries.size <= capacityFullRow) {
            // 一行放得下全部条目，不必留按钮位
            entries.size
        } else {
            // 放不下：给「更多」按钮留出格子，剩下的按配置数量放，但不超过容量
            configured.coerceAtMost(capacityWithMore)
        }
        uiState.allItems = entries
        if (entries.size > rowSlots) {
            uiState.visibleItems = entries.subList(0, rowSlots).toList()
            uiState.moreItems = entries.subList(rowSlots, entries.size).toList()
        } else {
            // 如果菜单项少于一行容量，全部显示在主菜单
            uiState.visibleItems = entries
            uiState.moreItems = emptyList()
        }
        uiState.expanded = expandTextMenu
    }

    /**
     * 量出一行能放几个菜单格。
     *
     * 口径与 Compose 侧完全一致：可用宽度 = 屏宽 − 两侧屏幕留白 − 卡片左右内边距，
     * 需要挂「更多」按钮时再扣掉按钮那一格，最后除以格子标准宽。
     * 四处取值全部来自 [AppDimens]，不在这里写第二个字面量。
     *
     * @param withMoreButton 这一行是否还要留位置给「更多」按钮。必须分两种情况算：
     *   按钮是 48dp 的独立元素，不占格子；按「格子数」一刀切会让 6 格 + 按钮 = 384dp
     *   超出本机 356.7dp 的可用宽度，菜单右边被屏幕切掉一截。
     *
     * 用 `displayMetrics.widthPixels` 而不是 Compose 的 `LocalConfiguration`：
     * 这个方法要在 [reloadMenuItems] 里被非 Composable 代码调用（popup 显示前），
     * 而菜单是贴在竖屏阅读页上的浮层，取屏宽即可，不必跟随窗口尺寸变化实时重算。
     */
    private fun measureRowCapacity(withMoreButton: Boolean): Int {
        val density = context.resources.displayMetrics.density
        val screenWidthDp = (context.resources.displayMetrics.widthPixels / density).toDouble()
        val moreButtonWidth =
            if (withMoreButton) AppDimens.textMenuMoreTouchSize.value.toDouble() else 0.0
        val usableDp = screenWidthDp -
            (AppDimens.textMenuScreenMargin * 2).value -
            (AppDimens.textMenuCardPadding * 2).value -
            moreButtonWidth
        val capacity = (usableDp / AppDimens.textMenuCellWidth.value).toInt()
        // 下限 2：再窄也至少给出两个动作，否则菜单形同不可用
        return capacity.coerceAtLeast(2)
    }

    /**
     * 卡片**内容区**宽度：工具条、样式条、备注预览三段里最宽的一段。
     *
     * 划线态刻意把三段一起取最大值，而不是「当前显示哪段就算哪段」：
     * 备注预览与工具条是同一张卡片的两种形态（见 [TextHighlightPopupMode]），
     * 点右上角按钮切换时若两种形态宽度不同，WRAP_CONTENT 的 popup 会当场重新测量，
     * 卡片宽度肉眼可见地跳一下。让预览卡直接取「工具条形态下的卡片宽」，
     * 切换前后等宽，也就不需要像 NG 那样在切换时补一次 `update(x, y, w, h)` 重定位。
     *
     * 本仓 popup 全程 WRAP_CONTENT（NG 是显式设 width/height），
     * 所以这里算出的宽度只用于两处：定位前的居中估算，以及预览卡的定宽。
     */
    private fun cardContentWidthDp(): Dp {
        val toolbarWidth = toolbarWidth(
            cellCount = if (uiState.expanded) {
                // 展开态按容量分行，最宽的一行就是满行
                uiState.rowCapacity
            } else {
                uiState.visibleItems.size
            },
            showMoreButton = !uiState.expanded && uiState.moreItems.isNotEmpty()
        )
        // 普通文字选择态只有工具条一段，直接返回。
        // 这里不能写成 maxOf(toolbarWidth, 0.dp) 之外还去读 highlightStyleBarWidth：
        // 无划线段时样式条压根不渲染，算进去会让菜单凭空宽 284dp、居中位置整体偏左。
        if (textHighlightState.value == null) return toolbarWidth
        return maxOf(
            toolbarWidth,
            AppDimens.highlightStyleBarWidth,
            // 预览卡的下限：可见项被用户配到只剩 3 个时工具条只有 168dp，
            // 一行放七八个字的备注卡太窄，读起来要一直换行
            AppDimens.highlightNotePreviewMinWidth
        )
    }

    /**
     * 卡片估算宽度（dp），供 [show] / [showTextHighlight] 定位与夹回屏幕。
     *
     * popup 是 WRAP_CONTENT，showAtLocation 之前量不到实际宽度，只能估。
     * 与 [cardContentWidthDp] 同源，再补上卡片左右内边距。
     *
     * 形态（备注预览 / 工具条）不影响这个值——两种形态等宽是 [cardContentWidthDp]
     * 刻意保证的，所以调用方不必先判形态再估宽。
     *
     * 返回 [Float] 而不是 Double：`Dp.value` 本身就是 Float，两个 Float 相加还是 Float，
     * 而 Kotlin 不做 Float → Double 的隐式加宽（`val d: Double = 1.0f` 直接编译不过）。
     * 调用方紧接着要乘 `displayMetrics.density`（也是 Float）再 `roundToInt()`，
     * 全程 Float 反而少一次转换。
     */
    private fun estimateCardWidthDp(): Float =
        cardContentWidthDp().value + (AppDimens.textMenuCardPadding * 2).value

    /**
     * 把宽 [widthPx] 的卡片横向居中于 [anchorX]，再夹回屏幕内。
     *
     * 两侧各留 [AppDimens.textMenuScreenMargin] 的安全边距：贴到屏幕边缘的菜单
     * 看着像被切了一半，而且边缘那一格的点击区正好压在系统手势区上，容易误触。
     * 夹回的上下界同样带这个边距，与 [measureRowCapacity] 的可用宽度口径一致——
     * 一处按「屏宽 − 两侧留白」算格子数，另一处就必须按同一留白夹位置，
     * 否则算出来放得下、实际却被挤到屏幕外。
     *
     * @return 夹回屏幕后的 popup x 偏移
     */
    private fun centerOn(anchorX: Int, widthPx: Int, rootWidthPx: Int): Int {
        val density = context.resources.displayMetrics.density
        val marginPx = (AppDimens.textMenuScreenMargin.value * density).roundToInt()
        val maxX = (rootWidthPx - widthPx - marginPx).coerceAtLeast(marginPx)
        return (anchorX - widthPx / 2).coerceIn(marginPx, maxX)
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
        // 菜单宽度现在可估（定宽格子 × 格子数，见 estimateCardWidthDp），
        // 于是定位不再「左边直接贴 startX」：那样选区落在屏幕右半边时菜单会有一半在屏幕外，
        // 而旧实现因为每格按内容自适应宽、量不到宽度，只能退而求其次贴左边。
        // 现在一律横向居中于**选区中点**再夹回屏幕，与 showTextHighlight 同一口径。
        val density = context.resources.displayMetrics.density
        val cardWidth = (estimateCardWidthDp() * density).roundToInt()
        val rootWidth = view.rootView.width.takeIf { it > 0 }
            ?: context.resources.displayMetrics.widthPixels
        val anchoredX = centerOn((startX + endX) / 2, cardWidth, rootWidth)
        if (startTopY > 300) {
            // 上方空间充裕：菜单底部对齐选中文字顶部，向上生长，绝不遮挡
            menuAnchoredToBottom = true
            menuOffsetX = anchoredX
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
            menuOffsetX = anchoredX
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
     * 横向刻意**居中于点击点**再夹回屏幕：菜单宽度按 [estimateCardWidthDp] 估算，
     * 若照 [show] 那样直接把 x 当左边，点在屏幕右半边时面板会有一半在屏幕外。
     *
     * 两种形态（备注预览 / 工具条）等宽，所以这里不必先判形态再估宽，
     * 切形态时也不需要重设窗口位置，见 [cardContentWidthDp] 的说明。
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
        noteDraftState.value = textHighlight.content
        // 有备注 → 先给备注预览；没备注 → 直接进工具条（见 TextHighlightPopupMode）。
        // 必须在估宽之前赋值：cardContentWidthDp 靠 textHighlightState 判断是否处于划线态，
        // 顺序反了会按「普通选择态」估宽，卡片居中位置整体偏左。
        textHighlightPopupModeState.value = initialTextHighlightPopupMode(textHighlight)
        // 直接复位而不是走 setNoteEditorVisible：那一对方法要动 isFocusable，
        // 而此刻窗口还没显示，动了也白动
        noteEditorVisibleState.value = false
        isFocusable = false

        val density = context.resources.displayMetrics.density
        val gap = (highlightAnchorGap * density).roundToInt()
        // 注意：这里的 density 是 displayMetrics.density（Float），不是 Compose 的 Density，
        // 所以不能用 with(density) { roundToPx() }（那个扩展的 receiver 类型不对，编译不过）。
        // 换算沿用本文件 loadMenuIcon 的同一口径：令牌 .value * density。
        //
        // 宽度取 [estimateCardWidthDp] 而不是单看样式条：划线态的卡片现在有「工具条 +
        // 样式条」两段，第一行格子多起来时工具条会比样式条更宽，只按样式条估会让菜单偏一半。
        // 调用前 textHighlightState 已赋值，所以这段能算进划线宽度。
        val cardWidth = (estimateCardWidthDp() * density).roundToInt()
        val rootWidth = view.rootView.width.takeIf { it > 0 }
            ?: context.resources.displayMetrics.widthPixels
        val anchoredX = centerOn(x, cardWidth, rootWidth)

        if (y > 300) {
            // 上方空间充裕：面板底边停在点击位置上方 gap 处，向上生长
            menuAnchoredToBottom = true
            menuOffsetX = anchoredX
            menuOffsetY = windowHeight - y + gap
            showAtLocation(view, Gravity.BOTTOM or Gravity.START, menuOffsetX, menuOffsetY)
        } else {
            // 贴近页眉：面板顶边停在点击位置下方 gap 处，向下生长
            menuAnchoredToBottom = false
            menuOffsetX = anchoredX
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
     * 从备注预览切到工具条形态。
     *
     * ## 为什么不需要重定位
     * NG 在这里算了一整套目标宽高、再 `update(x, y, w, h)` 把窗口挪过去，
     * 因为它的 popup 是显式设宽高的：两种形态尺寸不同，不重设就会把内容裁掉。
     * 本仓 popup 全程 WRAP_CONTENT，宽度由 Compose 自己量；而两种形态的宽度
     * 已由 [cardContentWidthDp] 保证相等，高度变化时 `Gravity.BOTTOM` 向上生长、
     * `Gravity.TOP` 向下生长，都不需要改 x/y。所以这里只切状态，重组即可。
     *
     * ## 为什么要收掉「更多」面板
     * 「更多」展开时卡片下面已经挂了一列动作面板，此时切形态会让卡片高度
     * 从「备注预览 + 动作面板」跳到「工具条 + 动作面板」，可能超出屏幕下沿。
     * 预览卡上根本没有「更多」按钮，能走到这里说明面板是收着的，收一次是幂等的。
     */
    private fun showTextHighlightToolbar() {
        if (
            !isShowing ||
            textHighlightState.value == null ||
            textHighlightPopupModeState.value != TextHighlightPopupMode.NOTE_PREVIEW
        ) {
            return
        }
        uiState.showingMore = false
        noteEditorVisibleState.value = false
        textHighlightPopupModeState.value = TextHighlightPopupMode.TOOLBAR
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
     * 展开 / 收起备注输入框（第 2 项：唤不起输入法）。
     *
     * 顺序不能换：`isFocusable` 的 setter 只是改字段、不会重新下发窗口参数，
     * 而 `inputMethodMode` 的 setter 在窗口已显示时会调 `update()`，
     * 由那次 update 顺带把新的 focusable 标志带上。所以「先设 focusable、
     * 再设 inputMethodMode」才生效（NG 同序）。
     *
     * 与旧实现的两处差异：
     * - 展开前先把「更多」列表收掉。展开态下窗口高度本来就紧张，
     *   再叠一层「更多」会把备注框顶出屏幕（NG 的 dismissMoreMenu 同理）；
     * - 不再动 softInputMode。`SOFT_INPUT_ADJUST_PAN` 只把窗口平移、不重算尺寸，
     *   输入法抬起后菜单可能被推出屏幕；构造时已统一设成 ADJUST_RESIZE，与 NG 一致。
     *
     * 收起时提交草稿：输入到一半点别处，备注也该留住。
     */
    private fun setNoteEditorVisible(visible: Boolean) {
        if (textHighlightState.value == null || noteEditorVisibleState.value == visible) return
        if (visible) {
            uiState.showingMore = false
            isFocusable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
            noteEditorVisibleState.value = true
        } else {
            commitTextHighlightNote()
            noteEditorVisibleState.value = false
            isFocusable = false
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
     * 菜单内容
     *
     * 布局沿用旧 XML 的三段结构：第一行横向放菜单项与「更多」按钮，第二行纵向放更多项；
     * 展开模式（[UiState.expanded]）则把全部条目一次性横向铺开、不出现按钮。
     */
    /**
     * 菜单内容
     *
     * 布局是**一段式**的：第一层菜单始终在，划线态只做两件事——把「书签」槽位换成
     * 「删除划线」，再在第一层下面追加一条操作区（备注开关、三种样式、五个颜色）。
     *
     * 旧实现是 `if (textHighlight != null) { 整卡换成编辑面板; return }`，
     * 结果点已有划线时复制 / 朗读 / 搜索全部消失，看起来就是弹了个书签编辑页。
     * 现在这两类动作同时可见，不必再走「第一层菜单 → 第二层编辑」两步。
     */
    @Composable
    private fun TextActionMenuContent() {
        val localContext = LocalContext.current
        // 卡片底色取 @color/background_card（昼 md_grey_100 / 夜 md_grey_850），
        // 与 colorScheme.surface 不是同一个值，用 surface 会让卡片颜色偏离原设计。
        //
        // 玻璃质感叠在它上面而不是直接用：glassPanelContainerTopColor 只有 44%（夜 23%）
        // 不透明度，菜单浮在正文上，真半透明的卡面会让底下的字从卡片里透上来、和菜单文字
        // 叠成一团。compositeOver 先把它压到不透明底色上，昼 / 夜 / 墨水屏各自取到
        // 该有的提亮，同时一个字都不漏。
        val baseCardColor = Color(ContextCompat.getColor(localContext, R.color.background_card))
        val containerColor = glassPanelContainerTopColor().compositeOver(baseCardColor)
        // 文字与图标颜色取 @color/primaryText，与旧布局取值口径一致
        val contentColor = Color(ContextCompat.getColor(localContext, R.color.primaryText))
        // 划线操作区的强调色，与「我的」等页面同一口径
        val accentColor = pageAccentColor()
        // 描边色：夜间用 glassPanelBorderColor 的白发丝（白线在黑卡上才看得见），
        // 昼间卡面本身就接近白，白发丝等于没有边，改用 8% 的黑线勾出一圈轮廓
        val borderColor = if (MaterialTheme.colorScheme.background.luminance() < 0.2f) {
            glassPanelBorderColor()
        } else {
            Color.Black.copy(alpha = 0.08f)
        }
        val textHighlight = textHighlightState.value
        // 圆角与 XML 侧 UiCorner 同源（theme-styles.md §7.2），不在 Compose 侧另设常量
        val shape = composePanelShape()

        // 备注预览卡的滚动状态在**这里**创建、传进 HighlightNotePreview：
        // 宿主要用它的 maxValue 判断卡片能不能拖（见下面拖动手势那段）。
        // 放在卡片内部 remember 的话宿主拿不到这个值，长备注就没法既滚动又拖动。
        val notePreviewScrollState = rememberScrollState()
        val isNotePreview =
            textHighlight != null &&
                textHighlightPopupModeState.value == TextHighlightPopupMode.NOTE_PREVIEW

        // 划线态的第一层条目：把「书签」槽换成「删除划线」。分页口径与 reloadMenuItems
        // 保持一致——先合并再替换再切回两页，避免两页各自补一个兜底删除项。
        val pagedEntries = remember(uiState.visibleItems, uiState.moreItems, textHighlight) {
            if (textHighlight == null) {
                uiState.visibleItems to uiState.moreItems
            } else {
                val merged = (uiState.visibleItems + uiState.moreItems).withDeleteHighlight()
                val head = uiState.visibleItems.size
                merged.take(head) to merged.drop(head)
            }
        }
        val allEntries = remember(uiState.allItems, textHighlight) {
            if (textHighlight == null) uiState.allItems else uiState.allItems.withDeleteHighlight()
        }

        Column(
            modifier = Modifier
                .clip(shape)
                .background(containerColor)
                // 发丝描边压在内容之下、裁在圆角之内：菜单没有阴影（原因见 MenuCardBorderWidth
                // 上的注释），边界全靠这道线交代，缺了它卡片会像一块贴纸糊在正文上
                .border(MenuCardBorderWidth, borderColor, shape)
                // 拖动手势挂在整张卡片上（padding 之前）：放在 padding 之后的话，
                // 卡片内边距就不属于可拖区域，手指压在边缘那条上拖不动。
                //
                // 与子项点击不冲突：detectDragGestures 内部用
                // `awaitFirstDown(requireUnconsumed = false)`，即便子项的 combinedClickable
                // 在 Main pass 里先消费掉 down，父层仍能接管；只有位移超过 touch slop
                // 才真正夺取手势，没超过就交还给子项当点击处理。
                //
                // 与 NG 一致：备注输入框展开时不挂拖动手势。IME 会把窗口顶起来，
                // 拖动基准（dragAccum / menuOffset）在那一刻全部失效，
                // 继续响应拖动会让菜单跟着手指乱跳。
                //
                // 备注预览卡**可滚动时**同样不挂：卡片内是 verticalScroll，
                // 外层再挂 detectDragGestures 就有两个手势抢同一根手指。
                // 理论上子节点先消费、父层 awaitTouchSlopOrCancellation 会放弃，
                // 但那条链依赖指针消费顺序，换个 Compose 版本就可能变；
                // 直接按「内容能不能滚」二选一更稳（NG 的 dragEnabled && maxValue == 0 同思路）。
                // 备注短、滚不动时 maxValue 是 0，拖动手势照常放行，卡片仍然拖得走。
                .then(
                    if (
                        noteEditorVisibleState.value ||
                        (isNotePreview && notePreviewScrollState.maxValue > 0)
                    ) {
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
                .padding(AppDimens.textMenuCardPadding)
        ) {
            // ── 形态一：备注预览 ──
            // 点开的是一条**带备注**的划线时，整张卡片只显示备注，两段常规内容都不渲染。
            // 用 return@Column 提前收尾，而不是把下面两段包进 else：包进去要给
            // 一百多行既有代码整体加一层缩进，diff 里全是位移噪声，看不出改了什么。
            //
            // 宽度取 cardContentWidthDp()，即「工具条形态下的卡片内容宽」——
            // 两种形态等宽，点右上角按钮切过去时卡片不会横向跳一下（理由见该函数注释）。
            // 这里直接调成员函数而不是先算好再传：它读的 uiState 字段都是 Compose 状态，
            // 在组合里调用才能把读取登记进快照，用户改了菜单项数量后宽度会跟着重算。
            if (isNotePreview && textHighlight != null) {
                HighlightNotePreview(
                    note = textHighlight.content,
                    accentColor = accentColor,
                    contentColor = contentColor,
                    onSettings = ::showTextHighlightToolbar,
                    scrollState = notePreviewScrollState,
                    modifier = Modifier.width(cardContentWidthDp()),
                )
                return@Column
            }
            // ── 第一段：文字动作菜单，划线态下也照常显示 ──
            if (uiState.expanded) {
                // 展开态：全部条目按容量切行，每行都是等宽格子，行与行之间对齐
                val rows = allEntries.chunked(uiState.rowCapacity.coerceAtLeast(1))
                rows.forEach { rowEntries ->
                    MenuToolbar(
                        entries = rowEntries,
                        contentColor = contentColor,
                        showMoreButton = false
                    )
                }
            } else {
                // 展开「更多」时第一行**保留**：NG 的做法是工具条常驻、动作列表追加在下方。
                // 旧实现把第一行整排换成一个返回箭头，卡片宽度当场从 300dp 掉到 48dp，
                // 再回到面板宽度——宽度两次跳变，看着像菜单在自己抽搐。
                MenuToolbar(
                    entries = pagedEntries.first,
                    contentColor = contentColor,
                    showMoreButton = uiState.moreItems.isNotEmpty(),
                    showingMore = uiState.showingMore,
                    onToggleMore = { uiState.showingMore = !uiState.showingMore }
                )
                if (uiState.showingMore) {
                    // 面板宽度对齐工具条：卡片宽度在展开前后完全一致，
                    // popup 是 WRAP_CONTENT，宽度不变也就不会触发窗口重新定位
                    val panelWidth = maxOf(
                        toolbarWidth(
                            cellCount = pagedEntries.first.size,
                            showMoreButton = uiState.moreItems.isNotEmpty()
                        ),
                        AppDimens.textMenuMorePanelWidth
                    )
                    MorePanel(
                        entries = pagedEntries.second,
                        contentColor = contentColor,
                        width = panelWidth
                    )
                }
            }
            // ── 第二段：划线操作区，只在高亮态追加，宽度与样式条对齐 ──
            if (textHighlight != null) {
                Spacer(Modifier.height(HighlightSectionGap))
                Column(
                    modifier = Modifier.width(HighlightStyleBarWidth),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 分隔线把「这段文字能做什么」和「这条划线长什么样」切成两段，
                    // 比单纯空出一行间距更能说明这是同一张卡片上的两组控件
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = contentColor.copy(alpha = 0.10f),
                    )
                    Spacer(Modifier.height(HighlightSectionGap))
                    HighlightStyleBar(
                        highlight = textHighlight,
                        noteEditorVisible = noteEditorVisibleState.value,
                        noteDraft = noteDraftState.value,
                        accentColor = accentColor,
                        contentColor = contentColor,
                        onNoteEditorVisibleChange = ::setNoteEditorVisible,
                        onStyleChange = { style ->
                            updateTextHighlight(style, textHighlight.highlightColor)
                        },
                        onColorChange = { color ->
                            updateTextHighlight(textHighlight.highlightStyle, color)
                        },
                    )
                    val note = noteDraftState.value
                    if (noteEditorVisibleState.value) {
                        Spacer(Modifier.height(HighlightNoteEditorGap))
                        HighlightNoteEditor(
                            value = note,
                            onValueChange = { noteDraftState.value = it },
                            // 收起输入框会顺带提交草稿，见 setNoteEditorVisible
                            onDone = { setNoteEditorVisible(false) },
                            accentColor = accentColor,
                            contentColor = contentColor,
                        )
                    } else if (note.isNotBlank()) {
                        // 已有备注：补一行摘要，点它直接进编辑，省掉「先点备注按钮」这一步
                        Spacer(Modifier.height(HighlightNoteEditorGap))
                        HighlightNoteSummary(
                            note = note,
                            accentColor = accentColor,
                            contentColor = contentColor,
                            onClick = { setNoteEditorVisible(true) },
                        )
                    }
                }
            }
        }
    }

    /**
     * 工具条一行的实际宽度：格子数 × 格子宽，挂「更多」按钮时再加一格。
     *
     * 与 [estimateCardWidthDp] 共用同一批令牌，两处算出来的宽度必须一致——
     * 一处用于「更多」面板的最小宽度，一处用于 popup 居中估算，
     * 口径漂移会让面板与工具条不等宽、卡片宽度在展开瞬间跳一下。
     */
    private fun toolbarWidth(cellCount: Int, showMoreButton: Boolean): Dp {
        val cells = cellCount * AppDimens.textMenuCellWidth
        return if (showMoreButton) cells + AppDimens.textMenuMoreTouchSize else cells
    }

    /**
     * 一行工具条：等宽格子横向排开，右侧可挂「更多」按钮。
     *
     * ## 为什么行高写死、格子定宽
     * 旧实现是 `FlowRow` + 每格按内容自适应宽：标签长短不一让每项宽窄不齐，
     * 7 个可见项加「更多」在本机（392dp 宽）要 400dp 以上，于是折成两行——
     * 这就是 2026-11-05 反馈的「长按菜单太胖」。
     *
     * 现在改成 NG 的做法：行高固定 [AppDimens.textMenuToolbarHeight]（52dp），
     * 每格固定 [AppDimens.textMenuCellWidth]（56dp），一行放几格由屏宽反算
     * （见 [measureRowCapacity]），第一行永不折行。
     *
     * 行高 52dp 是「图标 20 + 间隙 2 + `labelSmall` 标签 ≈ 12」再加上下呼吸空间，
     * 同时高于 §15.3 的 48dp 命中区下限——紧凑不以牺牲可点击区域为代价。
     */
    @Composable
    private fun MenuToolbar(
        modifier: Modifier = Modifier,
        entries: List<MenuEntry>,
        contentColor: Color,
        showMoreButton: Boolean,
        showingMore: Boolean = false,
        onToggleMore: () -> Unit = {},
    ) {
        if (entries.isEmpty() && !showMoreButton) return
        Row(
            modifier = modifier.height(AppDimens.textMenuToolbarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            entries.forEach { entry ->
                MenuItemCell(entry = entry, contentColor = contentColor)
            }
            if (showMoreButton) {
                MoreButton(
                    contentColor = contentColor,
                    showingMore = showingMore,
                    onToggle = onToggleMore
                )
            }
        }
    }

    /**
     * 单个菜单项：图标在上、标签在下，占一个等宽格子。
     *
     * 竖排而不是横排是这次瘦身的关键：横排时格子宽 = 图标 20 + 间隙 + 标签宽，
     * 「网页搜索」这类四字标签能占掉 60dp 以上；竖排后宽度只由标签决定，
     * 同样四个字收进 56dp，图标还从 18dp 放大到 20dp。
     *
     * 点击与长按都走 [combinedClickable]，指示层用 [ripple]——按下态由绘制层接管，
     * 不依赖一次重组（快速点击时按下/抬起落在同一帧，自带底色方案看不到反馈）。
     * 圆角取 [composeActionShape]（与 XML 侧 UiCorner 同源），让按下波纹跟着格子圆角裁切。
     *
     * 标签字号取 `typography.labelSmall`（11sp，与 NG 同值）而不是裸 `11.sp`：
     * §7.4 要求所有字号走档位，这样字号跟随用户的字体缩放与全局排版配置，
     * 也让 [AppDimens] 保持「只定义 dp」的契约。
     */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun MenuItemCell(
        modifier: Modifier = Modifier,
        entry: MenuEntry,
        contentColor: Color,
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val cellShape = composeActionShape()
        Column(
            modifier = modifier
                .width(AppDimens.textMenuCellWidth)
                .fillMaxHeight()
                .clip(cellShape)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = contentColor.copy(alpha = 0.12f)),
                    role = Role.Button,
                    onClick = { onEntryClick(entry) },
                    onLongClick = { toggleSpeakMode() }
                )
                .padding(horizontal = AppDimens.textMenuCellHorizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val iconModifier = Modifier.size(AppDimens.textMenuIconSize)
            when {
                entry.iconResId != null -> Image(
                    painter = painterResource(entry.iconResId),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(contentColor),
                    modifier = iconModifier
                )

                entry.iconBitmap != null -> Image(
                    bitmap = entry.iconBitmap,
                    contentDescription = null,
                    modifier = iconModifier
                )
            }
            Spacer(Modifier.height(AppDimens.textMenuCellIconGap))
            Text(
                text = entry.title,
                color = contentColor,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                // 标签不换行：格子宽度是固定的，折行会把 52dp 的行撑破
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    /**
     * 「更多 / 返回」按钮，顶替 iv_menu_more 的 setImageResource 切换。
     *
     * 旧实现是「24dp 图标本身就是点击区」，远低于 accessibility.md §15.3 的 48dp 下限。
     * 现在宽度取 [AppDimens.textMenuMoreTouchSize]（48dp）、高度撑满工具条（52dp），
     * 图标 22dp 视觉大小基本不变，手指能按到的范围翻倍。
     */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun MoreButton(
        modifier: Modifier = Modifier,
        contentColor: Color,
        showingMore: Boolean,
        onToggle: () -> Unit,
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val description = stringResource(R.string.more_menu)
        val buttonShape = composeActionShape()
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .width(AppDimens.textMenuMoreTouchSize)
                .fillMaxHeight()
                .clip(buttonShape)
                .combinedClickable(
                    interactionSource = interactionSource,
                    // 对应旧布局的 ?attr/selectableItemBackgroundBorderless
                    indication = ripple(bounded = false, color = contentColor.copy(alpha = 0.12f)),
                    role = Role.Button,
                    onClick = onToggle,
                    onLongClick = null
                )
        ) {
            Image(
                painter = painterResource(
                    if (showingMore) R.drawable.ic_arrow_back else R.drawable.ic_more_vert
                ),
                contentDescription = description,
                colorFilter = ColorFilter.tint(contentColor),
                modifier = Modifier.size(AppDimens.textMenuMoreIconSize)
            )
        }
    }

    /**
     * 「更多」纵向面板：图标 + 标题横排，一行一条。
     *
     * 面板保持横排而不是跟着工具条竖排：这里的条目含系统文本处理菜单的第三方应用名
     * （「用 XX 搜索」），竖排 56dp 格子会把它们全截成省略号，横排才有地方放全称。
     *
     * @param width 面板宽度，调用处取「工具条宽度」与 [AppDimens.textMenuMorePanelWidth]
     *   的较大者——两者同源才能让卡片宽度在展开前后不跳变。
     *
     * 条目数上不封顶（系统菜单能塞进十几条），所以给最大高度并允许内部滚动，
     * 免得面板把整张卡片顶出屏幕。
     */
    @Composable
    private fun MorePanel(
        modifier: Modifier = Modifier,
        entries: List<MenuEntry>,
        contentColor: Color,
        width: Dp,
    ) {
        Column(
            modifier = modifier
                .width(width)
                .heightIn(max = AppDimens.textMenuMorePanelMaxHeight)
                .verticalScroll(rememberScrollState())
        ) {
            // 分隔线把「第一行动作」和「更多动作」切开，与划线段的分隔线同一手法
            HorizontalDivider(
                thickness = AppDimens.textMenuCardBorderWidth,
                color = contentColor.copy(alpha = 0.10f),
            )
            entries.forEach { entry ->
                MoreActionItem(entry = entry, contentColor = contentColor)
            }
        }
    }

    /** 「更多」面板里的单条：图标 + 标题，48dp 行高（§15.3 下限，不取 NG 的 44dp） */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun MoreActionItem(
        modifier: Modifier = Modifier,
        entry: MenuEntry,
        contentColor: Color,
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val itemShape = composeActionShape()
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(AppDimens.textMenuMoreItemHeight)
                .clip(itemShape)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = contentColor.copy(alpha = 0.12f)),
                    role = Role.Button,
                    onClick = { onEntryClick(entry) },
                    onLongClick = { toggleSpeakMode() }
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(AppDimens.textMenuMoreItemHorizontalPadding))
            val iconModifier = Modifier.size(AppDimens.textMenuIconSize)
            when {
                entry.iconResId != null -> Image(
                    painter = painterResource(entry.iconResId),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(contentColor),
                    modifier = iconModifier
                )

                entry.iconBitmap != null -> Image(
                    bitmap = entry.iconBitmap,
                    contentDescription = null,
                    modifier = iconModifier
                )
            }
            Spacer(Modifier.width(AppDimens.textMenuMoreItemIconGap))
            Text(
                text = entry.title,
                modifier = Modifier.weight(1f),
                color = contentColor,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(AppDimens.textMenuMoreItemHorizontalPadding))
        }
    }

    /**
     * 菜单项点击：先问宿主，宿主不处理再由菜单自己处理，最后无条件通知收尾。
     *
     * 这个三段顺序与旧实现一致，不能调换——[CallBack.onMenuActionFinally] 负责关闭菜单、
     * 取消选择，任何一条分支都必须走到。
     */
    private fun onEntryClick(entry: MenuEntry) {
        val highlight = textHighlightState.value
        // 「书签」这一项在两种形态下是相反的动作，共用同一个 itemId：
        // - 普通形态（highlight == null）：把当前选区存成划线，然后**菜单不关**，
        //   原地追加划线操作区继续改备注 / 样式 / 颜色；
        // - 高亮形态：这一项已经被换成「删除划线」（见 withDeleteHighlight）。
        // 两者都不走宿主，也不走 onMenuActionFinally——菜单要留着。
        if (entry.itemId == R.id.menu_bookmark) {
            if (highlight != null) {
                deleteTextHighlight(highlight)
            } else {
                createTextHighlight()
            }
            return
        }
        // 其余动作在高亮态下同样可用：宿主的 selectedText 会返回划线原文
        // （见 ReadBookActivity.selectedText），所以复制 / 分享 / 朗读 / 搜索拿到的
        // 都是用户点的那段文字，而不是一个空选区。
        commitTextHighlightNote()
        if (!callBack.onMenuItemSelected(entry.itemId)) {
            entry.item?.let { onMenuItemSelected(it) }
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
                // 刚划出来的划线**不进备注预览**，哪怕 created.content 非空
                // （宿主可能把同一位置已有的书签并进来）。用户刚点的是「划线」，
                // 期待看到的是样式 / 颜色 / 备注这一排工具条；这时换成一张备注卡，
                // 等于把他刚做的操作结果藏起来，还得再点一下才回到工具条。
                // 显式赋值而不是依赖默认值：showTextHighlight 会把模式设成 NOTE_PREVIEW，
                // 若某条路径漏了复位，这里就是最后一道兜底。
                textHighlightPopupModeState.value = TextHighlightPopupMode.TOOLBAR
                // 备注输入框保持收起：刚存下来的划线通常没有备注，
                // 直接弹键盘会挡住用户刚划的那段字
                noteEditorVisibleState.value = false
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

    /**
     * 高亮态下的第一层条目：把「书签」槽位换成「删除划线」。
     *
     * 点已有划线时再放一个「书签」没有意义（这段文字已经是划线了），而删除是这一形态下
     * 唯一的破坏性动作，占掉那个槽位最省地方，用户也不必去二级面板里找。
     * 图标换成垃圾桶，标题换成「删除划线」，避免同一个图标在两种形态下表示相反的动作。
     */
    private fun List<MenuEntry>.withDeleteHighlight(): List<MenuEntry> {
        // 没有「书签」槽位可换：用户在文本菜单设置里把「书签」整条隐藏了。
        // 这时第一层行首补一个删除项——否则点开一条已有划线，屏幕上没有任何入口能删掉它，
        // 只能绕去书签列表里找。
        val hasSlot = any { it.itemId == R.id.menu_bookmark }
        val mapped = map { entry ->
            if (entry.itemId == R.id.menu_bookmark) deleteHighlightEntry(entry.item) else entry
        }
        return if (hasSlot) mapped else listOf(deleteHighlightEntry(null)) + mapped
    }

    /**
     * 合成「删除划线」条目。
     *
     * @param item 被替换掉的「书签」原始菜单项，可为 null（用户把「书签」隐藏时没有可继承的项）
     */
    private fun deleteHighlightEntry(item: MenuItemImpl?): MenuEntry = MenuEntry(
        title = context.getString(R.string.delete_highlight),
        itemId = R.id.menu_bookmark,
        item = item,
        iconResId = R.drawable.ic_outline_delete,
        iconBitmap = null,
    )

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
        val sizePx = (AppDimens.textMenuIconSize.value * density).toInt()
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
     * @param item 原始菜单项，点击时从中取 intent 执行。
     *   「删除划线」是菜单自己合成的条目（见 [deleteHighlightEntry]），没有对应的
     *   MenuItemImpl，所以这里可空——它的动作在 [onEntryClick] 里就地处理，不落到 intent。
     * @param iconResId 内置项图标资源；非内置项为 null
     * @param iconBitmap 其他应用图标，仅内置项为 null 时才尝试解析
     */
    private class MenuEntry(
        val title: String,
        val itemId: Int,
        val item: MenuItemImpl?,
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

        /**
         * 一行能放几个格子，由 [measureRowCapacity] 按屏宽算出。
         *
         * 展开态分行、以及 [estimateCardWidthDp] 估宽都以它为准，
         * 所以放在状态里而不是每次现算：三处口径必须同源。
         */
        var rowCapacity by mutableIntStateOf(6)
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
         * 菜单已经关闭（任何一条关闭路径都会走到）。
         *
         * 宿主用它清掉「当前正在操作的划线」：[CallBack.selectedText] 在高亮态下返回划线原文，
         * 菜单关掉之后如果这个字段还留着，下一次长按选字时宿主会误以为还在高亮态，
         * 把用户刚选的那段文字换成上一条划线的原文。
         *
         * 只在 dismiss 监听里调一次，不假设宿主是否自己做过清理。
         */
        fun onTextActionMenuClosed()

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