package io.legado.app.ui.book.info

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.theme.AppDimens
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppBookCover
import io.legado.app.ui.widget.components.card.GlassCard
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.utils.formatReadDuration

/**
 * 书籍详情页（Compose 版）的版式层。
 *
 * ## 与 md3 参考版的关系
 * 版式（几何与层次）参考 `HapeLee/legado-with-MD3` 的 `BookInfoHeader` / `BookInfoActions` /
 * `BookInfoSummary`：封面左置 + 右侧书名/作者/来源、其下标签行与分类行、四宫格动作卡、
 * 摘要块。**代码不复用**，原因是实测出的三处硬不兼容：
 * 1. md3 全程用 `LegadoTheme.colorScheme` / `.typography`（object 访问器），而 max 的
 *    `LegadoTheme` 只是个 Composable 函数，没有这些访问器 —— 这里一律用 `MaterialTheme`；
 * 2. md3 的 `TextCard` 带 `onClick`，max 的 `TextCard` 没有该参数 —— 可点标签改用 `Surface`；
 * 3. md3 依赖 Miuix（`top.yukonga.miuix.kmp`）、coil3、`kotlinx.collections.immutable`、
 *    `org.koin.compose`，max 都没有，引入等于给详情页塞三套新依赖。
 *
 * ## 与 md3 的刻意差异
 * - 封面比例不自适应：md3 通过 `onImageAspectRatio` 回调 + `cachedCoverAspectRatio` 缓存按
 *   真实宽高比算高度（112dp 宽，clamp 到 124.4~218.3dp）。max 的 `AppBookCover` 没有该回调，
 *   故这里用默认比例 5:7（112×157dp）。要自适应需先给 `AppBookCover` 加回调，属后续项。
 * - 可点标签高度抬到 48dp：md3 的胶囊约 36dp，但 UI-ARCHITECTURE.md §15.3 是可点击区域
 *   ≥48×48dp 的硬卡项，且「48dp 下限优先于视觉紧凑」，因此这里用 `heightIn(min = 48.dp)`。
 * - 摘要不显示「已读 x/y 章」：md3 用的 `read_chapter_total` / `read_chapter_index` /
 *   `is_unread` 三个字符串在 max 的资源里不存在，改用 max 已有的 `all_chapter_num`。
 * - 阅读时长：md3 放在动作区第四格（点开出阅读记录 Sheet）。max 已有独立的
 *   `BookReadRecordActivity`，且阅读时长是「累计值」而非入口，故这里放到书名区作者行下方，
 *   以次要色小字显示，点击仍进阅读记录页 —— 详情页少占一格，信息却不丢。
 *
 * 本文件只含版式与状态载体，不含宿主 Activity / ViewModel 接线，接入分两步走以避免
 * 一次改动过大导致详情页整体不可用。
 */

// ── 版式常量（对齐 md3 的封面几何）────────────────────────────────

/** 详情页封面宽度，取 md3 的 `BOOK_INFO_COVER_WIDTH_DP` */
private val BookInfoCoverWidth: Dp = 112.dp

/**
 * 详情页封面高度。
 *
 * md3 的默认宽高比是 5:7（`BOOK_INFO_COVER_DEFAULT_ASPECT_RATIO`），即 112 / (5/7) = 156.8dp。
 * 这里直接给 157dp，与 md3 在未拿到真实比例时的显示尺寸一致。
 */
private val BookInfoCoverHeight: Dp = 157.dp

/** 封面圆角：md3 详情页封面是直角卡片，max 沿用自身的封面圆角档位保持全局一致 */
private val BookInfoCoverCornerRadius: Dp = AppDimens.bookCoverCornerRadius

/** 摘要区左右内边距，对齐 md3 `BookInfoSummary` 的 16dp */
private val BookInfoHorizontalPadding: Dp = 16.dp

/** 可点标签的最小高度：§15.3 硬下限 */
private val BookInfoTagMinHeight: Dp = 48.dp

/**
 * 头部区的文字颜色。
 *
 * 头部叠在 `bg_book`（模糊封面）与 `vw_bg`（固定 `#50000000` 遮罩）之上，那层遮罩不随主题
 * 变浅变深，于是头部实际底色永远是暗的 —— 原 XML 因此把 `tv_name` 硬编码成 `@color/white`、
 * 把 `TitleBar` 设成 `themeMode="dark"`。若这里改用 `colorScheme.onBackground`，浅色主题下会
 * 变成深色字压在暗底图上，直接不可读。
 *
 * md3 能安全使用主题色，是因为它的头部自己画了一层 `surface` 垂直渐变（`BookInfoBackdrop`），
 * 等效于把头部底色改回不透明。max 的模糊底图是详情页的既有观感，不该为了配色而抹掉，
 * 故这里与 md3 走相反方向：保留底图，把头部文字固定为浅色。
 */
private val BookInfoOnBackdrop: Color = Color.White

/** 头部区的次要文字色：白字降透明度，与 `@color/tv_text_summary` 在白底上的层级关系对位 */
private val BookInfoOnBackdropSecondary: Color = Color.White.copy(alpha = 0.76f)

/**
 * 详情页 UI 状态载体。
 *
 * 只收 max 侧真实存在的字段，不照搬 md3 的 `BookInfoUiState`：md3 那份含私密书籍、
 * 书架冲突、角色/知识/事件等 max 没有的功能位，带过来只会是永远为默认值的死字段。
 *
 * @param coverPath 展示封面路径（调用方传 `BookCover.getDisplayCover(book)`）
 * @param kinds 分类标签（`book.getKindList()`：字数 + kind 拆分），点击进搜索
 * @param tags max 独有的书籍标签（`BookTagHelper.parse(book.customTag)`），仅展示，编辑在编辑页
 * @param groupNames 分组名（`viewModel.loadGroup` 回调结果），为空时不渲染分组胶囊
 * @param readTimeMillis 累计阅读时长（毫秒），≤0 时显示「暂无阅读记录」
 * @param showReadRecord 是否显示阅读时长，对应 `AppConfig.enableReadRecord &&
 *   AppConfig.bookInfoShowReadRecord`
 */
data class BookInfoUiState(
    val name: String = "",
    val author: String = "",
    val originName: String = "",
    val coverPath: String? = null,
    val kinds: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val groupNames: String? = null,
    val inBookshelf: Boolean = false,
    val totalChapterNum: Int = 0,
    val durChapterTitle: String? = null,
    val latestChapterTitle: String? = null,
    val tocLoading: Boolean = false,
    val tocLoadFailed: Boolean = false,
    val readTimeMillis: Long = 0L,
    val showReadRecord: Boolean = false,
    /** 本地书：没有书源可换，信息行不渲染换源入口（与原 XML 行为一致） */
    val isLocal: Boolean = false,
    /**
     * 网页文件（`book.isWebFile`）：目录尚未就绪，目录行显示「加载中」而非真实章节名。
     * 对应原 XML 中 `llToc.gone()` + 「下载中...」那一支。
     */
    val isWebFile: Boolean = false,
)

// ── 头部：封面 + 书名 / 作者 / 来源 + 阅读时长 ─────────────────────

/**
 * 详情页头部（md3 `BookInfoHeader` 的版式）。
 *
 * 结构：左侧封面，右侧依次是书名、作者、所属书源，作者行下方靠右是累计阅读时长；
 * 其下是标签行与分类行。
 *
 * @param modifier Modifier（§3.1：永远是第一个参数）
 * @param state 页面状态
 * @param showTags 是否显示标签行（未入架的书不显示，与 max 现有 `upTag` 判定一致）
 * @param onCoverClick 封面点击（看大图）
 * @param onNameClick 书名点击（搜索书名）
 * @param onAuthorClick 作者点击（搜索作者）
 * @param onOriginClick 书源点击（看书源）
 * @param onKindClick 分类标签点击（按分类搜索）
 * @param onReadRecordClick 阅读时长点击（进阅读记录页）
 * @param readRecordDescription 阅读时长的无障碍描述
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookInfoHeader(
    modifier: Modifier = Modifier,
    state: BookInfoUiState,
    showTags: Boolean = true,
    /**
     * 是否渲染「所属书源」行。
     *
     * 分阶段接线的开关：头部先只接管封面/书名/分类，书源行仍由 `ll_info` 里的
     * `tv_origin` + `tv_change_source`（换源按钮）承担，两处同时渲染会把书源显示两遍。
     * 等信息行区也换成 [BookInfoInfoRows] 并删掉那组旧行后，再把它打开。
     */
    showOrigin: Boolean = true,
    /**
     * 是否渲染「作者」行。
     *
     * 同上，分阶段接线用：`ll_info` 里的 `tv_author` 行（带 ic_author 图标，12 处引用）
     * 目前仍是作者的唯一渲染处。头部先不接管它，否则同屏出现两个作者。
     * md3 版式里作者确实在头部，等旧行随信息行区一起被替换后再打开。
     */
    showAuthor: Boolean = true,
    onCoverClick: () -> Unit = {},
    onCoverLongClick: (() -> Unit)? = null,
    onNameClick: () -> Unit = {},
    onNameLongClick: (() -> Unit)? = null,
    onAuthorClick: () -> Unit = {},
    onAuthorLongClick: (() -> Unit)? = null,
    onOriginClick: () -> Unit = {},
    onKindClick: (String) -> Unit = {},
    onKindLongClick: ((String) -> Unit)? = null,
    onReadRecordClick: () -> Unit = {},
    readRecordDescription: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = BookInfoHorizontalPadding,
                top = BookInfoHorizontalPadding,
                end = BookInfoHorizontalPadding,
                bottom = 8.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(BookInfoHorizontalPadding),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .width(BookInfoCoverWidth)
                    .height(BookInfoCoverHeight)
                    // 长按看大图（PhotoDialog）：原来挂在 CoverImageView 的 setOnLongClickListener 上，
                    // 换 Compose 后必须由 combinedClickable 承担，否则该入口静默丢失
                    .combinedClickable(
                        onClick = onCoverClick,
                        onLongClick = onCoverLongClick,
                    )
                    .semantics {
                        role = Role.Button
                        contentDescription = state.name
                    },
            ) {
                AppBookCover(
                    modifier = Modifier.size(BookInfoCoverWidth, BookInfoCoverHeight),
                    name = state.name,
                    author = state.author,
                    coverPath = state.coverPath,
                    // 详情页封面不参与图集身份取图：`AppBookCover` 内部已按 coverPath 走
                    // 「图集 → 真实图 → HTML → 默认封面」链路，这里只补一个稳定身份
                    galleryIdentity = state.name,
                    contentDescription = state.name,
                    cornerRadius = BookInfoCoverCornerRadius,
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // stringResource 必须在 @Composable 作用域里取：semantics { } 的 lambda 不是
                // Composable 上下文，在其中调用会编译不过，故先提到这里，下面复用
                val authorText = stringResource(R.string.author_show, state.author)
                val originText = stringResource(R.string.origin_show, state.originName)
                Text(
                    text = state.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = BookInfoOnBackdrop,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = BookInfoTagMinHeight)
                        // 长按走 SourceCallBack.LONG_CLICK_BOOK_NAME：原 tvName.setOnLongClickListener
                        .combinedClickable(
                            onClick = onNameClick,
                            onLongClick = onNameLongClick,
                        )
                        .semantics {
                            role = Role.Button
                            contentDescription = state.name
                        },
                )
                if (showAuthor) {
                    Text(
                        text = authorText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = BookInfoOnBackdropSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = BookInfoTagMinHeight)
                            // 长按走 SourceCallBack.LONG_CLICK_AUTHOR：原 tvAuthor.setOnLongClickListener
                            .combinedClickable(
                                onClick = onAuthorClick,
                                onLongClick = onAuthorLongClick,
                            )
                            .semantics {
                                role = Role.Button
                                contentDescription = authorText
                            },
                    )
                }
                // 所属书源：md3 用 primary 色 + labelMedium 表达「可点」。这里不跟 primary：
                // 头部底色是暗的模糊封面，浅色主题下 primary 是深色饱和色，压在暗底上对比度不足。
                // 可点性改由水波纹与无障碍 role=Button 承担，颜色仍走头部浅色文字档位。
                if (showOrigin) {
                    Text(
                        text = originText,
                        style = MaterialTheme.typography.labelMedium,
                        color = BookInfoOnBackdropSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = BookInfoTagMinHeight)
                            .clickable(onClick = onOriginClick)
                            .semantics {
                                role = Role.Button
                                contentDescription = originText
                            },
                    )
                }
                if (state.showReadRecord) {
                    BookInfoReadTimeLine(
                        readTimeMillis = state.readTimeMillis,
                        onClick = onReadRecordClick,
                        description = readRecordDescription,
                    )
                }
            }
        }

        // 标签行：max 独有的书架标签，仅展示（编辑入口已移到编辑页）
        if (showTags && state.tags.isNotEmpty()) {
            // 纯展示：不传 onClick，走 List<String> 重载（该重载 onClick 默认为 null）
            BookInfoTagRow(
                labels = state.tags,
                keyPrefix = "tag",
            )
        }
        // 分类行：书源 kind + 字数，点击进搜索；分组名以同形态的胶囊排在最前
        // 显式标注元素类型：两条 add 的 second 分别是 null 与 String，靠推断会得到不同的
        // Pair 类型（Pair<String, Nothing?> 与 Pair<String, String>）从而报类型不匹配
        val kindRowLabels = buildList<Pair<String, String?>> {
            state.groupNames?.takeIf { it.isNotBlank() }?.let {
                add(it to null)
            }
            state.kinds.forEach { add(it to it) }
        }
        if (kindRowLabels.isNotEmpty()) {
            BookInfoTagRow(
                labels = kindRowLabels,
                onClick = onKindClick,
                onLongClick = onKindLongClick,
                keyPrefix = "kind",
            )
        }
    }
}

/**
 * 累计阅读时长行。
 *
 * 放在作者/书源下方而不是动作区第四格：max 已有独立的阅读记录页，这里只需要一个
 * 「累计多久」的轻量入口，不必再占一格动作卡。点击仍进那个页面，功能没有减少。
 */
@Composable
private fun BookInfoReadTimeLine(
    modifier: Modifier = Modifier,
    readTimeMillis: Long,
    onClick: () -> Unit,
    description: String?,
) {
    val context = LocalContext.current
    val text = if (readTimeMillis <= 0L) {
        stringResource(R.string.no_read_record)
    } else {
        stringResource(
            R.string.book_read_time_label,
            formatReadDuration(context, readTimeMillis),
        )
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        // 这一行渲染在头部（暗底模糊封面）内，不能跟 onSurfaceVariant：浅色主题下它是深灰，
        // 压在暗底上几乎看不见
        color = BookInfoOnBackdropSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = BookInfoTagMinHeight)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = description ?: text
            },
    )
}

/**
 * 横滚胶囊标签行。
 *
 * md3 用带 `onClick` 的 `TextCard`，max 的 `TextCard` 没有该参数，故这里用 `Surface`：
 * 圆角、底色、文字档位与 `TextCard` 默认值保持一致（8dp 圆角 / surfaceVariant /
 * onSurfaceVariant / bodyMedium），观感不产生第二套胶囊样式。
 *
 * @param labels 标签文案；元素第二项为该标签的点击载荷，null 表示不可点
 * @param onClick 点击回调；为 null 时整行不可点（纯展示）
 * @param keyPrefix LazyRow 的 key 前缀，避免与其它列表冲突
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookInfoTagRow(
    modifier: Modifier = Modifier,
    labels: List<Pair<String, String?>>,
    onClick: ((String) -> Unit)?,
    keyPrefix: String,
    onLongClick: ((String) -> Unit)? = null,
) {
    val shape = RoundedCornerShape(8.dp)
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(labels, key = { index, item -> "$keyPrefix-$index-${item.first}" }) { _, item ->
            val (label, payload) = item
            val clickable = payload != null && (onClick != null || onLongClick != null)
            // 先 clip 再 combinedClickable：水波纹被裁在圆角内，否则点击反馈是方的
            Surface(
                modifier = Modifier
                    .then(
                        if (clickable) {
                            Modifier
                                .heightIn(min = BookInfoTagMinHeight)
                                .clip(shape)
                                .combinedClickable(
                                    // payload 已被 clickable 的非空判断智能转换为 String，
                                    // 再写 payload?.let 是多余安全调用（编译器会告警）
                                    onClick = { onClick?.invoke(payload) },
                                    onLongClick = { onLongClick?.invoke(payload) },
                                )
                                .semantics {
                                    role = Role.Button
                                    contentDescription = label
                                }
                        } else {
                            Modifier
                        },
                    ),
                shape = shape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                BookInfoTagText(label = label)
            }
        }
    }
}

/** 胶囊内的文字，两条分支共用，避免出现第二套胶囊文字样式 */
@Composable
private fun BookInfoTagText(
    modifier: Modifier = Modifier,
    label: String,
) {
    Text(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 只有文案的标签行重载，供纯展示场景（书籍标签）调用 */
@Composable
private fun BookInfoTagRow(
    modifier: Modifier = Modifier,
    labels: List<String>,
    keyPrefix: String,
    onClick: ((String) -> Unit)? = null,
    onLongClick: ((String) -> Unit)? = null,
) {
    BookInfoTagRow(
        modifier = modifier,
        labels = labels.map { it to null },
        onClick = onClick,
        onLongClick = onLongClick,
        keyPrefix = keyPrefix,
    )
}

// ── 动作区：四宫格 ────────────────────────────────────────────

/**
 * 详情页动作区（md3 `BookInfoActions` 的版式）。
 *
 * 四格等宽：书架 / 目录 / 换源 / 阅读。max 现有的 `fl_action` 只有「书架 + 阅读」两个按钮，
 * 目录与换源是散落在信息行右侧的小胶囊（`tv_toc_view` / `tv_change_source`），四宫格把它们
 * 提到同一层，是 md3 版式里信息层级最清楚的一处。
 *
 * @param modifier Modifier
 * @param inBookshelf 是否已在书架，决定第一格的图标与文案
 * @param onShelfClick 书架格点击（加入 / 移出）
 * @param onTocClick 目录格点击
 * @param onChangeSourceClick 换源格点击
 * @param onReadClick 阅读格点击
 */
@Composable
fun BookInfoActions(
    modifier: Modifier = Modifier,
    inBookshelf: Boolean = false,
    onShelfClick: () -> Unit = {},
    onTocClick: () -> Unit = {},
    onChangeSourceClick: () -> Unit = {},
    onReadClick: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = BookInfoHorizontalPadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BookInfoActionCard(
            modifier = Modifier.weight(1f),
            label = stringResource(
                if (inBookshelf) R.string.remove_from_bookshelf else R.string.add_to_bookshelf
            ),
            onClick = onShelfClick,
        )
        BookInfoActionCard(
            modifier = Modifier.weight(1f),
            label = stringResource(R.string.view_toc),
            onClick = onTocClick,
        )
        BookInfoActionCard(
            modifier = Modifier.weight(1f),
            label = stringResource(R.string.change_origin),
            onClick = onChangeSourceClick,
        )
        BookInfoActionCard(
            modifier = Modifier.weight(1f),
            label = stringResource(R.string.reading),
            onClick = onReadClick,
        )
    }
}

/**
 * 单个动作卡。
 *
 * 用 max 已有的 `GlassCard`（md3 用的也是同名组件，但那是它自己的实现）。图标位留空：
 * max 现有详情页的动作按钮本来就没有图标，加图标需要先确定四枚图标资源，不在本次范围。
 * 卡片高度由 48dp 下限保证，文字最多两行。
 */
@Composable
private fun BookInfoActionCard(
    modifier: Modifier = Modifier,
    label: String,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    GlassCard(
        modifier = modifier
            .heightIn(min = BookInfoTagMinHeight)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
            },
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── 摘要区 ────────────────────────────────────────────────────

/**
 * 详情页摘要区（md3 `BookInfoSummary` 的版式，不含简介渲染）。
 *
 * 三行：当前目录章节、最新章节、总章数。简介（`<useweb>` / `<usehtml>` / `<md>` 三种渲染
 * 分支）不在此处：max 现有实现走 `TextView` + `TextViewTagHandler`，迁移到 Compose 需要
 * 一并处理 WebView 容器与 JS 桥接，单独一步做，避免与版式改动混在一起难以定位问题。
 *
 * @param modifier Modifier
 * @param state 页面状态
 * @param onRemarkClick 备注点击（max 现有 `tv_remark` 行为）
 */
@Composable
fun BookInfoSummary(
    modifier: Modifier = Modifier,
    state: BookInfoUiState,
    onRemarkClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = BookInfoHorizontalPadding,
                end = BookInfoHorizontalPadding,
                top = 8.dp,
                bottom = BookInfoHorizontalPadding,
            ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(
                R.string.toc_s,
                state.durChapterTitle ?: stringResource(R.string.loading),
            ),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.lasted_show, state.latestChapterTitle ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.all_chapter_num, state.totalChapterNum),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            if (state.tocLoadFailed) {
                Text(
                    text = stringResource(R.string.error_load_toc),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * 阅读记录底部弹窗占位。
 *
 * md3 用 `BookInfoReadRecordSheet`（含按天分格的时间轴 + 每次阅读起止）。max 的数据层已有
 * 等价能力（`ReadRecordRepository.getBookReadTime` 及会话查询），但时间轴 UI 需要另做；
 * 当前先复用已有的 `BookReadRecordActivity`（见 [BookInfoReadTimeLine] 的点击），
 * 这个弹窗留作后续把 md3 的时间轴搬过来时的落点。
 */
@Composable
fun BookInfoReadRecordSheet(
    modifier: Modifier = Modifier,
    show: Boolean,
    totalReadTime: Long,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    AppModalBottomSheet(
        modifier = modifier,
        show = show,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.read_record),
    ) {
        Text(
            text = if (totalReadTime <= 0L) {
                stringResource(R.string.no_read_record)
            } else {
                stringResource(
                    R.string.book_read_time_label,
                    formatReadDuration(context, totalReadTime),
                )
            },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(AppDimens.screenPadding),
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** 详情页内容区的滚动内边距：底部让开导航条，供宿主传入 LazyColumn 的 contentPadding */
val BookInfoContentPadding: PaddingValues
    @Composable get() = PaddingValues(bottom = 16.dp)

// ── 信息行区（替换 activity_book_info.xml 中 ll_info 内那一整块信息行）──────────

/**
 * 详情页信息行区。
 *
 * 对应 max 现有 XML 里 `ll_info` 内那 7 行（作者 / 书源+换源 / 最新 / 分组+换分组 /
 * 标签+设置标签 / 目录+查看 / 阅读记录+查看）。其中作者、书源、分组、标签、阅读时长
 * 已由 [BookInfoHeader] 以 md3 版式呈现，故这里只保留 **header 没有、但功能上必须留着**
 * 的入口：最新章节、目录（+查看）、阅读记录（+查看）、换源。
 *
 * 为什么不把换源/目录也塞进 header：md3 把它们放在动作区四宫格，而 max 的底部
 * `fl_action` 是固定操作条（书架 + 阅读）。若照搬四宫格就要连带改底部条的背景与
 * 导航栏内边距逻辑（`bottomBackground` / `applyNavigationBarPadding`），改动面比
 * 收益大；保持 max 现有的「入口散在信息行」结构可以做到零功能回退。
 *
 * 注意：分组与标签的**编辑入口**（原 `tv_change_group` / `tv_set_tag`）不在这里，
 * 按要求已移到书籍编辑页；此处它们只是 header 里的只读胶囊。
 *
 * @param modifier Modifier（§3.1：永远是第一个参数）
 * @param state 页面状态
 * @param onTocClick 目录行点击（查看目录）
 * @param onChangeSourceClick 换源点击
 * @param onReadRecordClick 阅读记录行点击（进阅读记录页）
 */
@Composable
fun BookInfoInfoRows(
    modifier: Modifier = Modifier,
    state: BookInfoUiState,
    onTocClick: () -> Unit = {},
    onChangeSourceClick: () -> Unit = {},
    onReadRecordClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val tocText = stringResource(
        R.string.toc_s,
        when {
            state.tocLoading -> stringResource(R.string.loading)
            state.tocLoadFailed -> stringResource(R.string.error_load_toc)
            else -> state.durChapterTitle ?: stringResource(R.string.loading)
        },
    )
    val lastedText = stringResource(R.string.lasted_show, state.latestChapterTitle ?: "")
    val readTimeText = if (state.readTimeMillis <= 0L) {
        stringResource(R.string.no_read_record)
    } else {
        stringResource(
            R.string.book_read_time_label,
            formatReadDuration(context, state.readTimeMillis),
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = BookInfoHorizontalPadding,
                end = BookInfoHorizontalPadding,
                bottom = 8.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BookInfoInfoRow(text = lastedText)
        BookInfoInfoRow(
            text = tocText,
            actionLabel = stringResource(R.string.view_toc),
            onActionClick = onTocClick,
        )
        if (state.showReadRecord) {
            BookInfoInfoRow(
                text = readTimeText,
                actionLabel = stringResource(R.string.view_read_record),
                onActionClick = onReadRecordClick,
            )
        }
        // 换源：本地书没有书源可换，与原 XML 中 tv_change_source 的显示条件保持一致
        if (!state.isLocal) {
            BookInfoInfoRow(
                text = stringResource(R.string.origin_show, state.originName),
                actionLabel = stringResource(R.string.change_origin),
                onActionClick = onChangeSourceClick,
            )
        }
    }
}

/**
 * 单条信息行：左侧一行文字，右侧可选的操作胶囊。
 *
 * 行高固定 48dp：文字本身不可点，但右侧胶囊可点，整行按 §15.3 的下限给高度，
 * 避免胶囊被压到 36dp 左右而难以命中。无胶囊时同样保持 48dp，让各行节奏一致。
 */
@Composable
private fun BookInfoInfoRow(
    modifier: Modifier = Modifier,
    text: String,
    actionLabel: String? = null,
    onActionClick: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = BookInfoTagMinHeight),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null) {
            Surface(
                modifier = Modifier
                    .heightIn(min = BookInfoTagMinHeight)
                    .semantics {
                        role = Role.Button
                        contentDescription = actionLabel
                    },
                onClick = onActionClick,
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

// ── 状态载体：View 宿主与两个 ComposeView 之间共享 ─────────────────

/**
 * 详情页 Compose 状态载体。
 *
 * 头部与信息行分属两个 `ComposeView`（XML 里头部在 `ll_content_bg` 之外，要让封面底图
 * 的模糊背景透出来；信息行在 `ll_content_bg` 之内，有不透明背景）。两者必须读同一份
 * 状态，否则一次 `showBook` 要写两遍、还容易漏。
 *
 * 用法：Activity 持有本类实例，`update { }` 改状态，两个 `setContent` 都读 [state]。
 * 回调单独存放（不放进 [BookInfoUiState]），因为 lambda 不参与相等性比较，混进 data class
 * 会让每次赋值都判定为「变了」而触发无谓重组。
 */
@Stable
class BookInfoComposeState {

    var state: BookInfoUiState by mutableStateOf(BookInfoUiState())
        private set

    /** 局部更新：只改需要的字段，其余保持原值 */
    fun update(block: BookInfoUiState.() -> BookInfoUiState) {
        state = state.block()
    }

    /** 整份替换（`showBook` 一次性拿到 Book 时用） */
    fun set(newState: BookInfoUiState) {
        state = newState
    }
}

// ── 预览（UI-ARCHITECTURE.md §10.1 强制）─────────────────────────

@Preview(name = "详情页头部 · 已入架", showBackground = true)
@Composable
private fun BookInfoHeaderPreview() {
    LegadoTheme {
        BookInfoHeader(
            state = BookInfoUiState(
                name = "剑来",
                author = "烽火戏诸侯",
                originName = "起点中文网",
                kinds = listOf("1234万字", "仙侠", "修真"),
                tags = listOf("养肥", "长期追"),
                groupNames = "追更中",
                inBookshelf = true,
                showReadRecord = true,
                readTimeMillis = 3_720_000L,
            ),
        )
    }
}

@Preview(name = "详情页头部 · 未入架（无标签行）", showBackground = true)
@Composable
private fun BookInfoHeaderNotInShelfPreview() {
    LegadoTheme {
        BookInfoHeader(
            state = BookInfoUiState(
                name = "诡秘之主",
                author = "爱潜水的乌贼",
                originName = "笔趣阁",
                kinds = listOf("450万字", "玄幻"),
                showReadRecord = false,
            ),
            showTags = false,
        )
    }
}

@Preview(name = "信息行区", showBackground = true)
@Composable
private fun BookInfoInfoRowsPreview() {
    LegadoTheme {
        BookInfoInfoRows(
            state = BookInfoUiState(
                originName = "起点中文网",
                latestChapterTitle = "第 1245 章 归山",
                durChapterTitle = "第 1024 章 雪中行",
                readTimeMillis = 3_720_000L,
                showReadRecord = true,
            ),
        )
    }
}

@Preview(name = "动作区 + 摘要区", showBackground = true)
@Composable
private fun BookInfoActionsSummaryPreview() {
    LegadoTheme {
        Column {
            BookInfoActions(inBookshelf = true)
            BookInfoSummary(
                state = BookInfoUiState(
                    totalChapterNum = 1245,
                    durChapterTitle = "第 1024 章 雪中行",
                    latestChapterTitle = "第 1245 章 归山",
                    tocLoadFailed = false,
                ),
            )
        }
    }
}
