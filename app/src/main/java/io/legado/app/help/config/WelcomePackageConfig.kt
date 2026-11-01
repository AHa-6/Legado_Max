package io.legado.app.help.config

import androidx.annotation.Keep
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import splitties.init.appCtx
import java.io.File

/**
 * 开屏管理：启动页配置包。
 *
 * 写法与 [TopBarConfig] 一致：日间/夜间分开存放，每套一个目录
 * `externalFiles/welcomePackages/day|night/<目录名>/`，内含 welcome.json 和可选图片。
 * 内置「默认」不落盘（因此也不参与备份），不可编辑、不可删除。
 *
 * 应用某一套时把值写回原有 pref key（welcomeImagePath(Dark)、welcomeShowText(Dark)、
 * welcomeShowIcon(Dark)、welcomeShowTime），启动页与其他改版仍按原 key 读取。
 * 夜间显示时间新增 [PreferKey.welcomeShowTimeDark]；[PreferKey.customWelcome] 改为自动维护。
 */
object WelcomePackageConfig {

    /** 内置「默认」的目录名（不落盘） */
    const val DEFAULT_DIR_NAME = "default"

    /** externalFiles 下的根目录名，也是备份条目名 */
    const val BACKUP_DIR_NAME = "welcomePackages"

    const val DEFAULT_SHOW_TIME = 500
    const val MAX_SHOW_TIME = 800

    private const val packageFileName = "welcome.json"
    private const val imageBaseName = "welcome_image"
    private const val migratedKey = "welcomePackageMigrated"

    @Volatile
    private var migrateChecked = false

    /**
     * @property imagePath 图片文件名（相对于包目录）
     * @property showTime 显示时间（毫秒），0 表示直接进入主界面
     */
    @Keep
    data class Config(
        var name: String = "",
        var isNightMode: Boolean = false,
        var imagePath: String? = null,
        var showText: Boolean = true,
        var showIcon: Boolean = true,
        var showTime: Int = DEFAULT_SHOW_TIME,
        var updatedAt: Long = System.currentTimeMillis(),
    )

    data class Entry(
        val config: Config,
        val isBuiltin: Boolean,
        val dirName: String,
        val localDir: File? = null,
    ) {
        /** 解析后的图片文件，不存在时为 null */
        val imageFile: File?
            get() {
                val path = config.imagePath?.takeIf { it.isNotBlank() } ?: return null
                val file = File(path)
                val resolved = if (file.isAbsolute) file else localDir?.let { File(it, path) }
                return resolved?.takeIf { it.isFile }
            }
    }

    val rootDir: File
        get() = appCtx.externalFiles.getFile(BACKUP_DIR_NAME)

    private fun typeDir(isNight: Boolean): File =
        rootDir.getFile(if (isNight) "night" else "day").apply { mkdirs() }

    private fun localDir(isNight: Boolean, dirName: String): File = typeDir(isNight).getFile(dirName)

    private fun activeKey(isNight: Boolean): String =
        if (isNight) PreferKey.welcomePackageNight else PreferKey.welcomePackageDay

    private fun defaultName(): String = appCtx.getString(R.string.welcome_default_name)

    fun defaultEntry(isNight: Boolean): Entry = Entry(
        Config(name = defaultName(), isNightMode = isNight, updatedAt = 0L),
        isBuiltin = true,
        dirName = DEFAULT_DIR_NAME,
    )

    /** 当前选中的目录名，未选时为 [DEFAULT_DIR_NAME] */
    fun activeDirName(isNight: Boolean): String =
        appCtx.getPrefString(activeKey(isNight))?.takeIf { it.isNotBlank() } ?: DEFAULT_DIR_NAME

    /** 「默认」在首位，其余按更新时间倒序 */
    fun loadEntries(isNight: Boolean): List<Entry> {
        migrateLegacyIfNeeded()
        val local = typeDir(isNight).listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { readEntry(it) }
            ?.sortedWith(
                compareByDescending<Entry> { it.config.updatedAt }
                    .thenBy { it.config.name }
                    .thenBy { it.dirName }
            )
            .orEmpty()
        return listOf(defaultEntry(isNight)) + local
    }

    fun currentEntry(isNight: Boolean): Entry {
        val dirName = activeDirName(isNight)
        return loadEntries(isNight).firstOrNull { it.dirName == dirName } ?: defaultEntry(isNight)
    }

    /** 按目录名查找，找不到返回 null */
    fun findEntry(isNight: Boolean, dirName: String?): Entry? {
        if (dirName.isNullOrBlank()) return null
        return loadEntries(isNight).firstOrNull { it.dirName == dirName }
    }

    private fun readEntry(dir: File): Entry? {
        val file = File(dir, packageFileName)
        if (!file.isFile) return null
        val config = GSON.fromJsonObject<Config>(file.readText()).getOrNull() ?: return null
        config.showTime = config.showTime.coerceIn(0, MAX_SHOW_TIME)
        return Entry(config, isBuiltin = false, dirName = dir.name, localDir = dir)
    }

    /**
     * 新增或更新一套开屏。
     *
     * @param oldEntry 为本地条目时原地更新，否则新建目录；新建时重名抛异常
     * @param newImage 不为 null 时替换图片；[Config.imagePath] 为空表示移除图片
     */
    fun addOrUpdate(config: Config, oldEntry: Entry? = null, newImage: File? = null): Entry {
        val name = config.name.trim()
        require(name.isNotBlank()) { appCtx.getString(R.string.input_is_empty) }
        val localOld = oldEntry?.takeIf { !it.isBuiltin }
        // 按显示名称判重（同一日夜类型内，含「默认」，编辑时排除自身）。
        // 目录名在新建时确定、改名不变，只按目录判重会出现有的名称能重复、有的不能
        val duplicated = loadEntries(config.isNightMode).any {
            it.config.name.trim().equals(name, ignoreCase = true) && it.dirName != localOld?.dirName
        }
        require(!duplicated) { appCtx.getString(R.string.welcome_name_exists) }
        val dirName = localOld?.dirName ?: uniqueDirName(config.isNightMode, name)
        val dir = localDir(config.isNightMode, dirName)
        dir.mkdirs()
        var imageName = config.imagePath?.takeIf { it.isNotBlank() }?.let { File(it).name }
        if (newImage != null && newImage.isFile) {
            val suffix = if (newImage.name.endsWith(".9.png", true)) "9.png"
            else newImage.extension.ifBlank { "jpg" }
            val target = File(dir, "$imageBaseName.$suffix")
            if (newImage.absolutePath != target.absolutePath) {
                clearImages(dir)
                newImage.copyTo(target, overwrite = true)
            }
            imageName = target.name
        } else if (imageName == null) {
            clearImages(dir)
        }
        val next = config.copy(
            name = name,
            imagePath = imageName,
            showTime = config.showTime.coerceIn(0, MAX_SHOW_TIME),
            updatedAt = System.currentTimeMillis(),
        )
        File(dir, packageFileName).writeText(GSON.toJson(next))
        val entry = Entry(next, isBuiltin = false, dirName = dirName, localDir = dir)
        // 编辑的是当前选中项：立即写回 pref
        if (activeDirName(next.isNightMode) == dirName) apply(entry)
        return entry
    }

    private fun clearImages(dir: File) {
        dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("$imageBaseName.") }
            ?.forEach { it.delete() }
    }

    /** 切换到指定开屏：把值写回原有 pref key */
    fun apply(entry: Entry) {
        val isNight = entry.config.isNightMode
        val imageKey = if (isNight) PreferKey.welcomeImageDark else PreferKey.welcomeImage
        val textKey = if (isNight) PreferKey.welcomeShowTextDark else PreferKey.welcomeShowText
        val iconKey = if (isNight) PreferKey.welcomeShowIconDark else PreferKey.welcomeShowIcon
        val timeKey = if (isNight) PreferKey.welcomeShowTimeDark else PreferKey.welcomeShowTime
        val image = if (entry.isBuiltin) null else entry.imageFile
        if (image != null) appCtx.putPrefString(imageKey, image.absolutePath) else appCtx.removePref(imageKey)
        appCtx.putPrefBoolean(textKey, entry.config.showText)
        appCtx.putPrefBoolean(iconKey, entry.config.showIcon)
        appCtx.putPrefInt(timeKey, entry.config.showTime.coerceIn(0, MAX_SHOW_TIME))
        appCtx.putPrefString(activeKey(isNight), entry.dirName)
        syncCustomWelcome()
    }

    /** 按目录名切换；找不到时不做改动，返回 false */
    fun applyDir(isNight: Boolean, dirName: String?): Boolean {
        val entry = findEntry(isNight, dirName) ?: return false
        apply(entry)
        return true
    }

    /** 恢复备份或目录被改名后，重新写回当前选中项（图片绝对路径随本机目录更新） */
    fun reapplyActive() {
        listOf(false, true).forEach { isNight ->
            val entry = findEntry(isNight, activeDirName(isNight)) ?: defaultEntry(isNight)
            apply(entry)
        }
    }

    /** 删除本地开屏；删除的是当前选中项时切回「默认」 */
    fun deleteLocal(entry: Entry) {
        if (entry.isBuiltin) return
        val isNight = entry.config.isNightMode
        FileUtils.delete(entry.localDir ?: localDir(isNight, entry.dirName), deleteRootDir = true)
        if (activeDirName(isNight) == entry.dirName) apply(defaultEntry(isNight))
    }

    /** customWelcome 自动维护：日夜任一边选了非「默认」即打开 */
    private fun syncCustomWelcome() {
        val custom = activeDirName(false) != DEFAULT_DIR_NAME || activeDirName(true) != DEFAULT_DIR_NAME
        appCtx.putPrefBoolean(PreferKey.customWelcome, custom)
    }

    /** 启动页显示时间：夜间未设置时沿用日间值 */
    fun showTime(isNight: Boolean): Int {
        val day = appCtx.getPrefInt(PreferKey.welcomeShowTime, DEFAULT_SHOW_TIME)
        return if (isNight) appCtx.getPrefInt(PreferKey.welcomeShowTimeDark, day) else day
    }

    private fun uniqueDirName(isNight: Boolean, preferred: String): String {
        val clean = preferred.normalizeFileName().ifBlank { "welcome_${System.currentTimeMillis()}" }
        var candidate = clean
        var index = 1
        // "default" 是内置「默认」的保留目录名（不落盘），用户包避开它（外部存储不区分大小写）
        while (candidate.equals(DEFAULT_DIR_NAME, ignoreCase = true) || localDir(isNight, candidate).exists()) {
            candidate = "${clean}_$index"
            index++
        }
        return candidate
    }

    /**
     * 旧设置迁移（只执行一次）：原来开了自定义欢迎页或改过显示时间的，
     * 生成一套「当前开屏」并选中，避免升级后启动页变样。
     */
    private fun migrateLegacyIfNeeded() {
        if (migrateChecked) return
        migrateChecked = true
        if (appCtx.getPrefBoolean(migratedKey)) return
        appCtx.putPrefBoolean(migratedKey, true)
        // 已有选中记录（如从新版备份恢复）就不再迁移
        if (!appCtx.getPrefString(PreferKey.welcomePackageDay).isNullOrBlank() ||
            !appCtx.getPrefString(PreferKey.welcomePackageNight).isNullOrBlank()
        ) return
        val custom = appCtx.getPrefBoolean(PreferKey.customWelcome)
        val time = appCtx.getPrefInt(PreferKey.welcomeShowTime, DEFAULT_SHOW_TIME)
        if (!custom && time == DEFAULT_SHOW_TIME) return
        listOf(false, true).forEach { isNight ->
            runCatching {
                val imageKey = if (isNight) PreferKey.welcomeImageDark else PreferKey.welcomeImage
                val textKey = if (isNight) PreferKey.welcomeShowTextDark else PreferKey.welcomeShowText
                val iconKey = if (isNight) PreferKey.welcomeShowIconDark else PreferKey.welcomeShowIcon
                val image = if (custom) appCtx.getPrefString(imageKey)?.let { File(it) }?.takeIf { it.isFile } else null
                val name = appCtx.getString(R.string.welcome_current_name)
                val dirName = uniqueDirName(isNight, name)
                val dir = localDir(isNight, dirName).apply { mkdirs() }
                val imageName = image?.let { src ->
                    val suffix = if (src.name.endsWith(".9.png", true)) "9.png" else src.extension.ifBlank { "jpg" }
                    val target = File(dir, "$imageBaseName.$suffix")
                    src.copyTo(target, overwrite = true)
                    target.name
                }
                val config = Config(
                    name = name,
                    isNightMode = isNight,
                    imagePath = imageName,
                    showText = if (custom) appCtx.getPrefBoolean(textKey, true) else true,
                    showIcon = if (custom) appCtx.getPrefBoolean(iconKey, true) else true,
                    showTime = time.coerceIn(0, MAX_SHOW_TIME),
                )
                File(dir, packageFileName).writeText(GSON.toJson(config))
                apply(Entry(config, isBuiltin = false, dirName = dirName, localDir = dir))
            }
        }
    }
}
