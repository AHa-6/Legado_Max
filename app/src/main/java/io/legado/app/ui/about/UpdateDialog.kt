package io.legado.app.ui.about

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.R
import io.legado.app.help.InnerBrowserLinkResolver
import io.legado.app.help.update.AppUpdate
import io.legado.app.lib.theme.eInkGrayscale
import io.legado.app.model.Download
import io.legado.app.ui.theme.pageCardContainerColor
import io.legado.app.ui.theme.pageTopBarColors
import io.legado.app.ui.widget.components.dialog.AppRadioChoiceDialog
import io.legado.app.ui.widget.components.dialog.BaseComposeDialogFragment
import io.legado.app.ui.widget.text.ScrollTextView
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin

/**
 * 应用更新对话框 - Compose 实现
 *
 * 用于显示新版本更新信息并提供下载功能，更新日志按 Markdown 渲染。
 *
 * 正文仍然复用原生 [ScrollTextView]：它自带五次插值器的嵌套惯性滚动与可选中行为，
 * Compose 侧的 `verticalScroll + Text` 无法等价替代，因此用 [AndroidView] 嵌进来，
 * 只有工具栏、溢出菜单与「所有版本」列表改成了 Compose。
 */
class UpdateDialog() : BaseComposeDialogFragment() {

    /**
     * 便利构造函数，用于传入更新信息自动构建参数。
     * 参数键沿用旧实现的 newVersion / updateBody / url / name，便于进程重建时原样恢复。
     */
    constructor(updateInfo: AppUpdate.UpdateInfo) : this() {
        arguments = Bundle().apply {
            putString("newVersion", updateInfo.tagName)
            putString("updateBody", updateInfo.updateLog)
            putString("url", updateInfo.downloadUrl)
            putString("name", updateInfo.fileName)
        }
    }

    private val newVersion: String? get() = arguments?.getString("newVersion")
    private val updateBody: String? get() = arguments?.getString("updateBody")
    private val downloadUrl: String? get() = arguments?.getString("url")
    private val fileName: String? get() = arguments?.getString("name")

    override fun onStart() {
        super.onStart()
        // 窗口宽度由 Window 决定，Compose 的 fillMaxWidth 管不到这一层，必须保留
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        super.onFragmentCreated(view, savedInstanceState)
        // 检查更新日志是否存在，不存在则关闭对话框
        if (updateBody == null) {
            toastOnUi("没有数据")
            dismiss()
        }
    }

    @Composable
    override fun DialogContent() {
        val updateLog = updateBody ?: return
        UpdateDialogContent(
            title = newVersion.orEmpty(),
            updateLog = updateLog,
            downloadUrl = downloadUrl,
            fileName = fileName,
            onDismiss = { dismiss() },
        )
    }
}

/**
 * 应用更新对话框内容
 *
 * @param title 新版本号，作为标题栏文字
 * @param updateLog Markdown 格式的更新日志
 * @param downloadUrl 当前版本的下载地址，为空时不响应下载
 * @param fileName 下载后的文件名
 * @param onDismiss 关闭回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateDialogContent(
    title: String,
    updateLog: String,
    downloadUrl: String?,
    fileName: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val topBarColors = pageTopBarColors()
    val cardColor = pageCardContainerColor()
    // 正文限高：合法更新日志可能很长，限高后交给 ScrollTextView 自己惯性滚动，避免顶穿屏幕
    val maxBodyHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    val bodyColor = MaterialTheme.colorScheme.onSurfaceVariant
    var overflowExpanded by remember { mutableStateOf(false) }
    // 非空即代表「所有版本」列表已加载完成、需要弹出
    var allVariants by remember { mutableStateOf<List<AppUpdate.UpdateInfo>?>(null) }

    val markwon = remember(context) {
        // Markwon 配置说明：
        // - InnerBrowserLinkResolver: 使 Markdown 中的链接点击后使用内置浏览器打开
        // - GlideImagesPlugin: 支持 Markdown 中的图片显示
        // - HtmlPlugin: 支持 Markdown 中的 HTML 标签
        // - TablePlugin: 支持 Markdown 中的表格渲染
        Markwon.builder(context)
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.linkResolver(InnerBrowserLinkResolver)
                }
            })
            .usePlugin(GlideImagesPlugin.create(context))
            .usePlugin(HtmlPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .build()
    }

    fun startDownload(url: String, name: String) {
        Download.start(context, url, name)
        context.toastOnUi(R.string.download_start)
    }

    fun loadAllVariants() {
        AppUpdate.giteeUpdate.getAllVariants(scope)
            .onSuccess { variants: List<AppUpdate.UpdateInfo> ->
                if (variants.isEmpty()) {
                    context.toastOnUi("没有其他版本")
                } else {
                    allVariants = variants
                }
            }
            .onError {
                context.toastOnUi(context.getString(R.string.check_update) + "\n" + it.localizedMessage)
            }
    }

    allVariants?.let { variants ->
        AppRadioChoiceDialog(
            title = stringResource(R.string.all_versions),
            options = variants.map { it.fileName },
            // 这里只是「选一个来下载」，没有持久选中态，因此不预选任何一项
            selectedIndex = -1,
            onSelect = { index ->
                val selected = variants[index]
                startDownload(selected.downloadUrl, selected.fileName)
                allVariants = null
            },
            onDismissRequest = { allVariants = null },
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Surface(
            // 弹层是独立窗口，页面那层灰阶罩不到，这里再贴一次
            modifier = Modifier
                .eInkGrayscale()
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = MaterialTheme.shapes.large,
            color = cardColor,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                TopAppBar(
                    title = {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                            )
                        }
                    },
                    actions = {
                        // 对应 app_update.xml 里 showAsAction="always" 的下载项
                        if (downloadUrl != null && fileName != null) {
                            IconButton(
                                onClick = { startDownload(downloadUrl, fileName) },
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Download,
                                    contentDescription = stringResource(R.string.action_download),
                                )
                            }
                        }
                        // 对应 showAsAction="never" 的「所有版本」，收进溢出菜单
                        IconButton(onClick = { overflowExpanded = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.more_menu),
                            )
                        }
                        DropdownMenu(
                            expanded = overflowExpanded,
                            onDismissRequest = { overflowExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.all_versions)) },
                                onClick = {
                                    overflowExpanded = false
                                    loadAllVariants()
                                },
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = topBarColors.containerColor,
                        titleContentColor = topBarColors.contentColor,
                    ),
                )
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxBodyHeight)
                        .padding(12.dp),
                    factory = { ctx ->
                        ScrollTextView(ctx, null).apply {
                            // 旧布局里这三个属性写在 dialog_update.xml 上，手动构造时需要补齐；
                            // setTextIsSelectable 会覆盖 init 里的 LinkMovementMethod，故在其后重新设置
                            setTextIsSelectable(true)
                            movementMethod = LinkMovementMethod.getInstance()
                            // setTextColor 有 int 与 ColorStateList 两个重载，Kotlin 无法合成 textColor 属性，必须显式调用
                            setTextColor(bodyColor.toArgb())
                        }
                    },
                    update = { textView ->
                        textView.setTextColor(bodyColor.toArgb())
                        // 宽度确定后 Markwon 才能正确换行与量图片，故 post 到下一帧；
                        // 用 tag 去重，避免每次重组都重排一遍 Markdown
                        if (textView.tag != updateLog) {
                            textView.tag = updateLog
                            textView.post {
                                markwon.setMarkdown(textView, updateLog)
                            }
                        }
                    },
                )
            }
        }
    }
}