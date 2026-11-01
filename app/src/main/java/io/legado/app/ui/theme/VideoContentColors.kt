package io.legado.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.MaterialTheme
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.ColorUtils

@Immutable
data class VideoContentColors(
    val surface: Color,
    val cell: Color,
    val content: Color,
    val muted: Color,
    val accent: Color,
    val selectedContainer: Color,
    val onAccent: Color,
)

/**
 * 视频 UI 专属中性配色：面板表面 / 单元格 / 正文 / 次要文字 / 强调色 / 选中容器 / 选中文字。
 *
 * 中性面板跟随 App 昼夜模式（不继承带色调的主题背景），强调色取当前主题主色。
 * 用 Max 自己的 [ColorUtils] 与 [AppConfig.isNightTheme] 实现，不依赖 NG 的 NgColorMath。
 */
@Composable
fun videoContentColors(): VideoContentColors {
    val isDark = AppConfig.isNightTheme
    val primary = MaterialTheme.colorScheme.primary.toArgb()
    return remember(isDark, primary) {
        VideoContentColors(
            surface = if (isDark) Color(0xFF111315) else Color.White,
            cell = if (isDark) Color(0xFF1C1F23) else Color(0xFFF6F6F6),
            content = if (isDark) Color(0xFFF4F4F5) else Color(0xFF1D1D1F),
            muted = if (isDark) Color(0xFFADB1BC) else Color(0xFF6E6E73),
            accent = Color(primary),
            selectedContainer = Color(videoSelectedContainer(primary)),
            onAccent = Color.White,
        )
    }
}

/** 仅实心选中态压暗，保证白字/白勾清晰可读。 */
private fun videoSelectedContainer(primary: Int): Int {
    val background = android.graphics.Color.rgb(
        android.graphics.Color.red(primary),
        android.graphics.Color.green(primary),
        android.graphics.Color.blue(primary)
    )
    val white = android.graphics.Color.WHITE
    if (contrastRatio(background, white) >= 4.5) return background
    var low = 0f
    var high = 1f
    repeat(12) {
        val amount = (low + high) / 2
        val candidate = ColorUtils.blendColors(background, android.graphics.Color.BLACK, amount)
        if (contrastRatio(candidate, white) >= 4.5) high = amount else low = amount
    }
    return ColorUtils.blendColors(background, android.graphics.Color.BLACK, high)
}

/** WCAG 相对亮度对比度（借用 androidx.core.graphics.ColorUtils）。 */
private fun contrastRatio(a: Int, b: Int): Double {
    val la = androidx.core.graphics.ColorUtils.calculateLuminance(a)
    val lb = androidx.core.graphics.ColorUtils.calculateLuminance(b)
    val lighter = maxOf(la, lb)
    val darker = minOf(la, lb)
    return (lighter + 0.05) / (darker + 0.05)
}
