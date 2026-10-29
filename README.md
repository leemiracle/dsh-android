# dsh-android — DeepSeek Harness APK 壳

把 [deepseek-harness](https://github.com/deepseek-ai/deepseek-harness)（`dsh`，DeepSeek 官方
开源 agent harness，Node.js + Web UI）完整装进一个 Android APK：安装即用、离线自包含，
无需 Termux、无需 root。在长城 pd2508（麒麟 PC 形态，arm64 / Android 16）上端到端验证通过。

上游仓库 `/usr-data/data2/repos/deepseek-ai/deepseek-harness`（master @ f3803c9697，
0.2.2-alpha.2）**只作基准参考，零改动**；APK 内嵌的是 npm 发布包
`@deepseek-ai/dsh@0.2.1-rc.2` + 一个 Android 专用 shim 覆盖（见下）。

## 架构

```
┌────────────────────────── APK (144MB) ──────────────────────────┐
│ lib/arm64-v8a/                                                   │
│   libnode_exec.so   Termux nodejs-lts 24.22.1 (aarch64/bionic)   │
│   lib*_*.so ×9      c-ares/openssl/icu/sqlite/z/libc++ （改名+    │
│                      patchelf 重写 SONAME/NEEDED/RUNPATH）        │
│ assets/dsh-runtime.zip  dsh node_modules 305MB 瘦身树             │
│                         + require-builtin shim + CA bundle        │
└──────────────────────────────────────────────────────────────────┘
            安装首启                           运行
 assets → filesDir/dsh/{web,rt}    DshService(foreground) exec
                                   nativeLibraryDir/libnode_exec.so
                                   --expose-internals dsh…bin.js web
                                   --port 0 → stdout 吐 token URL
                                   → WebView 加载 http://127.0.0.1:PORT/?token=…
```

代码全在 `app/src/main/java/com/dsh/shell/`（纯 framework API，零第三方依赖）：
`MainActivity`（状态页+WebView）、`DshService`（前台服务：装包/起进程/读 URL/崩溃重启×3/
扫 /proc 清孤儿）、`DshRuntime`（zip 首装 + 路径常量）。

## 关键技术决策（都有实证）

| 决策 | 原因 |
|---|---|
| Termux node 24 而非 nodejs-mobile | nodejs-mobile 止步 Node 18.20.4；dsh 实测 18/20 均崩（`util.parseEnv`、静默死），22+ 才活 |
| node 进 jniLibs + `useLegacyPackaging` | targetSdk≥29 只允许 exec nativeLibraryDir；filesDir W^X 禁 exec/mmap-exec |
| 9 个 .so 改名 + patchelf | AGP 静默丢弃 SONAME≠文件名或无 SONAME 的 jniLib（libsqlite3 无 SONAME 也被丢）；`libz.so.1` 这类名字直接进不了包。改名后 SONAME/NEEDED/RUNPATH(`$ORIGIN`) 全链重写 |
| `--expose-internals` + shim | cordis 启动链硬依赖 `node-addon-require-builtin` 的 glibc 预编译 .node；bionic 加载不了。shim 用 expose-internals 下的普通 require 顶替 internal 模块加载（cordis-plugin-loader 源码本身就带这条回退路径） |
| NDK 编译 flock 绑定（layout 2 新增） | 会话持久化 lease 硬依赖 `@deepseek-ai/node-addon-system/flock`，Termux Node 上 `process.platform==='android'` 被 gate 直接拒（上游只发 linux/darwin prebuilds）。build-runtime.sh 用 NDK r29 把上游 `src/flock.c`（纯 Node-API v8）编成 `libflocknode.so` 进 jniLibs，并 patch loader 走 `process.dlopen(DSH_NATIVE_DIR/libflocknode.so)`——`.node` 放 assets 会被 W^X 拦，`require()` 又不认 `.so` 后缀 |
| session 落盘 link→rename（layout 3 新增） | `dsh-session-persistence-jsonl` 两处发布点用 `link(2)` 做 create-no-clobber 原子提交，Android SELinux 对 `app_data_file` 禁 hardlink（EACCES）→ 会话目录只剩 session.lock、零 jsonl。patch 后 android 走 `rename()`（存在性前置检查+写 lease 保住 no-clobber 语义）。真机实证：`session.v4.jsonl.zstd` 完整落盘（zstd 压缩、含会话头/权限配置/消息流） |
| boot 单飞闸（AtomicBoolean） | MainActivity onCreate+onStart 冷启时各发一次 startService，两个 `dsh-boot` 线程并发跑 ensureInstalled 的 delete-and-extract，FileTreeWalk 断言直接炸进程（只在 zip 版本变化需重装时触发——marker 命中时双线程都早退所以平时无恙）。onStart 保活（系统停服务后拉活）与单飞闸配合：用户主动停止不打架 |
| 瘦身 868MB→305MB（zip 后 45MB） | 删 map/md/ts/test 目录、跨平台 prebuilds、libreoffice-wasm(146M)、inspector(26M)。**坑：`doc/` 不能删**——yaml 包的 `dist/doc/` 是运行时代码 |

## 构建

```sh
# 1) 重建运行时输入（termux deb + npm + prune + shim + patchelf，可复现）
tools/build-runtime.sh

# 2) 出 APK（本机 gradle 8.11.1 + AGP 8.7.3 + JDK21 均已缓存）
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

升级 dsh：改 `tools/build-runtime.sh` 里 `DSH_NPM_VERSION` 重跑即可（上游仓库不受影响）。

## 使用

首启约 30-60 秒（解 zip）；状态页变标题后 WebView 自动进入。通知栏常驻「停止」
按钮；活动内返回键 = 后台保活。API key 在 Web UI 设置→模型 里填 DeepSeek 官方
key（UI 自己持久化），或让用户侧 push `files/dsh/home/.dsh-env`。

## 内置模型：kylin 本地 vLLM（与 kylin-agent 同源）

首启时自动预置 kylin-agent `LocalHarness` 同款模型（vLLM `qwen3.5-35b`）到
`$DSH_HOME/profiles/web/cordis.patch.yml`，作为默认模型：

- provider `kylin-vllm` → `http://127.0.0.1:11433/v1`（`openai-completions`，无鉴权，
  `KYLIN_VLLM_API_KEY` 由 Service 注入哑值过凭据 seam）
- 预置逻辑幂等合并：文件已存在（UI 已初始化过）则追加两个 entry，用户在 UI 里
  改过的不动；一旦出现 `kylin-vllm` 字样或 `# dsh-android-preseed-off` 标记即永不再写

设备上访问该 vLLM 需先建隧道（开发机执行，与 kylin-agent 完全同一命令）：

```sh
bash /usr-data/data2/kylin/kylin-agent/bin/kylin100.sh tunnel
# = 本机 ka_fwd.py 转发 127.0.0.1:11433 → 10.44.11.11:11433 + adb reverse tcp:11433
```

不想要预置：在 `cordis.patch.yml` 顶部加一行 `# dsh-android-preseed-off`（或删掉那
两个 entry）即可，重启后不会再被写回。

## 已知边界

- bash/终端类工具在 Android 上降级（有 /system/bin/sh，无 bash/git）——服务器与 UI 不受影响
- Android 15+ 对 dataSync 前台服务有 6h 上限，超时系统会停服务（重启 app 即恢复；onStart 保活已覆盖进程内拉活）
- **麒麟 ROM 装机后约 48s 可能一次性强停 FGS**（观察到 `Stop FGS timeout` + CompatChangeReporter 同刻触发；非装机场景 130s+/1.5h+ 稳定存活）。停后下次打开 app 由 onStart 拉活
- **附件上传不可用**：`dsh-attachment-local` 的内容寻址去重靠 `link(2)` 保留源对象加别名，rename 会搬走源——SELinux hardlink 禁令下需改 copy 语义，暂列为已知边界（会话/消息/文件树不受影响）
- `dsh-experimental-inspector`、libreoffice 文档预览（wasm 146MB）已裁，功能缺失但不影响主链
- 只带 arm64-v8a；x86_64 需换 Termux x86_64 deb 同法打包
- 版本: 壳 0.1.0 · dsh 0.2.1-rc.2 · node 24.22.1 · runtime layout 3（2026-10-29 真机验证：消息收发+会话 zstd 落盘全绿）
