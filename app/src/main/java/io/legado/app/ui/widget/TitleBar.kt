package io.legado.app.ui.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.StyleRes
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.graphics.alpha
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import com.google.android.material.appbar.AppBarLayout
import io.legado.app.R
import io.legado.app.constant.Theme
import io.legado.app.lib.theme.elevation
import io.legado.app.utils.activity
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import splitties.views.bottomPadding
import splitties.views.topPadding

@Suppress("unused", "MemberVisibilityCanBePrivate")
class TitleBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppBarLayout(context, attrs) {

    val toolbar: Toolbar
    val menu: Menu
        get() = toolbar.menu

    /**
     * 紧凑形态的卡片层，仅 `app:compact="true"` 时非 null。
     *
     * 紧凑形态下 TitleBar 自身退化为「状态栏 inset + 四周留白」容器，顶栏实体
     * （底色、圆角、阴影、Toolbar）整体搬到这一层。因此 [setBackground]、
     * [setBackgroundResource]、[setBackgroundColor]、[getBackground]、[setElevation]
     * 全部在此转发：[io.legado.app.ui.widget.applyTopBarConfig] 与 [topBarContentColor]
     * 照常读写「TitleBar 的背景与阴影」，实际落在卡片上，配色管线无需任何改动。
     */
    private var compactCard: LinearLayout? = null

    /** 紧凑形态下承载 Toolbar 的行容器（固定 52dp 高） */
    private var compactRow: FrameLayout? = null

    /**
     * 紧凑形态的居中标题覆盖层。
     *
     * AppCompat [Toolbar] 的内置标题只能左对齐，标题居中要靠 MaterialToolbar——
     * 而本仓主题基类是 `Theme.AppCompat.DayNight.NoActionBar`，MaterialToolbar 会在
     * 构造时抛「需要 Theme.MaterialComponents」，47 个既有布局一起遭殃。
     * 所以这里把内置标题染成透明占位、另画一层居中文字：[title] 的读写语义与
     * ActionBar 的标题同步都不受影响。
     */
    private var compactTitle: TextView? = null

    /** 紧凑卡片四角圆角（px），供 [ViewOutlineProvider] 裁剪背景与勾勒阴影 */
    private var compactRadius = 0f

    /**
     * 内置标题是否已被压成透明。
     *
     * Toolbar 没有公开可读的"当前标题色"，所以只能自己记账：[syncCompactTitle] 每次
     * 布局都会跑，无脑 setTitleTextColor 会 requestLayout → onLayout → 再写，形成
     * 布局死循环。换肤时 [setTitleTextColor] 会把标题色刷回内容色，标志位在那里复位。
     */
    private var compactTitleSuppressed = false

    var title: CharSequence?
        get() = toolbar.title
        set(title) {
            if (toolbar.title != title) {
                toolbar.title = title
                // 标题改了不一定伴随布局（ActionBar 直接写 Toolbar 时也一样），
                // 紧凑形态的居中覆盖层要主动同步，否则会停在旧标题上
                syncCompactTitle()
            }
        }

    var subtitle: CharSequence?
        get() = toolbar.subtitle
        set(subtitle) {
            if (toolbar.subtitle != subtitle) {
                toolbar.subtitle = subtitle
            }
        }

    private val displayHomeAsUp: Boolean
    private val navigationIconTint: ColorStateList?
    private val navigationIconTintMode: Int
    private val fitStatusBar: Boolean
    private val fitNavigationBar: Boolean
    private val attachToActivity: Boolean
    internal val opaque: Boolean
    internal val ignoreTopBarOpacity: Boolean
    internal val skipTopBarConfig: Boolean
    internal val topBarTheme: Theme

    init {
        val a = context.obtainStyledAttributes(
            attrs, R.styleable.TitleBar,
            R.attr.titleBarStyle, 0
        )
        navigationIconTint = a.getColorStateList(R.styleable.TitleBar_navigationIconTint)
        navigationIconTintMode = a.getInt(R.styleable.TitleBar_navigationIconTintMode, 9)
        attachToActivity = a.getBoolean(R.styleable.TitleBar_attachToActivity, true)
        displayHomeAsUp = a.getBoolean(R.styleable.TitleBar_displayHomeAsUp, true)
        fitStatusBar = a.getBoolean(R.styleable.TitleBar_fitStatusBar, true)
        fitNavigationBar = a.getBoolean(R.styleable.TitleBar_fitNavigationBar, false)
        opaque = a.getBoolean(R.styleable.TitleBar_opaque, false)
        ignoreTopBarOpacity = a.getBoolean(R.styleable.TitleBar_ignoreTopBarOpacity, false)
        skipTopBarConfig = a.getBoolean(R.styleable.TitleBar_skipTopBarConfig, false)

        val navigationIcon = a.getDrawable(R.styleable.TitleBar_navigationIcon)
        val navigationContentDescription =
            a.getText(R.styleable.TitleBar_navigationContentDescription)
        val titleText = a.getString(R.styleable.TitleBar_title)
        val subtitleText = a.getString(R.styleable.TitleBar_subtitle)
        val themeMode = a.getInt(R.styleable.TitleBar_themeMode, 0)
        topBarTheme = when (themeMode) {
            1 -> Theme.Dark
            2 -> Theme.Light
            else -> Theme.Auto
        }

        // 紧凑形态：Toolbar 挂进卡片里的行容器；非紧凑时宿主就是 TitleBar 自己，
        // 层级与改造前完全一致。编辑模式下不建卡片，避免预览里多出一层无配置的空白。
        val isCompact = a.getBoolean(R.styleable.TitleBar_compact, false) && !isInEditMode
        val toolbarHost: ViewGroup = if (isCompact) buildCompactCard() else this
        when (themeMode) {
            1 -> inflate(context, R.layout.view_title_bar_dark, toolbarHost)
            else -> inflate(context, R.layout.view_title_bar, toolbarHost)
        }
        toolbar = findViewById(R.id.toolbar)
        if (isCompact) {
            // 行高由卡片里的行容器固定成 52dp，Toolbar 撑满它；否则 Toolbar 会按
            // actionBarSize（56dp）自量高度，紧凑等于白做
            // 重新赋回 layoutParams：只改字段不触发 requestLayout，构造函数里虽然还没量过，
            // 但显式赋值更稳，也避免后续有人把这段挪出构造函数时静默失效
            toolbar.layoutParams = toolbar.layoutParams?.also {
                it.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            // 覆盖层放在 Toolbar 之后 = 画在其上方。它不接收点击，触控会穿透到
            // 下面的返回键与菜单按钮
            compactTitle?.let { compactRow?.addView(it) }
        }

        toolbar.apply {
            navigationIcon?.let {
                this.navigationIcon = it
                this.navigationContentDescription = navigationContentDescription
            }

            if (a.hasValue(R.styleable.TitleBar_titleTextAppearance)) {
                this.setTitleTextAppearance(
                    context,
                    a.getResourceId(R.styleable.TitleBar_titleTextAppearance, 0)
                )
            }

            if (a.hasValue(R.styleable.TitleBar_titleTextColor)) {
                this.setTitleTextColor(a.getColor(R.styleable.TitleBar_titleTextColor, -0x1))
            }

            if (a.hasValue(R.styleable.TitleBar_subtitleTextAppearance)) {
                this.setSubtitleTextAppearance(
                    context,
                    a.getResourceId(R.styleable.TitleBar_subtitleTextAppearance, 0)
                )
            }

            if (a.hasValue(R.styleable.TitleBar_subtitleTextColor)) {
                this.setSubtitleTextColor(a.getColor(R.styleable.TitleBar_subtitleTextColor, -0x1))
            }


            if (a.hasValue(R.styleable.TitleBar_contentInsetLeft)
                || a.hasValue(R.styleable.TitleBar_contentInsetRight)
            ) {
                this.setContentInsetsAbsolute(
                    a.getDimensionPixelSize(R.styleable.TitleBar_contentInsetLeft, 0),
                    a.getDimensionPixelSize(R.styleable.TitleBar_contentInsetRight, 0)
                )
            }

            if (a.hasValue(R.styleable.TitleBar_contentInsetStart)
                || a.hasValue(R.styleable.TitleBar_contentInsetEnd)
            ) {
                this.setContentInsetsRelative(
                    a.getDimensionPixelSize(R.styleable.TitleBar_contentInsetStart, 0),
                    a.getDimensionPixelSize(R.styleable.TitleBar_contentInsetEnd, 0)
                )
            }

            if (a.hasValue(R.styleable.TitleBar_contentInsetStartWithNavigation)) {
                this.contentInsetStartWithNavigation = a.getDimensionPixelOffset(
                    R.styleable.TitleBar_contentInsetStartWithNavigation, 0
                )
            }

            if (a.hasValue(R.styleable.TitleBar_contentInsetEndWithActions)) {
                this.contentInsetEndWithActions = a.getDimensionPixelOffset(
                    R.styleable.TitleBar_contentInsetEndWithActions, 0
                )
            }

            if (!titleText.isNullOrBlank()) {
                this.title = titleText
            }

            if (!subtitleText.isNullOrBlank()) {
                this.subtitle = subtitleText
            }

            if (a.hasValue(R.styleable.TitleBar_contentLayout)) {
                // 紧凑形态下附加内容（搜索框等）也要进卡片，才能被同一层圆角与底色包住；
                // 卡片是纵向 LinearLayout，附加内容排在 Toolbar 行下方，与改造前一致
                inflate(
                    context,
                    a.getResourceId(R.styleable.TitleBar_contentLayout, 0),
                    compactCard ?: this,
                )
            }
        }

        if (!isInEditMode) {
            if (fitStatusBar || fitNavigationBar) {
                setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
                    val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                    // 仅当 insets > 0 时才设置，避免部分设备 WindowInsets 派发异常时
                    // 用 0 覆盖已有的 padding
                    if (fitStatusBar && insets.top > 0) {
                        topPadding = insets.top
                    }
                    if (fitNavigationBar && insets.bottom > 0) {
                        bottomPadding = insets.bottom
                    }
                    windowInsets
                }
                // 兜底：部分设备（低版本 Android / 鸿蒙）上 WindowInsets 可能不派发，
                // 此时 insets.top 始终为 0。作为后备，在视图挂载后主动检查并补充状态栏高度。
                post {
                    if (fitStatusBar && topPadding == 0) {
                        val resourceId =
                            resources.getIdentifier("status_bar_height", "dimen", "android")
                        if (resourceId > 0) {
                            topPadding = resources.getDimensionPixelSize(resourceId)
                        }
                    }
                    if (fitNavigationBar && bottomPadding == 0) {
                        val resourceId =
                            resources.getIdentifier("navigation_bar_height", "dimen", "android")
                        if (resourceId > 0) {
                            bottomPadding = resources.getDimensionPixelSize(resourceId)
                        }
                    }
                }
            }

            applyTopBarConfig()

            stateListAnimator = null
        }
        a.recycle()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachToActivity()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        syncCompactTitle()
    }

    /**
     * 把 Toolbar 的当前标题镜像到居中覆盖层。
     *
     * 放在 onLayout 而不是 setter 里：`setSupportActionBar` 之后 Activity 标题由
     * ActionBar 直接写进 Toolbar，不经过 [title] setter，只有布局时镜像才不会漏。
     * 同时把内置标题染成透明占位——它左对齐且无法居中，留着会与覆盖层重影。
     */
    private fun syncCompactTitle() {
        val overlay = compactTitle ?: return
        val current = toolbar.title
        if (overlay.text?.toString() != current?.toString()) {
            overlay.text = current
        }
        val visibility = if (current.isNullOrEmpty()) View.GONE else View.VISIBLE
        if (overlay.visibility != visibility) {
            overlay.visibility = visibility
        }
        // applyTopBarContentColor 每次换肤都会把标题色刷回内容色，这里再压回透明。
        // 必须先比对当前色再写：TextView.setTextColor 不做相等判断，无脑写会
        // requestLayout → onLayout → 再写，形成布局死循环。
        if (!compactTitleSuppressed) {
            toolbar.setTitleTextColor(Color.TRANSPARENT)
            compactTitleSuppressed = true
        }
        applyCompactTitleInset(overlay)
    }

    /**
     * 给居中标题覆盖层算左右安全内缩，避免长标题压到返回键或菜单图标上。
     *
     * 覆盖层横跨整行、且画在 Toolbar 之上，标题一旦比可用宽度长就会与图标重叠。
     * 用固定内缩不行：右侧占用随页面菜单数量变化（订阅源两页是 2 个常驻 action +
     * overflow ≈ 144dp），写死大值在小屏上会把「订阅源」这种三字标题也截断。
     * 所以按 Toolbar 当前子 View 实测左右占用，取较大者做**对称**内缩——对称才能
     * 保证文字仍然屏幕居中，同时任何一侧都不会被压到。
     *
     * 菜单是在 onCreateOptionsMenu 里才灌进来的（晚于首次布局），所以这里必须能在
     * 后续 onLayout 里重算；赋值前比对旧值，避免 padding → requestLayout → onLayout
     * 自激。
     */
    private fun applyCompactTitleInset(overlay: TextView) {
        val toolbarWidth = toolbar.width
        if (toolbarWidth <= 0) return
        val titleText = toolbar.title?.toString()
        var leftOccupied = 0
        var rightOccupied = 0
        for (i in 0 until toolbar.childCount) {
            val child = toolbar.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            // 内置标题已被染成透明占位，它的位置不代表真实占用，跳过
            if (child is TextView && child.text?.toString() == titleText) continue
            if (child.right <= toolbarWidth / 2) {
                leftOccupied = maxOf(leftOccupied, child.right)
            } else {
                rightOccupied = maxOf(rightOccupied, toolbarWidth - child.left)
            }
        }
        val gap = resources.getDimensionPixelSize(R.dimen.title_bar_compact_title_gap)
        val inset = maxOf(leftOccupied, rightOccupied) + gap
        if (overlay.paddingLeft != inset || overlay.paddingRight != inset) {
            overlay.setPadding(inset, overlay.paddingTop, inset, overlay.paddingBottom)
        }
    }

    /**
     * 构建紧凑形态的卡片层，返回 Toolbar 的行容器。
     *
     * 层级：TitleBar（留白 + inset）→ 卡片（底色 / 圆角 / 阴影）→ 行容器（52dp）
     * → Toolbar + 居中标题覆盖层。附加内容（搜索框）排在行容器下方、仍在卡片内。
     */
    private fun buildCompactCard(): ViewGroup {
        val res = resources
        val outerH = res.getDimensionPixelSize(R.dimen.title_bar_compact_outer_horizontal)
        val outerTop = res.getDimensionPixelSize(R.dimen.title_bar_compact_outer_top)
        val outerBottom = res.getDimensionPixelSize(R.dimen.title_bar_compact_outer_bottom)
        val rowHeight = res.getDimensionPixelSize(R.dimen.title_bar_compact_height)
        compactRadius = res.getDimension(R.dimen.title_bar_compact_corner_radius)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // 壁纸层是 BitmapShader，不认背景 Drawable 的圆角；靠 outline 裁剪才能四角
            // 一致，顺带这个 outline 也是 elevation 阴影的形状来源
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, compactRadius)
                }
            }
            stateListAnimator = null
            layoutParams = AppBarLayout.LayoutParams(
                AppBarLayout.LayoutParams.MATCH_PARENT,
                AppBarLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(outerH, outerTop, outerH, outerBottom) }
        }
        // 阴影要画进卡片外的留白里，外层不能裁剪子 View
        clipChildren = false
        // AppBarLayout 自带主题主色背景（Widget.Design.AppBarLayout 里的 ?attr/colorPrimary）。
        // 紧凑模式下底色全部转发进卡片，外层若留着这块默认色，会把四周留白与状态栏区域
        // 糊成一整块主色矩形，悬浮观感直接消失，所以外层显式置全透明。
        // 走 super 而非本类的 setBackground：后者的 alpha<255 分支会把 elevation 拨到 0.1f。
        super.setBackground(ColorDrawable(Color.TRANSPARENT))
        val row = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                rowHeight,
            )
        }
        val overlay = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            )
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            // 覆盖层绝不能吃点击：返回键与菜单按钮都在它下面
            isClickable = false
            isFocusable = false
            // 字号降一档（20sp → 16sp）。紧凑只从字号与留白上省，图标命中区仍是
            // Toolbar 自带的 48dp（accessibility.md §15.3 下限），不靠削热区换空间
            setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                res.getDimension(R.dimen.font_size_middle),
            )
            visibility = View.GONE
        }
        compactTitle = overlay
        compactRow = row
        compactCard = card
        card.addView(row)
        addView(card)
        return row
    }

    fun setNavigationOnClickListener(clickListener: ((View) -> Unit)) {
        toolbar.setNavigationOnClickListener(clickListener)
    }

    fun setTitle(titleId: Int) {
        toolbar.setTitle(titleId)
        syncCompactTitle()
    }

    fun setSubTitle(subtitleId: Int) {
        toolbar.setSubtitle(subtitleId)
    }

    fun setTitleTextColor(@ColorInt color: Int) {
        toolbar.setTitleTextColor(color)
        // applyTopBarContentColor 每次换肤都会走这里把标题色刷回内容色，
        // 紧凑形态需要在下一次布局里重新压回透明，所以标志位在此复位
        compactTitleSuppressed = false
    }

    fun setTitleTextAppearance(@StyleRes resId: Int) {
        toolbar.setTitleTextAppearance(context, resId)
    }

    fun setSubTitleTextColor(@ColorInt color: Int) {
        toolbar.setSubtitleTextColor(color)
    }

    fun setSubTitleTextAppearance(@StyleRes resId: Int) {
        toolbar.setSubtitleTextAppearance(context, resId)
    }

    fun setTextColor(@ColorInt color: Int) {
        setTitleTextColor(color)
        setSubTitleTextColor(color)
        // 紧凑形态的居中标题是自绘覆盖层，换肤时必须跟着走同一个内容色，
        // 否则深色顶栏上会留下一行黑字（applyTopBarContentColor 只刷 Toolbar）
        compactTitle?.setTextColor(color)
    }

    fun setColorFilter(@ColorInt color: Int) {
        val colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)
        toolbar.navigationIcon?.colorFilter = colorFilter
        toolbar.overflowIcon?.colorFilter = colorFilter
    }

    override fun setBackgroundColor(color: Int) {
        val card = compactCard
        if (card == null) {
            if (color.alpha < 255) {
                //这里不能改为0f,改为0f在横屏模式下文字和图标颜色会变
                elevation = 0.1f
            }
            super.setBackgroundColor(color)
            return
        }
        // 紧凑形态下「顶栏全透明」等于把卡片抹掉：卡片是这一形态唯一的可见实体，
        // 透明 + 0 阴影会让人看不出顶栏改过（2026-11-05 反馈的「订阅源顶栏无变化」，
        // 设备侧 transparentNavBar=true 时 applyTopBarConfig 正是走这条透明分支）。
        // 悬浮卡片是按布局 opt-in 的形态，这里给它兜一个不依赖顶栏配色管线的承载面。
        val resolved = if (color.alpha == 0) compactFallbackColor() else color
        card.setBackgroundColor(resolved)
        if (resolved.alpha == 0) {
            elevation = 0.1f
        } else if (color.alpha == 0) {
            // 兜底色是不透明的，按常规顶栏给阴影，卡片才浮得起来
            elevation = context.elevation
        } else if (color.alpha < 255) {
            //这里不能改为0f,改为0f在横屏模式下文字和图标颜色会变
            elevation = 0.1f
        }
    }
    override fun setBackground(background: Drawable?) {
        val card = compactCard
        if (card == null) {
            if (background is ColorDrawable && background.alpha < 255) {
                //这里不能改为0f,改为0f在横屏模式下文字和图标颜色会变
                elevation = 0.1f
            }
            super.setBackground(background)
            return
        }
        // 与 setBackgroundColor 同一条兜底：全透明的 ColorDrawable 换成卡片承载面
        val resolved = if (background is ColorDrawable && background.alpha == 0) {
            ColorDrawable(compactFallbackColor())
        } else {
            background
        }
        if (resolved is ColorDrawable && resolved.alpha < 255) {
            //这里不能改为0f,改为0f在横屏模式下文字和图标颜色会变
            elevation = 0.1f
        }
        card.background = resolved
    }

    /**
     * 紧凑卡片的兜底承载面。
     *
     * 取 `@color/background_card`（昼 md_grey_100 / 夜 md_grey_850）而不是主题 surface：
     * 与阅读页长按菜单卡片同源（见 [io.legado.app.ui.book.read.TextActionMenu] 的取色注释），
     * 两处悬浮卡片因此昼夜同色。
     *
     * 换色后 [topBarContentColor] 会自动跟着走——它读的是 `background.resolveSolidColor()`，
     * 拿到的正是这块兜底色，标题与图标的深浅因此仍然与卡面对比正确，不需要额外分支。
     */
    private fun compactFallbackColor(): Int =
        ContextCompat.getColor(context, R.color.background_card)

    /** 同 [setBackground]：紧凑形态下底色在卡片层，资源 id 也要落到卡片上 */
    override fun setBackgroundResource(@DrawableRes resid: Int) {
        val card = compactCard
        if (card == null) {
            super.setBackgroundResource(resid)
        } else {
            card.setBackgroundResource(resid)
        }
    }

    /**
     * 紧凑形态下底色在卡片层，这里转发以保持取色语义不变——
     * [topBarContentColor] 靠 `background.resolveSolidColor()` 反推顶栏深浅来决定
     * 标题与图标的颜色，读错背景就会算出错的配色。
     */
    override fun getBackground(): Drawable? =
        compactCard?.background ?: super.getBackground()

    /**
     * 阴影同样落在卡片上：外层必须保持 0，否则紧凑留白处会多出一圈矩形投影。
     */
    override fun setElevation(elevation: Float) {
        val card = compactCard
        if (card == null) {
            super.setElevation(elevation)
        } else {
            super.setElevation(0f)
            card.elevation = elevation
        }
    }

    fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, fullScreen: Boolean) {
//        if (fitStatusBar) {
//            val topPadding = if (!isInMultiWindowMode && fullScreen) context.statusBarHeight else 0
//            setPadding(paddingLeft, topPadding, paddingRight, paddingBottom)
//        }
    }

    private fun attachToActivity() {
        if (attachToActivity) {
            activity?.let {
                it.setSupportActionBar(toolbar)
                it.supportActionBar?.setDisplayHomeAsUpEnabled(displayHomeAsUp)
                // 图标与标题复用同一个取色入口：此前这里按"主题主色 / 页面写死的 themeMode"取，
                // 与标题"按顶栏底色取"是两套规则，于是图标会与标题不同色、也会与顶栏底色撞色
                setColorFilter(topBarContentColor())
            }
        }
    }

}
