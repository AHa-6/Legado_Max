# 回退基线 20261101

本目录收集的是 Compose 改造前 / 改造中断点上的原始文件快照，用于把界面退回旧实现。
`MANIFEST.md5` 是本目录全部 `.bak` 的校验值清单。

## 对号表（.bak → 目标路径）

| .bak 文件 | 目标路径 |
| --- | --- |
| `UpdateDialog.kt.bak` | `app/src/main/java/io/legado/app/ui/about/UpdateDialog.kt` |
| `TextActionMenu.kt.bak` | `app/src/main/java/io/legado/app/ui/book/read/TextActionMenu.kt` |
| `NavigationBarManageActivity.kt.bak` | `app/src/main/java/io/legado/app/ui/config/NavigationBarManageActivity.kt` |
| `TopBarManageActivity.kt.bak` | `app/src/main/java/io/legado/app/ui/config/TopBarManageActivity.kt` |
| `WelcomeManageActivity.kt.bak` | `app/src/main/java/io/legado/app/ui/config/WelcomeManageActivity.kt` |
| `item_nav_bar_config.xml.bak` | `app/src/main/res/layout/item_nav_bar_config.xml` |
| `item_top_bar_config.xml.bak` | `app/src/main/res/layout/item_top_bar_config.xml` |
| `dialog_update.xml.bak` | `app/src/main/res/layout/dialog_update.xml` |
| `popup_action_menu.xml.bak` | `app/src/main/res/layout/popup_action_menu.xml` |
| `app_update.xml.bak` | `app/src/main/res/menu/app_update.xml` |
| `updateLog.bak` | `app/src/main/assets/web/help/md/updateLog.md` |

## 关键区别（还原前必读）

- `UpdateDialog.kt.bak`（4988B / `46608d081374f9e80688d0d28427e18b`）与上游 `main`、tag `beta-3.26.102716` 上那份完全一致，属纯上游版本，随时可从任意 tag 重新下载。
- `TextActionMenu.kt.bak`（19488B / `835deca4a0e2612c42787812e62e33dc`）与上游（16027B / `3c269da69362e3232cea2348df74d811`）**不一致**：它在 Compose 改造之前就已经是本地改过的版本，GitHub 上没有任何副本，只在这里和 sdcard 的 `legado_rollback_20261101/` 里。这是整批备份中唯一不可再生的一份。

## 恢复方式

单文件：`cp .rollback/20261101/<x>.bak <目标路径>`（先去掉 `.bak` 后缀）。
整批：按上表逐条覆盖。
还原后必须重新编译，不要只替换文件就认为界面已回退。