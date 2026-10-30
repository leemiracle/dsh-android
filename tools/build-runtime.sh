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
LAYOUT="12"
WORK="${1:-/tmp/dsh-build}"
UPSTREAM="/usr-data/data2/repos/deepseek-ai/deepseek-harness"
NDK_CLANG="${NDK_CLANG:-/home/lwz/Android/Sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/linux-x86_64/bin/clang}"

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

echo "== [4b/6] patch node-addon-system flock loader for android"
# Termux Node reports process.platform==='android'; upstream's gate throws
# ERR_FLOCK_UNSUPPORTED_PLATFORM before the session lease can ever lock. The
# branch below loads the NDK-built binding (next step) from nativeLibraryDir —
# app data paths are no-exec, so the usual per-platform-package `.node`
# require() route cannot work on device.
python3 - "$WORK/web" <<'PY'
import os, sys
needle_gate = "if (platform !== 'linux' && platform !== 'darwin') {"
gate_new = "if (platform !== 'linux' && platform !== 'darwin' && platform !== 'android') {"
needle_req = "const require = createRequire(import.meta.url);"
branch = needle_req + """
    if (platform === 'android') {
        // DSH-Android overlay: upstream src/flock.c compiled with the NDK as
        // libflocknode.so (jniLibs). Loaded via process.dlopen because the
        // '.so' suffix never enters the addon require path.
        const so = process.env.DSH_NATIVE_DIR
        if (!so) throw Object.assign(new Error('flock: DSH_NATIVE_DIR is not set'), { code: 'ERR_FLOCK_NO_NATIVE_DIR' })
        const Module = require('node:module').Module
        const m = new Module('dsh-android-flock', null)
        process.dlopen(m, so + '/libflocknode.so')
        binding = m.exports
        return binding
    }"""
hits = 0
for root, dirs, files in os.walk(os.path.join(sys.argv[1], "node_modules")):
    if root.endswith(os.sep.join(["node-addon-system", "lib"])) and "flock.js" in files:
        p = os.path.join(root, "flock.js")
        s = open(p).read()
        if "DSH-Android overlay" in s:
            print("already patched", p)
            hits += 1
            continue
        assert needle_gate in s and needle_req in s, f"anchors missing in {p}"
        open(p, "w").write(s.replace(needle_gate, gate_new).replace(needle_req, branch))
        print("patched", p)
        hits += 1
assert hits, "node-addon-system/lib/flock.js not found under node_modules"
PY

echo "== [4c/6] patch session persistence: SELinux bans hardlinks on app data"
# Two publish sites stage a temp file then link(2) it into place (atomic
# create-no-clobber). Android SELinux denies link(2) on app_data_file with
# EACCES, so every session write died right after the flock lease was taken
# (symptom: session dirs with only session.lock, zero jsonl). On android both
# sites publish via rename() instead — pre-checks plus the session write lease
# preserve the no-clobber guarantee the hardlink provided.
python3 - "$WORK/web" <<'PY'
import os, sys
p = os.path.join(sys.argv[1], "node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js")
s = open(p).read()
if "DSH-Android overlay" not in s:
    imp_old = 'import { link, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from "node:fs/promises";'
    imp_new = 'import { link, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, rename, rm, stat, truncate } from "node:fs/promises";'
    assert imp_old in s, "persistence import anchor missing"
    s = s.replace(imp_old, imp_new)

    a1 = "\ttry {\n\t\tawait internals.fs.link(staged, currentPath);"
    b1 = '''\tif (internals.platform === "android") {
\t\t/* DSH-Android overlay: SELinux forbids hardlinking app_data_file (EACCES),
\t\t * so emulate link()'s create-exclusive commit: claim the target with an
\t\t * exclusive-create placeholder, then rename the staged file over it. The
\t\t * session write lease keeps this directory single-writer, which the link
\t\t * version relied on as well. */
\t\tlet placeholder;
\t\ttry {
\t\t\tplaceholder = await internals.fs.open(currentPath, "wx", 0o600);
\t\t} catch (error) {
\t\t\tif (isEEXIST(error)) return false;
\t\t\tthrow error;
\t\t}
\t\tawait placeholder.close();
\t\ttry {
\t\t\tawait rename(staged, currentPath);
\t\t} catch (error) {
\t\t\tawait removeTemporary(currentPath, error, internals);
\t\t\tthrow error;
\t\t}
\t\tawait syncDirectory(dirname(currentPath), internals);
\t\treturn true;
\t}
''' + a1
    assert a1 in s, "publishCurrentExclusive anchor missing"
    s = s.replace(a1, b1, 1)

    a2 = """\t\tlet linked = false;
\t\ttry {
\t\t\tawait link(tmp, finalPath);
\t\t\tlinked = true;
\t\t} finally {"""
    b2 = """\t\tlet linked = false;
\t\tif (process.platform === "android") {
\t\t\t/* DSH-Android overlay: SELinux forbids hardlinking app_data_file
\t\t\t * (EACCES on link(2)). Publish via rename instead — rejectExistingLog
\t\t\t * above plus the session write lease preserve the no-clobber guarantee. */
\t\t\tawait rename(tmp, finalPath);
\t\t\tlinked = true;
\t\t} else try {
\t\t\tawait link(tmp, finalPath);
\t\t\tlinked = true;
\t\t} finally {"""
    assert a2 in s, "materializePosix anchor missing"
    s = s.replace(a2, b2, 1)
    open(p, "w").write(s)
    print("patched", p)
else:
    print("already patched", p)
PY

echo "== [4d/6] patch attachment-local: same hardlink ban, copy/rename publish"
# dsh-attachment-local's content-addressed store dedups via link(2): immutable
# aliases link an existing object under a second name, staged objects link a
# temp into place. SELinux denies link() on app_data_file (EACCES), so every
# attachment write dies. On android: aliases copy the bytes (the source object
# must survive), staged temps rename into place; the EEXIST digest check
# becomes a pre-check, and the post-publish unlink tolerates the moved file.
python3 - "$WORK/web" <<'PY'
import os, sys
p = os.path.join(sys.argv[1], "node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js")
s = open(p).read()
if "DSH-Android overlay" not in s:
    imp_old = 'import { chmod, link, mkdir, open, readFile, rename, rm, unlink, writeFile } from "node:fs/promises";'
    imp_new = 'import { chmod, copyFile, link, mkdir, open, readFile, rename, rm, stat, unlink, writeFile } from "node:fs/promises";'
    assert imp_old in s, "attachment import anchor missing"
    s = s.replace(imp_old, imp_new, 1)

    a1 = "\t\ttry {\n\t\t\tawait link(source, target);\n\t\t} catch (error) {"
    b1 = """\t\tif (process.platform === "android") {
\t\t\t/* DSH-Android overlay: SELinux denies hardlinking app_data_file (EACCES).
\t\t\t * Copy the immutable object's bytes instead — same digest, new inode —
\t\t\t * preserving the source object the alias semantics require. */
\t\t\tlet existing = true;
\t\t\ttry {
\t\t\t\tawait stat(target);
\t\t\t} catch (error) {
\t\t\t\tif (error?.code !== "ENOENT") throw error;
\t\t\t\texisting = false;
\t\t\t}
\t\t\tif (existing) {
\t\t\t\tif (await digestFile(target) !== sha256) throw new AttachmentError("Stored attachment failed integrity verification.", "ATTACHMENT_CORRUPT");
\t\t\t} else {
\t\t\t\tawait copyFile(source, target);
\t\t\t}
\t\t} else try {
\t\t\tawait link(source, target);
\t\t} catch (error) {"""
    assert a1 in s, "attachment alias anchor missing"
    s = s.replace(a1, b1, 1)

    a2 = "\t\ttry {\n\t\t\tawait link(staged.path, target);\n\t\t} catch (error) {"
    b2 = """\t\tif (process.platform === "android") {
\t\t\t/* DSH-Android overlay: SELinux denies hardlinking app_data_file (EACCES).
\t\t\t * The staged file is a disposable temp: rename it into place, keeping
\t\t\t * the digest check for a pre-existing target. */
\t\t\tlet existing = true;
\t\t\ttry {
\t\t\t\tawait stat(target);
\t\t\t} catch (error) {
\t\t\t\tif (error?.code !== "ENOENT") throw error;
\t\t\t\texisting = false;
\t\t\t}
\t\t\tif (existing) {
\t\t\t\tif (await digestFile(target) !== staged.sha256) throw new AttachmentError("Stored attachment failed integrity verification.", "ATTACHMENT_CORRUPT");
\t\t\t} else {
\t\t\t\tawait rename(staged.path, target);
\t\t\t}
\t\t} else try {
\t\t\tawait link(staged.path, target);
\t\t} catch (error) {"""
    assert a2 in s, "attachment staged anchor missing"
    s = s.replace(a2, b2, 1)

    a3 = "\t\tawait unlink(staged.path);"
    assert s.count(a3) == 1, "attachment unlink anchor not unique"
    b3 = """\t\t/* DSH-Android: the rename branch above already moved this file away. */
\t\tawait unlink(staged.path).catch((error) => {
\t\t\tif (error?.code !== "ENOENT") throw error;
\t\t});"""
    s = s.replace(a3, b3, 1)
    open(p, "w").write(s)
    print("patched", p)
else:
    print("already patched", p)
PY

echo "== [4e/6] patch node-pty loader: android binding from nativeLibraryDir"
# The linux-arm64 prebuild is glibc-linked (libutil.so.1 bionic has not) and a
# .node inside app data is no-exec anyway. The NDK build (next step) ships as
# libptyexec.so in jniLibs; load it via process.dlopen, '.so' never enters
# the addon require path (same pattern as the flock binding).
python3 - "$WORK/web" <<'PY'
import os, sys
p = os.path.join(sys.argv[1], "node_modules/node-pty/lib/utils.js")
s = open(p).read()
if "DSH-Android overlay" not in s:
    a = "function loadNativeModule(name) {\n    // Check build, debug, and then prebuilds."
    b = """function loadNativeModule(name) {
    if (process.platform === 'android') {
        /* DSH-Android overlay: the glibc prebuild needs libutil.so.1 bionic
         * has not, and .node files under app data are no-exec. The NDK build
         * ships as lib<name>exec.so in nativeLibraryDir (DSH_NATIVE_DIR). */
        var so = process.env.DSH_NATIVE_DIR;
        if (!so) throw new Error("DSH-Android: DSH_NATIVE_DIR is not set");
        var Module = require('module').Module;
        var mod = new Module('dsh-android-' + name, null);
        process.dlopen(mod, so + '/lib' + name + 'exec.so');
        return { dir: 'nativeLibraryDir', module: mod.exports };
    }
    // Check build, debug, and then prebuilds."""
    assert a in s, "node-pty loader anchor missing"
    open(p, "w").write(s.replace(a, b, 1))
    print("patched", p)
else:
    print("already patched", p)
PY

echo "== [4f/6] patch subprocess-local: android joins the linux inspector"
# The terminal panel spawns through subprocess-local, whose process inspector
# factory only knows linux/darwin/win32 and throws on android. The Linux
# inspector (procfs reads) is exactly right for Android's same-uid /proc.
python3 - "$WORK/web" <<'PYINSPECTOR'
import os, sys
p = os.path.join(sys.argv[1], "node_modules/@deepseek-ai/dsh-subprocess-local/lib/runner-launch-B2zsQ1Dz.js")
s = open(p).read()
old = '\tif (platform === "linux") return new LinuxProcessInspector(arch, internals);'
new = '\tif (platform === "linux" || platform === "android") return new LinuxProcessInspector(arch, internals);'
if new not in s:
    assert old in s, "inspector anchor missing"
    open(p, "w").write(s.replace(old, new, 1))
    print("patched", p)
else:
    print("already patched", p)
PYINSPECTOR

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

echo "== [5b/6] flock binding: compile upstream flock.c with the NDK"
# Same C source the linux prebuilds use, plain Node-API v8 + flock(2), both
# fine on bionic. SONAME must equal the filename or AGP silently drops it.
[ -x "$NDK_CLANG" ] || { echo "NDK clang not found: $NDK_CLANG" >&2; exit 1; }
mkdir -p "$WORK/napi"
if [ ! -f "$WORK/napi/package/include/node_api.h" ]; then
  (cd "$WORK/napi" && npm pack node-api-headers --silent && tar xzf node-api-headers-*.tgz)
fi
"$NDK_CLANG" --target=aarch64-linux-android26 -shared -fPIC -O2 -DNAPI_VERSION=8 \
  -I"$WORK/napi/package/include" \
  "$UPSTREAM/native/system/packages/entry/src/flock.c" \
  -Wl,-soname,libflocknode.so -o "$OUT_JNI/libflocknode.so"
chmod 755 "$OUT_JNI/libflocknode.so"

echo "== [5c/6] pty binding: compile upstream node-pty pty.cc with the NDK"
# Plain N-API C++ over forkpty/openpty (both in bionic libc since API 23);
# node-addon-api headers ship in the tree, N-API headers via node-api-headers.
NDK_CXX="${NDK_CXX:-$(dirname "$NDK_CLANG")/clang++}"
[ -x "$NDK_CXX" ] || { echo "NDK clang++ not found: $NDK_CXX" >&2; exit 1; }
"$NDK_CXX" --target=aarch64-linux-android26 -shared -fPIC -O2 -std=c++17 \
  -DNAPI_VERSION=8 -DNODE_ADDON_API_DISABLE_DEPRECATED \
  -I"$WORK/web/node_modules/node-addon-api" \
  -I"$WORK/napi/package/include" \
  "$WORK/web/node_modules/node-pty/src/unix/pty.cc" \
  -l:libc++_static.a -l:libc++abi.a \
  -Wl,-soname,libptyexec.so -o "$OUT_JNI/libptyexec.so"
chmod 755 "$OUT_JNI/libptyexec.so"

echo "== [6/6] package web tree + CA bundle"
STAGE2="$STAGE/pack"; mkdir -p "$STAGE2/rt" "$STAGE2/web"
cp "$R/etc/tls/cert.pem" "$STAGE2/rt/cert.pem"
# kylin-tools replacement bundle: a profile bundle package living in the
# runtime module tree (bash=mksh replacing the sandbox-locked dsh-tool-bash,
# kylin-ddg search provider for the stock web_search tool). DshRuntime's
# preseed adds it to the web profile's bundle list. The preset restatement
# patch is generated from `--dump-config` output — regenerate it whenever
# DSH_NPM_VERSION changes.
rm -rf "$WORK/web/node_modules/kylin-tools"
cp -r "$ROOT/tools/kylin-tools" "$WORK/web/node_modules/kylin-tools"
cp -a "$WORK/web/node_modules" "$STAGE2/web/node_modules"
mkdir -p "$OUT_ASSETS"
rm -f "$OUT_ASSETS/dsh-runtime.zip"
( cd "$STAGE2" && zip -q -r -X "$OUT_ASSETS/dsh-runtime.zip" rt web )
echo "dsh-$DSH_NPM_VERSION+node-$PKG_VER-termux+layout$LAYOUT" > "$OUT_ASSETS/dsh-runtime.version"
rm -rf "$STAGE"
echo "DONE: $(du -h "$OUT_ASSETS/dsh-runtime.zip" | cut -f1) zip + $(du -sh "$OUT_JNI" | cut -f1) jniLibs"
