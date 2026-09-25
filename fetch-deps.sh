#!/usr/bin/env bash
#
# 下载构建依赖：Apache Commons Compress + XZ for Java（7z 解码用，纯 Java）
# 仓库里已经带了这两个 jar，只有它们丢失时才需要跑这个脚本。
#
set -e
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST="$ROOT/third_party"
BASE="https://repo1.maven.org/maven2"
mkdir -p "$DEST"
cd "$DEST"

fetch() {
  url="$1"; out="$2"
  if [ -f "$out" ]; then echo "已存在：$out"; return; fi
  echo "下载 $out …"
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 -o "$out" "$url"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$out" "$url"
  else
    echo "需要 curl 或 wget"; exit 1
  fi
}

fetch "$BASE/org/apache/commons/commons-compress/1.21/commons-compress-1.21.jar" commons-compress-1.21.jar
fetch "$BASE/org/tukaani/xz/1.9/xz-1.9.jar" xz-1.9.jar

# 许可文件：commons-compress 的 LICENSE/NOTICE 在 jar 里
if [ ! -f LICENSE-commons-compress.txt ] && command -v unzip >/dev/null 2>&1; then
  unzip -p commons-compress-1.21.jar META-INF/LICENSE.txt > LICENSE-commons-compress.txt || true
  unzip -p commons-compress-1.21.jar META-INF/NOTICE.txt  > NOTICE-commons-compress.txt  || true
fi
echo "完成。"
