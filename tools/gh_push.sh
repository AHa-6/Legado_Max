#!/usr/bin/env bash
# 推送当前分支到 fork。令牌取自 Operit 的「包管理 → 环境变量」GITHUB_TOKEN，
# 不依赖 Operit 的 GitHub 登录态（那份 DataStore 凭据曾归零，见 2026-11 排查）。
#
# 用法: tools/gh_push.sh [目标分支]   默认 compose-rework
set -u
dst="${1:-compose-rework}"
PREFS="/data/user/0/com.ai.assistance.operit/shared_prefs/env_preferences.xml"
if [ ! -f "$PREFS" ]; then echo "!! 找不到环境变量文件: $PREFS"; exit 1; fi
TOKEN="$(sed -n 's|.*name="GITHUB_TOKEN">\([^<]*\)<.*|\1|p' "$PREFS")"
if [ -z "$TOKEN" ]; then echo "!! 环境变量里没有 GITHUB_TOKEN"; exit 1; fi
echo "== 令牌已取到（长度 ${#TOKEN}，前缀 ${TOKEN:0:4}）"
cd "$(dirname "$0")/.." || exit 1
echo "== 推送 $(git rev-parse --abbrev-ref HEAD) -> $dst"
git push "https://x-access-token:${TOKEN}@github.com/AHa-6/Legado_Max.git" "HEAD:${dst}" 2>&1 \
    | sed -e "s|${TOKEN}|***|g"
rc=${PIPESTATUS[0]}
[ "$rc" -eq 0 ] && echo "== 完成" || echo "!! 推送失败 rc=$rc"
exit "$rc"
