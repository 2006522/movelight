#!/usr/bin/env bash
# MoveLight 构建脚本（无需 Gradle / Loom）
# 原理：26.x 的 Minecraft jar 已不再混淆，而且该快照既没有 Yarn 也没有 Mojang 映射，
#      所以直接对着官方 client.jar 编译，产物命名空间就是 official，不需要 remap。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
DEPS="${DEPS:-$ROOT/deps}"

# Git Bash 的 /d/... 形式 javac 不认，统一转成 D:/... 形式
tomix() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}
ROOTW="$(tomix "$ROOT")"
DEPSW="$(tomix "$DEPS")"

# JDK：优先 $JDK，其次 deps/jdk，再次 Minecraft 自带运行时，最后 PATH 里的 javac
if [ -n "${JDK:-}" ] && [ -x "$JDK/bin/javac" ]; then
  JAVAC="$JDK/bin/javac"; JAR="$JDK/bin/jar"
elif [ -x "$DEPS/jdk/bin/javac" ]; then
  JAVAC="$DEPS/jdk/bin/javac"; JAR="$DEPS/jdk/bin/jar"
elif [ -x "$HOME/AppData/Roaming/.minecraft/runtime/java-runtime-epsilon/bin/javac.exe" ]; then
  RUNTIME="$(tomix "$HOME/AppData/Roaming/.minecraft/runtime/java-runtime-epsilon/bin")"
  JAVAC="$RUNTIME/javac.exe"; JAR="$RUNTIME/jar.exe"
else
  JAVAC="javac"; JAR="jar"
fi

if [ ! -f "$DEPS/client.jar" ]; then
  echo "缺少依赖：$DEPS/client.jar" >&2
  echo "先运行 ./fetch-deps.sh 下载依赖（或设置 DEPS 指向已有依赖目录）" >&2
  exit 1
fi

CP="$DEPSW/client.jar;$DEPSW/loader.jar;$DEPSW/brigadier.jar;$DEPSW/mixin.jar;$DEPSW/dfu.jar"
# client.jar 里不少签名带 org.jspecify.annotations.Nullable（LivingEntity#getMainHandItem 之类），
# javac 读签名时要求这个注解类真实存在，否则报"找不到类文件"，所以单独补一个注解 jar。
if [ -f "$DEPS/jspecify.jar" ]; then
  CP="$CP;$DEPSW/jspecify.jar"
fi
# client.jar 里的 LivingEntity 还引用了 fastutil 的 Object2LongMap，同样要补
if [ -f "$DEPS/fastutil.jar" ]; then
  CP="$CP;$DEPSW/fastutil.jar"
fi
for jar in "$DEPS"/fapi/*.jar; do
  CP="$CP;$(tomix "$jar")"
done

echo "== 清理 =="
rm -rf "$ROOT/build/classes"
mkdir -p "$ROOT/build/classes" "$ROOT/dist"

echo "== 编译 =="
find "$ROOTW/src/main/java" -name "*.java" > "$ROOT/build/sources.txt"
"$JAVAC" -encoding UTF-8 --release 25 -nowarn -d "$ROOTW/build/classes" -cp "$CP" @"$ROOTW/build/sources.txt"

echo "== 打包 =="
VER=$(grep -o '"version": "[^"]*"' "$ROOT/src/main/resources/fabric.mod.json" | head -1 | sed 's/.*: "//;s/"//')
OUT="$ROOT/dist/movelight-${VER}+mc26.4-snapshot-5.jar"
rm -f "$OUT"
"$JAR" --create --file "$(tomix "$OUT")" -C "$ROOTW/build/classes" . -C "$ROOTW/src/main/resources" .
echo "完成: $OUT"
