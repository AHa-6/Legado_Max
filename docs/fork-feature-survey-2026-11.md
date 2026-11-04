# 六个改版高价值功能实现对照报告

调查时间：2026-11-04
调查对象：readC（分支 `own`）、arc、md3（HapeLee/legado-with-MD3）、NG、mr、mgz
基线：`AHa-6/Legado_Max` @ `518d95b`（本地 `master` = 远端 `compose-rework`）
取证方式：GitHub Trees API 递归文件树 + blob 内容解码 + 本地 NG 仓全文 grep。除标注"未读文件体"外，均为实际检索结果。

---

## 0. 结论速览

| 功能 | 蓝本改版 | 规模（文件数） | Max 现状 | 移植分级 |
|---|---|---|---|---|
| AutoTask 定时任务全链路 | mgz | 14 主 + 6 测试 | 零基础 | A（可直接对照移植） |
| 备份体系增强 | arc | 9 主 + 3 测试 | 已有 `help/storage` | A（增量替换） |
| 听书评论下载 / 离线评论 | readC | 4（含 DAO） | 零基础 | A（需 Room 迁移） |
| NG 全部书签体系 | NG | 11 | 已有 `Bookmark` 实体/DAO（18 文件） | B（按 Max 体系重写） |
| 主题引擎 / 主题包 | md3 | 12+ | 已有 `lib/theme/ThemeStore`（3 文件） | B（重写适配） |
| 液态玻璃组件族 | md3 / NG | 8 | 零基础 | B（配色须走 Max 令牌） |
| AI 层（Provider / Agent / 净化） | arc 36、readC 29、NG 12+ | 12～36 | 无 `help/ai` 目录 | C（等于新造模块） |
| 听书缓存四件套 | readC | 24（`help/tts` 全量） | 无 `help/tts` 目录 | C（等于新造模块） |
| AI 章节净化 | readC | 5 + 依赖 AI 层 | 零基础 | C |
| 颜文字 | **无** | **0** | 零基础 | C（无蓝本，需自研） |
| JS 书源引擎 | mr | — | — | 不可移植（Flutter/Dart） |

---

## 1. 可直接移植（A 类）

### 1.1 mgz — AutoTask 定时任务

mgz 侧 20 个文件构成完整闭环，分层清晰：

数据层 `data/entities/AutoTaskRule.kt`、`data/dao/AutoTaskRuleDao.kt`；
模型层 `model/AutoTask.kt`、`AutoTaskProtocol.kt`、`AutoTaskRunner.kt`、`AutoTaskSchedulePolicy.kt`；
服务层 `service/AutoTaskJobService.kt`、`AutoTaskScheduler.kt`；
UI 层 `ui/autoTask/` 下 `AutoTaskActivity`、`AutoTaskAdapter`、`AutoTaskEditActivity`、`AutoTaskDebugActivity`、`ImportAutoTaskDialog`、`ImportAutoTaskViewModel`；
测试层 6 个：`AutoTaskPersistenceContractTest`、`AutoTaskCoreTest`、`AutoTaskSchedulePolicyTest`、`AutoTaskEditActivitySourceTest`、`AutoTaskImportContractTest`、`AutoTaskImportMergeTest`。

Max 侧实测：`AutoTask` 相关文件 0 个，`model/AutoTask.kt` 不存在。移植时必须处理的事项：新增 Room 实体意味着数据库版本迁移；`AutoTaskAdapter` 是 View 体系实现，若要 Compose 化需按 `docs/project-rules/compose/` 规范重写 UI 层（模型/服务/DAO 可原样落地）；持久化契约测试恰好覆盖了 `api-compat-rules.md` 关心的"旧备份包恢复后行为不变"，建议连同测试一起搬。

### 1.2 arc — 备份体系增强

arc 的 `help/storage` 共 12 个 Kotlin 文件：主体 `Backup.kt`、`BackupAES.kt`、`BackupArchiveExtractor.kt`、`BackupConfig.kt`、`Restore.kt`、`RestoreJournal.kt`、`SourceRuntimeBackup.kt`、`ReaderDataRepair.kt`、`ImportOldData.kt`；测试 `BackupArchiveExtractorTest`、`RestoreJournalStateTest`、`RestorePackageDirectoryTest`。

Max 已有同名包，因此这是增量替换而非新造。其中价值最高的三个：`BackupArchiveExtractor`（归档解包，带独立单测，边界清晰）、`RestoreJournal`（恢复过程日志/状态机，有 `RestoreJournalStateTest` 覆盖，可用于诊断"恢复失败但不知停在哪一步"）、`SourceRuntimeBackup`（运行时书源快照，解决"改了书源又改坏了，想回到刚才那版"）。`BackupAES` 涉及加密格式，替换前必须确认与 Max 现有备份文件的向后兼容——这是唯一需要谨慎的一处。

### 1.3 readC — 听书评论下载 / 离线评论

评论相关共 9 文件，其中不依赖 AI 层的 4 个可直接落地：`PendingReviewComment.kt` + `PendingReviewCommentDao.kt`（待审评论的本地暂存），以及配套 UI。Max 侧实测 `PendingReviewComment` 命中 0。

剩余 5 个 AI 净化文件（`AiChapterPurifyRecord` + `Dao`、`AiChapterPurifyConfig`、`AiChapterPurifyHelper`、`AiChapterPurifyPreprocessor`、`AiChapterPurifyService`）归入 C 类，因为它们直接调用 readC 的 `help/ai`（29 文件），Max 没有这一层。

---

## 2. 需按 Max 体系重写（B 类）

### 2.1 NG — 全部书签体系（11 文件）

`data/entities/Bookmark.kt`、`data/dao/BookmarkDao.kt`、`ui/bookmark/` 下的 `AllBookmarkActivity`、`AllBookmarkScreen`、`AllBookmarkViewModel`、`BookmarkFilter`、`BookmarkFilterMenu`、`CatalogBookmarkFilterPanel`、`BookmarkDialog`、`BookmarkDialog.Ui`、`SelectionBookmark`。

Max 已有 18 个 `Bookmark*` 文件（含 `data/entities/Bookmark.kt`、`data/dao/BookmarkDao.kt`、`ui/book/read/page/entities/BookmarkNoteMark.kt`），所以缺的不是数据模型，而是"跨书全部书签页 + 多级筛选"这一层交互。重写的落点必须对齐 Max 的 Compose 规范：顶栏用本轮新增的 `AppPageCompactTopBar`（或 `AppPageTopBar`），配色一律走 `pageTopBarColors()`（§7.7.1 禁止调用点另配色，§7.7.4 要求顶栏内标题/图标同色），尺寸进 `AppDimens`（Composable 体内禁止裸写 `.dp`），文本走 `MaterialTheme.typography`（`AppDimens` 内禁止定义字号）。NG 的 `NgTheme`/`NgGlassSurface` 不可原样搬。

回归验证点与上一版一致：点已有划线看第一层菜单是否常驻、"删除划线"是否落在书签槽位、备注框能否自动拉起输入法；全部书签页书→章两级折叠与时间线导轨不穿章节头。

### 2.2 md3 — 主题引擎与主题包（12+ 文件，含体量最大的一个）

按文件体量排序：`help/config/ThemePackageManager.kt`（51.5 KB，全仓最大单文件之一）、`data/repository/ThemePackageSettingsRepository.kt`（17.5 KB）、`help/config/ThemeImportExport.kt`（17.1 KB）、`help/config/ThemeConfigStore.kt`（10.3 KB）、`lib/theme/ThemeStore.kt`（8.7 KB）、`help/config/ReadStyleResolver.kt`（6.8 KB）、`ui/theme/ThemeResolver.kt`（4.6 KB）、`lib/theme/ThemeStoreInterface.kt`（961 B）、`lib/theme/ThemeStorePrefKeys.kt`（929 B）、`domain/gateway/ThemePackageSettingsGateway.kt`（234 B）；测试 `ThemePackageManagerRollbackTest`（11.7 KB）、`ThemePackageSettingsMappingTest`（3.2 KB）、`ThemeImportExportTest`、`ReadStyleResolverTest`。

Max 侧实测：`ThemePackage` 命中 0，但 `lib/theme/ThemeStore.kt`、`ThemeStoreInterface.kt`、`ThemeStorePrefKeys.kt` 三个文件已存在。这意味着 Max 有主题存储的地基、没有主题包（打包/导入/导出/回滚）机制。适配的关键约束是 pref key 不改名——md3 的 `ThemeStorePrefKeys` 与 Max 现有同名文件必然有 key 冲突，必须以 Max 现有 key 为准做映射层，而不是整文件覆盖，否则会影响其他改版读取备份。`ThemePackageManagerRollbackTest` 值得优先移植，它是"主题包装坏了能退回去"的唯一保障。

### 2.3 md3 / NG — 液态玻璃组件族（8 文件）

md3 侧：`ui/widget/components/GlassDefaults.kt`（1.2 KB）、`components/card/GlassCard.kt`（7.2 KB）、`components/topbar/GlassMediumFlexibleTopAppBar.kt`（10.9 KB）、`GlassTopAppBar.kt`（3.5 KB）、`GlassSmallTopAppBar.kt`（2.1 KB）、`DynamicTopAppBar.kt`（6.3 KB）、`TopBarLiquidGlass.kt`（3.2 KB）、`components/reader/ReaderMenuGlass.kt`（3.4 KB）；另有 `ui/about/MiuixAboutScreen.kt`、`ui/util/MiuixUtils.kt`、`components/topbar/MiuixScrollBehavior.kt` 三个 Miuix 相关文件。

几何与模糊算法可以照搬，配色不可以：Max 的顶栏颜色必须由 `pageTopBarColors()` 决定，玻璃层的底色/描边需要接到 `CommonPageColors.kt` 的既有令牌上（本轮待办①已在此新增 `pageTopBarCardBackground()`，可作为接入点）。用户此前已明确授权：「我的」页面的液体玻璃效果如果做不好，允许参考或照搬 NG 的实现——该项在 NG 侧同样零 `NgGlassSurface` 之外的额外依赖。

### 2.4 md3 — 书籍详情页（5 文件，已在 `/tmp/md3ref/`）

`BookInfoScreen.kt`（2532 行）等 5 个文件。本轮待办②已用纯 XML 完成版式对齐（封面左置、书名与标签并排到封面右侧、书名改白色），未走 Compose 化。若后续要把详情页整体 Compose 化，md3 这 5 个文件是版式参考，但功能面必须按 Max 补全——Max 的详情页功能比 md3 多，不能照搬。

---

## 3. 等于新造模块（C 类）

### 3.1 AI 层（三个改版各有一套，Max 完全没有）

Max 侧实测：`app/src/main/java/io/legado/app/help/ai` 目录不存在，`help/ai` 命中 0。

三个蓝本的取向不同：arc 最重，36 文件，含 Agent 运行时与记忆库；readC 29 文件，偏"给章节净化和听书分镜供能"；NG 12+ 文件，最轻量，含 `AiPromptStore`、`OpenAiCompatibleProvider`、`GoogleAiProvider`、`AiChatInteraction`、`AiPurifyHelper`、`AgentModeDefinition`。

若要做 AI 功能，NG 那套是最现实的起点（Provider 抽象 + Prompt 存储 + 净化辅助，依赖面小），arc 的 Agent 运行时与记忆库属于重工程，不建议一期做。

### 3.2 readC — 听书缓存与 TTS 体系（`help/tts` 24 文件）

Max 侧实测：`app/src/main/java/io/legado/app/help/tts` 目录不存在，`TtsCache` 命中 0。

readC 侧 24 个文件：缓存四件套 `TtsCacheManager.kt`（752 行）、`TtsCacheStore.kt`（344 行）、`TtsCacheArchive.kt`、`TtsCacheLog.kt`；播放与路由 `TtsStreamingAudio.kt`、`TtsSpeedPolicy.kt`、`ReadAloudTtsRouter.kt`、`TtsChapterUnits.kt`；引擎接入 `TtsCapabilityRegistry.kt`、`TtsEngineModels.kt`、`TtsEngineStore.kt`、`TtsHttpForwarderClient.kt`、`TtsScriptEngineClient.kt`、`TtsWebSocketEngineClient.kt`、`TtsSynthesisText.kt`；分镜与多角色 `AiTtsStoryboardHelper.kt`、`AiStoryboardBatchAnalyzer.kt`、`AiStoryboardCacheDialog.kt`、`AiBatchAnalyzeDialog.kt`、`AiMultiVoiceConfig.kt`、`StoryboardCacheStore.kt`、`StoryboardModels.kt`；自动化与投屏 `BookTtsAutomationConfig.kt`、`BookTtsCastingCoordinator.kt`。

配套的 UI 与服务层：`ui/book/audio/` 5 文件（`AudioPlayActivity.kt`、`SliderPopup.kt`、`config/AudioPlayDisplaySettingDialog.kt`、`config/AudioPlayFontSettingDialog.kt`、`config/AudioSkipCredits.kt`）、`model/ReadAloudUiState.kt`、`service/ReadAloudProgress.kt`、`service/ReadAloudFloatingHost.kt`、`service/ReadAloudFloatingObstruction.kt`、`plugin/ReadAloudEnginePlugin.kt`、`plugin/ReadAloudEngines.kt`、`receiver/BluetoothReadAloudSkip.kt`、`ui/book/read/config/ReadAloudDialog.kt`、`ReadAloudConfigDialog.kt`、`ui/book/read/config/SpeakEngineDialog.kt`、`SpeakEngineViewModel.kt`。

关于"听书时间条"的载体，取证结果需要如实说明：全仓 `timeline` 命中 0，`slider` 唯一命中是 `ui/book/audio/SliderPopup.kt`。因此时间条不是以 `timeline` 命名的独立文件，其载体最可能是 `SliderPopup.kt` 配合 `AudioPlayActivity.kt` 与 `model/ReadAloudUiState.kt`（后者是 UI 状态的单一来源）。本轮只做了文件树级取证，未读这三个文件的文件体，落地前需要先读一遍确认状态字段。分镜与多角色那 7 个文件依赖 AI 层（3.1），不能脱离 3.1 单独移植。

---

## 4. 明确不做的两项

### 4.1 颜文字 — 六个改版全部零证据

这项在功能清单里被提到，但取证结果是否定的，如实记录：

- 文件路径级：md3（20702 条路径）、readC（15517）、arc（17887）、mgz（16492）、mr（2896）五个改版，用 `emoji|kaomoji|emoticon|颜文字|表情|sticker|meme|reaction` 检索，合计仅 2 条命中，且均为误报——md3 那条是 `ReaderMoreActionsSheet.kt` 里的字符串 `reAction` 被 `reaction` 误匹配（已定位到偏移 551270 并核对上下文），mgz 那条是测试文件名 `RuntimeMediaStabilityTest.kt` 中的 `mediaStab` 被 `sticker`/`meme` 类模式误匹配。
- 字符串资源级：取 md3 `app/src/main/res/values-zh-rCN/strings.xml`（blob `6c988f8c…`，解码 229429 字符）全文检索 `emoji|kaomoji|emoticon|颜文字`，零命中。该文件是中文界面文案的主要载体，若存在颜文字功能，这里必然有对应文案。
- 本地全仓 grep：`/tmp/ng`（NG 本地克隆）全仓检索 `emoji|kaomoji|emoticon|颜文字|表情`，零命中。

结论：颜文字不是从这六个改版可以"看看怎么实现的"的功能——它们里面都没有。要么它来自清单之外的第七个来源，要么需要按 Max 自己的需求全新设计（例如作为阅读器长按菜单的一个文本插入面板）。在拿到出处之前，不应把它排进移植计划。

### 4.2 mr — 技术栈不可移植

mr 是 Flutter/Dart 实现，其 JS 书源是 Dart 引擎重写，无法作为 Android/Kotlin 侧的移植蓝本。mgz 侧对应能力只有 `NativeBaseSource.kt` 一个文件，价值有限。

---

## 5. 建议的推进顺序

按"单位风险产出比"排序：

1. **arc 备份增强中的 `BackupArchiveExtractor` + `RestoreJournal`**（A 类，各自带单测，不动数据库 schema，收益是备份出问题时可诊断）。
2. **mgz AutoTask**（A 类，14 主文件 + 6 测试，模型/服务层可原样落地，需一次 Room 迁移；UI 层若要 Compose 化则额外走 B 类流程）。
3. **md3 主题包机制**（B 类，先移植 `ThemePackageManagerRollbackTest` 建立安全网，再做 key 映射层，最后接 `ThemeImportExport`）。
4. **NG 全部书签页**（B 类，Max 数据层已就绪，只补 UI 与筛选，可直接复用本轮新增的 `AppPageCompactTopBar`）。
5. **液态玻璃组件族**（B 类，几何照搬 + 配色接 Max 令牌，「我的」页面有明确的照搬授权）。
6. **readC 听书缓存**（C 类，24 文件 + 服务层改造，工程量最大，建议在 1–5 稳定后单独立项）。
7. **NG AI 层**（C 类，12+ 文件起步，作为 readC AI 净化和分镜的前置依赖）。

前 5 项都不需要新建顶层模块目录，可在现有包结构内落地；第 6、7 项需要新增 `help/tts` 与 `help/ai` 两个目录，属于结构性变更，建议各自单独一个提交并附测试。

---

## 附：取证边界说明

本报告基于文件树、文件大小、blob 内容与本地 grep，属"结构与命名层"取证。除已明确读取过文件体的项（Max 侧 `AppPageTopBar.kt`、`CommonPageColors.kt`、`Dimensions.kt`、`ExploreShowScreen.kt`、`activity_book_info.xml`、`BookInfoActivity.kt`；md3 侧 `BookInfoScreen.kt`、readC 侧 `TtsCacheManager.kt`/`TtsCacheStore.kt`）之外，其余改版的文件均未逐个读取文件体。分级中"A 类"的判断依据是分层结构与依赖面，落地前仍需读对应文件确认内部依赖。
