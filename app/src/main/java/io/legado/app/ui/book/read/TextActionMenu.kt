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
import android.widget.PopupWindow
import androidx.annotation.RequiresApi
import androidx.appcompat.view.SupportMenuInflater
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.view.menu.MenuItemImpl
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.toastOnUi

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

    /** 菜单展示状态，刻意活在组合之外，见类注释 */
    private val uiState = UiState()

    /** 是否展开文本菜单（展开时不分页、不显示更多按钮） */
    private val expandTextMenu get() = context.getPrefBoolean(PreferKey.expandTextMenu)

    /** 隐藏的菜单项 ID 集合，每次都从配置中读取，保证改动立即生效 */
    private val hiddenMenuItemIds: Set<Int>
        get() = TextMenuConfig.getHiddenMenuItemIds(context)

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
        // popup 的 window 内容视图父链止于 PopupWindow 的 decor，Composition 建不出来时要抛
        // ViewTreeLifecycleOwner not found，所以必须在 showAtLocation（触发 attach）之前装好 owner
        composeView.installPopupViewTreeOwners(view)
        if (startTopY > 300) {
            // 上方空间充裕：菜单底部对齐选中文字顶部，向上生长，绝不遮挡
            showAtLocation(
                view,
                Gravity.BOTTOM or Gravity.START,
                startX,
                windowHeight - startTopY
            )
        } else {
            // 接近页眉：菜单紧贴选中文字起始位置下方，向下生长
            showAtLocation(
                view,
                Gravity.TOP or Gravity.START,
                startX,
                startBottomY
            )
        }
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
                .padding(CardPadding)
        ) {
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
        if (!callBack.onMenuItemSelected(entry.itemId)) {
            onMenuItemSelected(entry.item)
        }
        callBack.onMenuActionFinally()
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
         * 菜单操作完成后的回调
         * 用于执行清理工作，如关闭菜单、取消文本选择等
         */
        fun onMenuActionFinally()
    }
}

/**
 * 为 popup 里的 ComposeView 补上 ViewTreeOwner。
 *
 * PopupWindow 的内容视图父链止于自己的 decor，[androidx.lifecycle.findViewTreeLifecycleOwner]
 * 找不到 Activity 的 owner，Compose 就建不出 recomposer，所以这里从 `anchor`（宿主自己的视图，
 * 位于 Activity 视图树内）取，取不到再退到 [Context] 里找。
 */
private fun ComposeView.installPopupViewTreeOwners(anchor: View) {
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