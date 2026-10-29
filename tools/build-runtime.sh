#!/usr/bin/env bash
# Reproducibly rebuild the runtime inputs consumed by app/.
#
#   tools/build-runtime.sh [workdir]
#
# Produces:
#   app/src/main/assets/dsh-runtime.zip     (rt libs + CA bundle + dsh tree + shim)
#   app/src/main/assets/dsh-runtime.version
#   app/src/main/jniLibs/arm64-v8a/libnode_exec.so
#
# The upstream harness repo (/usr-data/data2/repos/deepseek-ai/deepseek-harness)
# is never modified; the npm-published @deepseek-ai/dsh distribution is what we
# embed, plus one JS shim overlay for Android/bionic (see below).
set -euo pipefail

DSH_NPM_VERSION="0.2.1-rc.2"       # @deepseek-ai/dsh (npm dist)
NODE_PKG="nodejs-lts_24.22.1"      # termux nodejs-lts (aarch64)
LAYOUT="1"
WORK="${1:-/tmp/dsh-build}"

TERMUX_APT="https://packages.termux.dev/apt/termux-main"
LIBS=(libcares.so libcrypto.so.3 libssl.so.3 libc++_shared.so \
      libicui18n.so.78 libicuuc.so.78 libicudata.so.78 \
      libz.so.1 libsqlite3.so)

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_ASSETS="$ROOT/app/src/main/assets"
OUT_JNI="$ROOT/app/src/main/jniLibs/arm64-v8a"

echo "== [1/5] termux runtime: $NODE_PKG (deps closure)"
mkdir -p "$WORK/debs" "$WORK/root"
PKG_BASE="${NODE_PKG%%_*}"          # nodejs-lts
PKG_VER="${NODE_PKG##*_}"
for p in "$PKG_BASE" libc++ openssl c-ares libicu libsqlite zlib ca-certificates resolv-conf; do
  :
done
# Pull the aarch64 Packages index once and resolve closure + paths in python.
curl -sf --retry 5 -o "$WORK/Packages.gz" \
  "$TERMUX_APT/dists/stable/main/binary-aarch64/Packages.gz"
zcat "$WORK/Packages.gz" > "$WORK/Packages"
python3 - "$WORK" "$PKG_BASE" <<'PY'
import re, sys
work, base = sys.argv[1], sys.argv[2]
stanzas = open(f"{work}/Packages").read().split("\n\n")
def get(s, k):
    m = re.search(rf"^{k}: (.*)$", s, re.M)
    return m.group(1) if m else None
idx = {get(s, "Package"): s for s in stanzas if get(s, "Package")}
seen, queue, urls = set(), [base], []
while queue:
    p = queue.pop(0)
    if p in seen or p not in idx:
        continue
    seen.add(p)
    urls.append(get(idx[p], "Filename"))
    for d in (get(idx[p], "Depends") or "").split(", "):
        n = d.split(" ")[0].split("(")[0].strip()
        if n and n not in seen:
            queue.append(n)
open(f"{work}/urls.txt", "w").write("\n".join(f"URLSPLIT{u}" for u in urls))
PY
while IFS= read -r u; do
  f="$WORK/debs/$(basename "${u#URLSPLIT}")"
  [ -s "$f" ] || curl -sf --retry 5 -o "$f" "$TERMUX_APT/${u#URLSPLIT}"
done < "$WORK/urls.txt"

for d in "$WORK"/debs/*.deb; do
  t=$(mktemp -d)
  (cd "$t" && ar x "$d" && [ -f data.tar.xz ] && tar xf data.tar.xz)
  if [ -d "$t/data/data/com.termux/files/usr" ]; then
    cp -rn "$t/data/data/com.termux/files/usr/"* "$WORK/root/" 2>/dev/null || true
    if [ -d "$t/data/data/com.termux/files/etc" ]; then
      mkdir -p "$WORK/root/etc"
      cp -rn "$t/data/data/com.termux/files/etc/"* "$WORK/root/etc/" 2>/dev/null || true
    fi
  fi
  rm -rf "$t"
done

echo "== [2/5] npm: @deepseek-ai/dsh@$DSH_NPM_VERSION"
if [ ! -d "$WORK/web/node_modules/@deepseek-ai/dsh" ]; then
  mkdir -p "$WORK/web"
  (cd "$WORK/web" && npm i --no-save "@deepseek-ai/dsh@$DSH_NPM_VERSION")
fi

echo "== [3/5] prune (keep: doc/, *.js runtime requires)"
python3 - "$WORK/web" <<'PY'
import os, shutil, sys
nm = os.path.join(sys.argv[1], "node_modules")
TESTDIRS = {"test","tests","__tests__","testing","coverage",".github",".vscode",
            "examples","example","benchmarks","benchmark","unittest","fixtures"}
for entry in os.listdir(nm):
    if entry.startswith("sherpa-onnx-") or entry in ("fsevents", "@img"):
        shutil.rmtree(os.path.join(nm, entry), ignore_errors=True)
for d in ("node_modules/@deepseek-ai/libreoffice-kit-wasm",
          "node_modules/@deepseek-ai/dsh-experimental-inspector",
          "node_modules/node-addon-system-linux-arm64"):
    shutil.rmtree(os.path.join(sys.argv[1], d), ignore_errors=True)
for root, dirs, files in os.walk(nm, topdown=True):
    dirs[:] = [d for d in dirs if d not in (".bin",) and d not in TESTDIRS]
    for f in files:
        p = os.path.join(root, f)
        if f.endswith((".map", ".md", ".markdown", ".ts", ".tsx", ".flowconfig",
                       ".npmignore", ".editorconfig", ".mo", ".po")):
            os.remove(p)
PY
# NOTE: *.node prebuilds are kept for non-Android platforms only out of the zip:
# on-device the shim below replaces the loader entry, so they never load.

echo "== [4/5] shim overlay: node-addon-require-builtin (bionic)"
cat > "$WORK/web/node_modules/node-addon-require-builtin/lib/index.js" <<'EOF'
"use strict";
// DSH-Android shim (APK shell overlay, not upstream code):
// bionic/Android has no glibc/musl prebuild for require_builtin.node. The
// shell launches Node with --expose-internals so internal module ids resolve
// through ordinary CJS require. API shape mirrors node-addon-require-builtin@0.1.9.
const { createRequire } = require("node:module");
const req = createRequire(__filename);
function requireBuiltin(moduleId) { return req(moduleId); }
function isAllowedInternalId(moduleId) {
  try { req(moduleId); return true; } catch { return false; }
}
function getBindingInfo() {
  return { platform: process.platform, arch: process.arch, backend: "shim", shim: "expose-internals" };
}
exports.requireBuiltin = requireBuiltin;
exports.isAllowedInternalId = isAllowedInternalId;
exports.getBindingInfo = getBindingInfo;
exports.default = { requireBuiltin, isAllowedInternalId, getBindingInfo };
EOF

echo "== [5/6] patchelf: rename versioned sonames for AGP packaging"
# AGP drops jniLibs files whose SONAME is missing or != filename, and rejects
# names like libz.so.1 outright. Rename each versioned lib to lib*.so, then
# rewrite SONAME/DT_NEEDED/RUNPATH so the graph stays self-consistent inside
# nativeLibraryDir. patchelf: pip install patchelf.
STAGE=$(mktemp -d)
cd "$STAGE"
R="$WORK/root"
declare -A MAP=(
  [libz_1.so]=libz.so.1
  [libcrypto_3.so]=libcrypto.so.3
  [libssl_3.so]=libssl.so.3
  [libicui18n_78.so]=libicui18n.so.78
  [libicuuc_78.so]=libicuuc.so.78
  [libicudata_78.so]=libicudata.so.78
)
for new in "${!MAP[@]}"; do cp "$R/lib/${MAP[$new]}" "$new"; done
cp "$R/lib/libsqlite3.so" libsqlite3.so
cp "$R/lib/libcares.so" libcares.so
cp "$R/lib/libc++_shared.so" libc++_shared.so
cp "$R/bin/node" libnode_exec.so
for f in *.so; do patchelf --set-rpath '$ORIGIN' "$f"; done
for new in "${!MAP[@]}"; do patchelf --set-soname "$new" "$new"; done
patchelf --set-soname libsqlite3.so libsqlite3.so   # ships without a SONAME
patchelf --replace-needed libcrypto.so.3 libcrypto_3.so libssl_3.so
patchelf --replace-needed libicuuc.so.78 libicuuc_78.so libicui18n_78.so
patchelf --replace-needed libicudata.so.78 libicudata_78.so libicuuc_78.so
patchelf --replace-needed libz.so.1 libz_1.so libsqlite3.so libnode_exec.so
patchelf --replace-needed libcrypto.so.3 libcrypto_3.so libnode_exec.so
patchelf --replace-needed libssl.so.3 libssl_3.so libnode_exec.so
patchelf --replace-needed libicui18n.so.78 libicui18n_78.so libnode_exec.so
patchelf --replace-needed libicuuc.so.78 libicuuc_78.so libnode_exec.so
rm -f "$OUT_JNI"/*.so
cp "$STAGE"/*.so "$OUT_JNI/"
chmod 755 "$OUT_JNI"/*.so

echo "== [6/6] package web tree + CA bundle"
mkdir -p "$STAGE2"
STAGE2="$STAGE/pack"; mkdir -p "$STAGE2/rt" "$STAGE2/web"
cp "$R/etc/tls/cert.pem" "$STAGE2/rt/cert.pem"
cp -a "$WORK/web/node_modules" "$STAGE2/web/node_modules"
mkdir -p "$OUT_ASSETS"
rm -f "$OUT_ASSETS/dsh-runtime.zip"
( cd "$STAGE2" && zip -q -r -X "$OUT_ASSETS/dsh-runtime.zip" rt web )
echo "dsh-$DSH_NPM_VERSION+node-$PKG_VER-termux+layout$LAYOUT" > "$OUT_ASSETS/dsh-runtime.version"
rm -rf "$STAGE"
echo "DONE: $(du -h "$OUT_ASSETS/dsh-runtime.zip" | cut -f1) zip + $(du -sh "$OUT_JNI" | cut -f1) jniLibs"
