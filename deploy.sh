#!/usr/bin/env bash
# 把 dist/ 里最新的 jar 部署到游戏实例的 mods 目录。
# 部署前会把同 mod id 的旧 jar 移到实例根目录的 _mods_backup/，
# 避免出现两个同 id 的 jar（Fabric Loader 会因重复 id 直接报错）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
MODS="${MODS:-/e/Axolotl.Launcher/profiles/26.4-snapshot-5-Fabric 0.19.5/mods}"
BACKUP="${MODS%/mods}/_mods_backup"

if [ ! -d "$MODS" ]; then
  echo "找不到 mods 目录：$MODS" >&2
  echo "可以用 MODS=/你的/mods 指过去" >&2
  exit 1
fi

VER=$(grep -o '"version": "[^"]*"' "$ROOT/src/main/resources/fabric.mod.json" | head -1 | sed 's/.*: "//;s/"//')
SRC="$ROOT/dist/movelight-${VER}+mc26.4-snapshot-5.jar"
if [ ! -f "$SRC" ]; then
  echo "找不到构建产物：$SRC（先跑 ./build.sh）" >&2
  exit 1
fi

mkdir -p "$BACKUP"
# 文件名必须带版本号，否则用户看不出装的哪版
DEST="$MODS/MoveLight-${VER}.jar"

for old in "$MODS"/MoveLight-*.jar; do
  [ -e "$old" ] || continue
  if [ "$old" != "$DEST" ]; then
    echo "移走旧的：$(basename "$old")"
    mv "$old" "$BACKUP/"
  fi
done

cp "$SRC" "$DEST"
echo "已部署：$DEST"
ls -la "$MODS"