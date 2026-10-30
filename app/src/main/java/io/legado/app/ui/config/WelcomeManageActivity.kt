package io.legado.app.ui.config

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ActivityTopBarManageBinding
import io.legado.app.databinding.ItemTopBarConfigBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.WelcomePackageConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyNavigationBarMargin
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 开屏管理 Activity。
 *
 * 界面与顶栏管理一致（复用其布局）：日间/夜间两个标签页，列表首项为内置「默认」，
 * 每套开屏可设置名称、图片、是否显示文字、是否显示图标、显示时间。
 */
class WelcomeManageActivity : BaseActivity<ActivityTopBarManageBinding>() {

    override val binding by viewBinding(ActivityTopBarManageBinding::inflate)

    private val adapter by lazy { Adapter(this) }
    private var entries: List<WelcomePackageConfig.Entry> = emptyList()
    private var isNightMode = false
    private var editingEntry: WelcomePackageConfig.Entry? = null
    private var pendingConfig: WelcomePackageConfig.Config? = null
    /** 编辑中新选的图片（临时文件），保存时复制进开屏目录 */
    private var pendingImage: File? = null
    private var editingDialog: LinearLayout? = null

    private val selectImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let(::onImagePicked)
    }

    companion object {
        private const val PREF_KEY_IS_NIGHT = "welcomeManageIsNight"
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = getString(R.string.welcome_manage)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        binding.recyclerView.adapter = adapter
        binding.tvAddConfig.text = getString(R.string.add_welcome)
        binding.tvAddConfig.setOnClickListener { showEditDialog(null) }
        binding.tvAddConfig.applyNavigationBarMargin(withInitialMargin = true)
        initTabs()
        loadPackages()
    }

    private fun initTabs() = binding.run {
        isNightMode = getPrefBoolean(PREF_KEY_IS_NIGHT, AppConfig.isNightTheme)
        updateTabSelection()
        tabDay.setOnClickListener { switchTab(false) }
        tabNight.setOnClickListener { switchTab(true) }
    }

    private fun switchTab(night: Boolean) {
        if (isNightMode == night) return
        isNightMode = night
        putPrefBoolean(PREF_KEY_IS_NIGHT, night)
        updateTabSelection()
        loadPackages()
    }

    private fun updateTabSelection() {
        val activeColor = accentColor
        val normal = ContextCompat.getColor(this, R.color.primaryText)
        val selectedBg = ContextCompat.getDrawable(this, R.drawable.bg_theme_tab_selected)
        binding.tvTabDay.setTextColor(if (!isNightMode) activeColor else normal)
        binding.tabDay.background = if (!isNightMode) selectedBg else null
        binding.tvTabNight.setTextColor(if (isNightMode) activeColor else normal)
        binding.tabNight.background = if (isNightMode) selectedBg?.constantState?.newDrawable() else null
    }

    private fun loadPackages() {
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) { WelcomePackageConfig.loadEntries(isNightMode) }
            }.onSuccess {
                entries = it
                adapter.setItems(entries)
                val type = getString(if (isNightMode) R.string.night else R.string.day)
                binding.tvSummary.text = "$type · ${getString(R.string.welcome_manage_summary)}"
            }.onFailure {
                binding.tvSummary.text = it.localizedMessage
            }
        }
    }

    private fun showEditDialog(entry: WelcomePackageConfig.Entry?) {
        if (entry?.isBuiltin == true) {
            toastOnUi(R.string.navigation_bar_default_readonly)
            return
        }
        editingEntry = entry
        // 上次取消编辑留下的临时图片
        pendingImage?.delete()
        pendingImage = null
        pendingConfig = entry?.config?.copy()
            ?: WelcomePackageConfig.Config(name = nextPackageName(), isNightMode = isNightMode)
        openEditDialog()
    }

    /** 显示编辑框；保存失败时也用它重新打开，已填内容和已选图片保留 */
    private fun openEditDialog() {
        val root = buildEditView()
        editingDialog = root
        alert(if (editingEntry == null) R.string.add else R.string.edit) {
            customView { ScrollView(this@WelcomeManageActivity).apply { addView(root) } }
            okButton { saveEditingPackage() }
            cancelButton()
        }
    }

    private fun buildEditView(): LinearLayout {
        val config = pendingConfig!!
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dp, 8.dp, 8.dp, 8.dp)
            addView(EditText(context).apply {
                tag = "name"
                hint = getString(R.string.welcome_manage)
                setText(config.name)
                setSingleLine(true)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 48.dp)
            })
            addView(optionRow(getString(R.string.background_image), imageLabel(config)) { showImageSelector() })
            addView(optionRow(getString(R.string.show_welcome_text), checkLabel(config.showText)) {
                config.showText = !config.showText
                refreshEditDialog()
            })
            addView(optionRow(getString(R.string.show_icon), checkLabel(config.showIcon)) {
                config.showIcon = !config.showIcon
                refreshEditDialog()
            })
            addView(optionRow(getString(R.string.welcome_show_time), "${config.showTime} ms") {
                NumberPickerDialog(this@WelcomeManageActivity)
                    .setTitle(getString(R.string.welcome_show_time))
                    .setMinValue(0)
                    .setMaxValue(WelcomePackageConfig.MAX_SHOW_TIME)
                    .setValue(config.showTime)
                    .show {
                        config.showTime = it.coerceIn(0, WelcomePackageConfig.MAX_SHOW_TIME)
                        refreshEditDialog()
                    }
            })
        }
    }

    private fun optionRow(title: String, value: String, onClick: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp, 0, 14.dp, 0)
            background = ContextCompat.getDrawable(context, R.drawable.bg_config_card)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 46.dp)
                .apply { topMargin = 8.dp }
            addView(TextView(context).apply {
                text = title
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.primaryText))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = value
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.secondaryText))
            })
            setOnClickListener { onClick() }
        }
    }

    private fun refreshEditDialog() {
        val root = editingDialog ?: return
        root.findViewWithTag<EditText>("name")?.text?.toString()?.trim()?.let { pendingConfig?.name = it }
        root.removeAllViews()
        val rebuilt = buildEditView()
        while (rebuilt.childCount > 0) {
            val child = rebuilt.getChildAt(0)
            rebuilt.removeView(child)
            root.addView(child)
        }
    }

    private fun showImageSelector() {
        val config = pendingConfig ?: return
        val hasImage = pendingImage != null || !config.imagePath.isNullOrBlank()
        val actions = buildList {
            add(getString(R.string.select_image))
            if (hasImage) add(getString(R.string.delete))
        }
        selector(getString(R.string.background_image), actions) { _, index ->
            if (index == 0) {
                selectImage.launch { mode = HandleFileContract.IMAGE }
            } else {
                pendingImage = null
                config.imagePath = null
                refreshEditDialog()
            }
        }
    }

    /** 选图后先复制到临时目录，保存时再复制进开屏目录（保留 .9.png 后缀） */
    private fun onImagePicked(uri: Uri) {
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    val displayName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                        ?: uri.lastPathSegment.orEmpty()
                    val suffix = when {
                        displayName.endsWith(".9.png", true) -> "9.png"
                        displayName.contains('.') -> displayName.substringAfterLast('.')
                        else -> "jpg"
                    }
                    val dir = externalFiles.getFile("welcomeTemp").apply { mkdirs() }
                    val file = File(dir, "pick_${System.currentTimeMillis()}.$suffix")
                    contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    } ?: throw IllegalArgumentException(getString(R.string.file_not_exist))
                    file
                }
            }.onSuccess { file ->
                pendingImage = file
                pendingConfig?.imagePath = file.absolutePath
                refreshEditDialog()
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun saveEditingPackage() {
        val config = pendingConfig ?: return
        val name = editingDialog?.findViewWithTag<EditText>("name")?.text?.toString()?.trim().orEmpty()
        config.name = name
        if (name.isBlank()) {
            toastOnUi(R.string.input_is_empty)
            openEditDialog()
            return
        }
        val oldEntry = editingEntry
        val image = pendingImage
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    WelcomePackageConfig.addOrUpdate(config.copy(name = name), oldEntry, image)
                }
            }.onSuccess {
                // 只有保存成功才删除临时图片
                withContext(Dispatchers.IO) { image?.delete() }
                if (pendingImage == image) pendingImage = null
                toastOnUi(R.string.success)
                loadPackages()
            }.onFailure {
                // 保存失败（如重名）：保留临时图片并重新打开编辑框，不用重新选图
                toastOnUi(it.localizedMessage)
                openEditDialog()
            }
        }
    }

    private fun applyPackage(entry: WelcomePackageConfig.Entry) {
        WelcomePackageConfig.apply(entry)
        loadPackages()
        toastOnUi("${getString(R.string.applied)}：${entry.config.name}")
    }

    private fun deletePackage(entry: WelcomePackageConfig.Entry) {
        alert(R.string.delete, R.string.sure_del) {
            yesButton {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { WelcomePackageConfig.deleteLocal(entry) }
                    loadPackages()
                }
            }
            noButton()
        }
    }

    private fun showActions(entry: WelcomePackageConfig.Entry) {
        val actions = buildList {
            add(R.string.apply)
            if (!entry.isBuiltin) {
                add(R.string.edit)
                if (entry.dirName != WelcomePackageConfig.activeDirName(entry.config.isNightMode)) {
                    add(R.string.delete)
                }
            }
        }
        selector(entry.config.name, actions.map { getString(it) }) { _, index ->
            when (actions[index]) {
                R.string.apply -> applyPackage(entry)
                R.string.edit -> showEditDialog(entry)
                R.string.delete -> deletePackage(entry)
            }
        }
    }

    private fun imageLabel(config: WelcomePackageConfig.Config): String = getString(
        if (pendingImage != null || !config.imagePath.isNullOrBlank()) R.string.top_bar_wallpaper_selected
        else R.string.top_bar_wallpaper_unselected
    )

    private fun checkLabel(checked: Boolean): String = if (checked) "✔" else "—"

    private fun nextPackageName(): String {
        val base = getString(R.string.welcome_manage)
        val used = entries.map { it.config.name }.toSet()
        for (index in 1..999) {
            val name = "$base $index"
            if (name !in used) return name
        }
        return "$base ${System.currentTimeMillis()}"
    }

    private fun buildInfoText(entry: WelcomePackageConfig.Entry, isActive: Boolean): String = buildString {
        if (isActive) {
            append(getString(R.string.current_applied))
            append(" · ")
        }
        if (entry.imageFile != null) {
            append(getString(R.string.background_image))
            append(" · ")
        }
        if (entry.config.showText) {
            append(getString(R.string.show_welcome_text))
            append(" · ")
        }
        if (entry.config.showIcon) {
            append(getString(R.string.show_icon))
            append(" · ")
        }
        append(getString(R.string.welcome_show_time_ms, entry.config.showTime))
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    inner class Adapter(context: Context) :
        RecyclerAdapter<WelcomePackageConfig.Entry, ItemTopBarConfigBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemTopBarConfigBinding {
            return ItemTopBarConfigBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemTopBarConfigBinding,
            item: WelcomePackageConfig.Entry,
            payloads: MutableList<Any>
        ) {
            binding.apply {
                tvName.text = item.config.name
                tvBuiltin.visibility = if (item.isBuiltin) View.VISIBLE else View.GONE
                val isActive = item.dirName == WelcomePackageConfig.activeDirName(item.config.isNightMode)
                tvInfo.text = buildInfoText(item, isActive)
                tvApply.text = getString(if (isActive) R.string.applied else R.string.apply)
                tvApply.setTextColor(
                    if (isActive) accentColor else ContextCompat.getColor(context, R.color.primaryText)
                )
                tvEdit.visibility = if (item.isBuiltin) View.GONE else View.VISIBLE
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemTopBarConfigBinding) {
            binding.apply {
                tvApply.setOnClickListener { entries.getOrNull(holder.layoutPosition)?.let(::applyPackage) }
                tvEdit.setOnClickListener { entries.getOrNull(holder.layoutPosition)?.let(::showEditDialog) }
                tvMore.setOnClickListener { entries.getOrNull(holder.layoutPosition)?.let(::showActions) }
                root.setOnClickListener { entries.getOrNull(holder.layoutPosition)?.let(::showActions) }
            }
        }
    }
}
