#!/usr/bin/env bash
# Legado_Max —— 版本备份到 GitHub 的统一入口
#
# 用途：每完成一个新版本（代码改完并通过编译）后跑一次，把当前工作树
#       提交成一条 commit、打上版本 tag、推到 fork 的 compose-rework 分支，
#       并把离线 bundle 重建到 sdcard。
#
# 设计要点：
#   * 令牌不落盘。每次都从 Operit 的 DataStore 现取，只用于命令行内联 URL，
#     不写进 .git/config，也不写进任何被提交的文件。
#   * 分支固定 compose-rework。本地历史是 git init 造出的孤立根提交，与
#     fork 的 main 无共同祖先，推 main 必被 non-fast-forward 拒绝，加 --force
#     会抹掉 main 上原有的自有提交。所以只往 compose-rework 这条独立分支推。
#   * 版本号来自 app/build.gradle 的 releaseTime()/releaseCode()，是编译时刻的
#     时间函数；tag 名直接用产物的 versionName，事后能一一对上。
#
# 用法：
#   tools/gh_backup.sh                 # 提交 + 打 tag + 推送 + 重建 bundle
#   tools/gh_backup.sh --dry-run       # 只打印将要执行的动作，不写任何东西
#
# 退出码：0 成功；1 前置检查失败；2 推送失败

set -u

BRANCH="compose-rework"
REMOTE_URL="https://github.com/AHa-6/Legado_Max.git"
PB="/data/user/0/com.ai.assistance.operit/files/datastore/github_auth_preferences.preferences_pb"
BUNDLE_DIR="/sdcard/全 Oprtit AI专用/legado_git_backup_20261101"

DRY_RUN=0
[ "${1:-}" = "--dry-run" ] && DRY_RUN=1

cd "$(dirname "$0")/.." || exit 1
ROOT="$(pwd)"
echo "== repo: $ROOT"

# ---- 1. 前置检查 -------------------------------------------------------------
if ! git rev-parse --git-dir >/dev/null 2>&1; then
    echo "!! 这里不是 git 仓库"; exit 1
fi

if [ -n "$(git status --porcelain)" ]; then
    echo "== 工作树有未提交改动，将一并提交："
    git status --short
else
    echo "== 工作树干净，无新改动"
fi

# 版本号优先取自本次编译产物名（releaseTime() 是编译时刻的时间函数，
# 直接读 build.gradle 拿不到实际值）；没有产物时退回 UTC 当前时间。
VERSION=""
APK="$(ls -t app/build/outputs/apk/*/*/legado_*_universal.apk 2>/dev/null | head -1)"
if [ -n "$APK" ]; then
    VERSION="$(basename "$APK" | grep -oE '3\.[0-9]{2}\.[0-9]{6}' | head -1)"
fi
[ -z "$VERSION" ] && VERSION="$(date -u +%y.%m%d%H)"

# ---- 2. 令牌 -----------------------------------------------------------------
TOKEN=""
if [ -f "$PB" ]; then
    TOKEN="$(strings "$PB" 2>/dev/null | grep -oE 'gho_[A-Za-z0-9_]{20,}' | head -1)"
fi
if [ -z "$TOKEN" ]; then
    echo "!! 取不到 GitHub 令牌（$PB）"; exit 1
fi
echo "== 令牌已取到（长度 ${#TOKEN}，前缀 ${TOKEN:0:4}）"

if [ "$DRY_RUN" = "1" ]; then
    echo "== [dry-run] 将执行："
    echo "   git add -A && git commit -m 'release: $VERSION'"
    echo "   git tag -f release-$VERSION"
    echo "   git push $REMOTE_URL HEAD:refs/heads/$BRANCH"
    echo "   git push $REMOTE_URL refs/tags/release-$VERSION"
    echo "   git bundle create $BUNDLE_DIR/... HEAD --all"
    exit 0
fi

# ---- 3. 提交 -----------------------------------------------------------------
if [ -n "$(git status --porcelain)" ]; then
    git add -A || exit 1
    git -c user.name='AHa-6' -c user.email='aha6@local' \
        commit -m "release: $VERSION（Compose 改版）" || exit 1
fi

COMMIT="$(git rev-parse HEAD)"
echo "== HEAD: $COMMIT"

# ---- 4. 打 tag ---------------------------------------------------------------
TAG="release-$VERSION"
git tag -f "$TAG" "$COMMIT" || exit 1
echo "== tag: $TAG -> $COMMIT"

# ---- 5. 推送分支与 tag -------------------------------------------------------
echo "== push $BRANCH"
git -c credential.helper= push "$REMOTE_URL" "HEAD:refs/heads/$BRANCH" 2>&1 | tail -3
RC=${PIPESTATUS[0]}
if [ "$RC" != "0" ]; then
    echo "!! 分支推送失败 RC=$RC"; exit 2
fi

echo "== push tag $TAG"
git -c credential.helper= push "$REMOTE_URL" "refs/tags/$TAG" 2>&1 | tail -3
RC=${PIPESTATUS[0]}
if [ "$RC" != "0" ]; then
    echo "!! tag 推送失败 RC=$RC"; exit 2
fi

# ---- 6. 重建离线 bundle ------------------------------------------------------
if [ -d "$BUNDLE_DIR" ]; then
    SHORT="$(git rev-parse --short=7 HEAD)"
    OUT="$BUNDLE_DIR/legado_src_${SHORT}.bundle"
    echo "== bundle: $OUT"
    git bundle create "$OUT" --all 2>&1 | tail -2
    ( cd "$BUNDLE_DIR" && md5sum "$(basename "$OUT")" > "$(basename "$OUT").md5" )
    echo "== bundle md5: $(cut -d' ' -f1 "$OUT.md5")"
fi

echo "== 完成：$BRANCH @ $COMMIT, tag $TAG"