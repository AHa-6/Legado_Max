package io.legado.app.ui.theme

import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.Theme
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.TopBarConfig
import io.legado.app.lib.theme.ThemeStateStore
import io.legado.app.lib.theme.elevation
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.transparentNavBar
import io.legado.app.ui.widget.resolveTopBarContentColor
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.eventObservable
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

data class PageTopBarColors(
    val containerColor: Color,
    val contentColor: Color,
    val cornerRadius: Dp = 0.dp,
    val shadowElevation: Dp = 0.dp,
    val wallpaperFile: File? = null,
    val wallpaperAlpha: Float = 1f,
)

@Composable
fun pageTopBarColors(): PageTopBarColors {
    val topBarConfigVersion = rememberTopBarConfigVersion()
    val context = LocalContext.current
    val config = TopBarConfig.currentConfig(context, AppConfig.isNightTheme)
    val transparentNavBar = context.transparentNavBar
    val backgroundColor = if (config.style == TopBarConfig.STYLE_REGULAR) {
        TopBarConfig.resolveBackgroundColor(config)
    } else {
        config.tagBarColor ?: context.primaryColor
    }
    val alphaPercent = if (transparentNavBar) {
        0
    } else if (config.style == TopBarConfig.STYLE_REGULAR) {
        config.wallpaperAlpha
    } else {
        config.tagBarAlpha
    }
    val cornerRadius = if (config.style == TopBarConfig.STYLE_REGULAR) {
        val radiusPx = context.resources.getDimension(R.dimen.ui_panel_radius) *
            TopBarConfig.resolveCornerScale(config).coerceIn(0f, 3f)
        with(LocalDensity.current) { radiusPx.toDp() }
    } else {
        0.dp
    }
    val shadowElevation = when {
        transparentNavBar -> 0.dp
        alphaPercent < 100 -> 0.1.dp
        else -> with(LocalDensity.current) { context.elevation.toDp() }
    }
    val containerColor = TopBarConfig.withOpacity(backgroundColor, alphaPercent)
    val wallpaperFile = TopBarConfig.currentWallpaperFile(context, AppConfig.isNightTheme)
        ?.takeIf { !transparentNavBar && config.style == TopBarConfig.STYLE_REGULAR }
    // 内容色与 View 侧 TitleBar 走同一个入口（见 resolveTopBarContentColor）：底色与壁纸两层
    // 都要传，由它先按各自的不透明度合成成"前景实际压着的颜色"再判深浅——只拿壁纸本身的深浅
    // 判定会把"44% 透明壁纸 + 米白底色"判成深底而配上白字（2026-10-12 反馈的顶栏文字看不清）。
    // 顶栏透明时两层 alpha 都是 0，等价于没传，会落到窗口背景亮度上
    val contentColor = Color(
        resolveTopBarContentColor(
            context = context,
            topBarTheme = Theme.Auto,
            topBarBackground = containerColor,
            topBarWallpaperColor = TopBarConfig.wallpaperAverageColor(wallpaperFile)
                ?.let { TopBarConfig.withOpacity(it, alphaPercent) },
        ),
    )
    return PageTopBarColors(
        containerColor = Color(containerColor),
        contentColor = contentColor,
        cornerRadius = cornerRadius,
        shadowElevation = shadowElevation,
        wallpaperFile = wallpaperFile,
        wallpaperAlpha = TopBarConfig.opacityToAlpha(alphaPercent) / 255f,
    )
}

@Composable
fun PageTopBarContainer(
    colors: PageTopBarColors,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.pageTopBarBackground(colors)) {
        content()
    }
}

@Composable
fun Modifier.pageTopBarBackground(colors: PageTopBarColors): Modifier {
    val shape = RoundedCornerShape(
        bottomStart = colors.cornerRadius,
        bottomEnd = colors.cornerRadius,
    )
    return this
        .fillMaxWidth()
        .shadow(colors.shadowElevation, shape, clip = false)
        .clip(shape)
        .background(colors.containerColor)
        .drawTopBarWallpaper(colors)
}

@Composable
private fun Modifier.drawTopBarWallpaper(colors: PageTopBarColors): Modifier {
    val wallpaper = colors.wallpaperFile
    val context = LocalContext.current
    val targetWidth = context.resources.displayMetrics.widthPixels.coerceAtLeast(1)
    val targetHeight = with(LocalDensity.current) { 160.dp.roundToPx() }.coerceAtLeast(1)
    val wallpaperBitmap = remember(
        wallpaper?.absolutePath,
        wallpaper?.lastModified(),
        targetWidth,
        targetHeight,
    ) {
        wallpaper?.takeIf { it.exists() && it.isFile }?.let { file ->
            runCatching {
                BitmapUtils.decodeBitmap(file.absolutePath, targetWidth, targetHeight)
            }.getOrNull()
        }
    }
    return if (wallpaperBitmap == null) {
        this
    } else {
        this.drawWithContent {
            drawIntoCanvas { canvas ->
                val dstWidth = size.width.roundToInt().coerceAtLeast(1)
                val dstHeight = size.height.roundToInt().coerceAtLeast(1)
                val scale = max(
                    dstWidth / wallpaperBitmap.width.toFloat(),
                    dstHeight / wallpaperBitmap.height.toFloat(),
                )
                val srcWidth = (dstWidth / scale).roundToInt().coerceAtMost(wallpaperBitmap.width)
                val srcHeight = (dstHeight / scale).roundToInt().coerceAtMost(wallpaperBitmap.height)
                val srcLeft = ((wallpaperBitmap.width - srcWidth) / 2).coerceAtLeast(0)
                val srcTop = ((wallpaperBitmap.height - srcHeight) / 2).coerceAtLeast(0)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    alpha = (colors.wallpaperAlpha.coerceIn(0f, 1f) * 255).roundToInt()
                }
                canvas.nativeCanvas.drawBitmap(
                    wallpaperBitmap,
                    Rect(srcLeft, srcTop, srcLeft + srcWidth, srcTop + srcHeight),
                    Rect(0, 0, dstWidth, dstHeight),
                    paint,
                )
            }
            drawContent()
        }
    }
}

@Composable
private fun rememberTopBarConfigVersion(): Int {
    val lifecycleOwner = LocalLifecycleOwner.current
    var version by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = Observer<Boolean> { isNightMode ->
            if (isNightMode == AppConfig.isNightTheme) {
                version += 1
            }
        }
        val observable = eventObservable<Boolean>(EventBus.TOP_BAR_CHANGED)
        observable.observe(lifecycleOwner, observer)
        onDispose {
            observable.removeObserver(observer)
        }
    }
    // 主题色板变化（ThemeConfig.applyTheme 广播的版本号）同样要让顶栏重算：
    // 过去只有 TOP_BAR_CHANGED 会触发，色板已换而事件未到的一瞬间顶栏会停在旧配色
    return version + ThemeStateStore.version
}

@Composable
fun pageCardContainerColor(): Color = MaterialTheme.colorScheme.surfaceVariant

@Composable
fun pageCardElevatedContainerColor(): Color {
    val background = MaterialTheme.colorScheme.background
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    return if (background.luminance() < 0.18f) {
        lerp(surface, onSurface, 0.06f).copy(alpha = 0.98f)
    } else {
        surface.copy(alpha = 0.95f)
    }
}

/**
 * 分组面板容器色（设置类页面的成组卡片）。
 *
 * 与首页模块卡片 `GlassCard` 的默认容器色取同一口径：半透明 `surfaceVariant`，
 * 让壁纸透出来——设置页与首页都是"壁纸上的卡片"，透明度不一致会显得两套观感。
 * 暗色下不做加亮：首页那套同样只调 alpha，保持一致才能"像首页一样"。
 */
@Composable
fun pagePanelContainerColor(): Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)

@Composable
fun pageHeaderContainerColor(): Color {
    val background = MaterialTheme.colorScheme.background
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    return if (background.luminance() < 0.18f) {
        lerp(surfaceVariant, onSurface, 0.08f).copy(alpha = 0.92f)
    } else {
        surfaceVariant.copy(alpha = 0.7f)
    }
}

@Composable
fun pageSecondaryTextColor(): Color {
    val background = MaterialTheme.colorScheme.background
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    return if (background.luminance() < 0.18f) {
        lerp(onSurfaceVariant, onSurface, 0.32f)
    } else {
        onSurfaceVariant
    }
}

@Composable
fun pageAccentColor(): Color {
    val background = MaterialTheme.colorScheme.background
    val primary = MaterialTheme.colorScheme.primary
    return if (background.luminance() < 0.18f) {
        lerp(primary, Color.White, 0.2f)
    } else {
        primary
    }
}

@Composable
fun pageSurfaceVariantColor(): Color {
    val background = MaterialTheme.colorScheme.background
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    return if (background.luminance() < 0.18f) {
        lerp(surfaceVariant, onSurface, 0.08f)
    } else {
        surfaceVariant
    }
}

@Composable
fun pageMutedIconTint(): Color {
    val background = MaterialTheme.colorScheme.background
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    return if (background.luminance() < 0.18f) {
        lerp(onSurfaceVariant, onSurface, 0.24f).copy(alpha = 0.78f)
    } else {
        onSurfaceVariant.copy(alpha = 0.5f)
    }
}

/**
 * 顶栏容器颜色（向后兼容）。
 * 使用 MaterialTheme 的 secondary 色作为顶栏背景色。
 */
@Composable
fun pageTopBarContainerColor(): Color = MaterialTheme.colorScheme.secondary

/**
 * ========== 设置面板材质（对齐 NG 的静态玻璃配方） ==========
 *
 * 这组颜色决定「我的」、视频设置等页面的面板观感，取值刻意照搬 NG 在其
 * `NgSettingsCardSurface` 里对 API < 31 设备使用的那一套：
 *
 * - 容器：**单层**半透明白（昼 `#70FFFFFF` / 夜 `#3AFFFFFF`）。
 * - 描边：细发丝线（昼 `#5AFFFFFF` / 夜 `#26FFFFFF`），只用来勾出边界。
 * - 图标底：#66FFFFFF / 夜 #33FFFFFF，配圆角方块做成 NG 那种「色块图标」。
 *
 * ## 为什么不再叠五层
 * API 31 以下没有 RenderEffect / RuntimeShader，真模糊做不出来。用多层半透明去
 * 「假装」玻璃时，每层都自带 alpha，叠在壁纸上会把背景压成一块脏灰；而且渐变方向
 * 与壁纸内容无关，看上去像污渍而不是玻璃。NG 在同样这些设备上走的就是
 * 「一层薄雾 + 一道细边」，观感反而干净——面板好不好看与它是不是大面板无关，
 * 差别只在这套材质配方。
 */

/** 墨水屏：不做任何半透明叠加，直接用不透明底色保证可读性 */
private val glassIsEInk: Boolean get() = AppConfig.isEInkMode

/** 夜间主题 */
private val glassIsNight: Boolean get() = AppConfig.isNightTheme

/**
 * 面板容器色：单层薄雾。
 *
 * 昼 #70FFFFFF（alpha 112/255）、夜 #3AFFFFFF（alpha 58/255），与 NG 的
 * `ng_settings_item` 同值；墨水屏退回不透明 surface。
 */
@Composable
fun glassPanelContainerTopColor(): Color {
    if (glassIsEInk) return MaterialTheme.colorScheme.surface
    return Color.White.copy(alpha = if (glassIsNight) 58f / 255f else 112f / 255f)
}

/**
 * 面板容器色下半部分。
 *
 * 与 [glassPanelContainerTopColor] 取同一个值——原来这里做的是「顶亮底深」的对角渐变，
 * 正是那股脏灰感的来源之一，现在刻意压成平色，只靠描边和圆角表达材质。
 */
@Composable
fun glassPanelContainerBottomColor(): Color = glassPanelContainerTopColor()

/** 面板描边色（发丝线）：昼 #5AFFFFFF / 夜 #26FFFFFF */
@Composable
fun glassPanelBorderColor(): Color {
    if (glassIsEInk) return MaterialTheme.colorScheme.outline
    return Color.White.copy(alpha = if (glassIsNight) 38f / 255f else 90f / 255f)
}

/**
 * 表面光泽色。
 *
 * 固定透明：NG 的设置卡片不画顶部光泽，这一层在深色壁纸上尤其容易显出「油膜」感。
 */
@Composable
fun glassPanelSurfaceGlossColor(): Color = Color.Transparent

/**
 * 边缘高光色。
 *
 * 固定透明：斜向高光是叠层时代最主要的一层「假反光」，NG 的设置卡片没有任何高光描边。
 */
@Composable
fun glassPanelHighlightColor(): Color = Color.Transparent

/**
 * 深度边缘色。
 *
 * 固定透明：底部暗色带是为了营造厚度，但它把卡片下沿糊成一条脏边，NG 不用。
 */
@Composable
fun glassPanelDepthEdgeColor(): Color = Color.Transparent

/**
 * 行首图标底色（做成圆角色块，NG 的标志性观感）。
 *
 * 昼 #66FFFFFF；夜间用容器色系（对应 NG 的 selectedContainer），墨水屏用 surfaceVariant。
 */
@Composable
fun glassPanelIconContainerColor(): Color = when {
    glassIsEInk -> MaterialTheme.colorScheme.surfaceVariant
    glassIsNight -> MaterialTheme.colorScheme.secondaryContainer
    else -> Color.White.copy(alpha = 102f / 255f)
}

/**
 * 行首图标前景色。
 *
 * 昼间走页面强调色（对应 NG 的 `colors.primary`）；夜间走 onSecondaryContainer；
 * 墨水屏走 onSurfaceVariant。
 */
@Composable
fun glassPanelIconContentColor(): Color = when {
    glassIsEInk -> MaterialTheme.colorScheme.onSurfaceVariant
    glassIsNight -> MaterialTheme.colorScheme.onSecondaryContainer
    else -> MaterialTheme.colorScheme.primary
}

/**
 * 面板内行分隔线颜色。
 *
 * 面板底已经是半透明白，行分隔线若用 Material 的 `outlineVariant`（本身偏灰）会显出一条
 * 比外框更重的灰线，把薄雾切成一块块硬格子。这里改成与描边同源的一点点白，
 * 在昼夜两种底色上都只是「轻轻一道」。
 */
@Composable
fun glassPanelDividerColor(): Color {
    if (glassIsEInk) return MaterialTheme.colorScheme.outlineVariant
    return Color.White.copy(alpha = if (glassIsNight) 22f / 255f else 54f / 255f)
}
