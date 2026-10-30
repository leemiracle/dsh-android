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

## 内置工具替换：kylin-tools bundle（layout 5 新增）

dsh 基础工具有两个在 Android 上天然不可用，用 kylin-agent 同款语义替换
（`tools/kylin-tools/`，作为 web profile 的第三个 bundle 自动注册）：

| 原工具 | Android 上的死因 | 替换实现 |
|---|---|---|
| `bash`（dsh-tool-bash） | 强制 Landlock/bubblewrap 沙箱后端，Android 没有，拒绝裸跑 | kylin-agent `shell` 语义：`/system/bin/sh`(mksh) 直跑，60s 上限，输出截断；四个 agent preset 的 tool-bash 槽位整体重述指向本包 |
| `web_search`（web-search-deepseek） | 硬绑 DeepSeek 搜索 API，无 key 必报错 | 插 `kylin-ddg` search provider 进 dsh-web 的 provider 注册表：**原工具名/UX 不变**，后端换 Bing（DDG 对部分代理出口 IP 反爬，作 fallback）；`DSH_WEB_SEARCH_PROVIDER=kylin-ddg` 选择 |

设备直验（真机 node + 完整代理链）：`getprop ro.product.model` → `GreatWall Computer / 16`；
`search("hefei weather")` → AccuWeather/weather.com 真结果。

### 出网隧道（web_search/web_fetch 需要）

设备无直连外网且本地网关 TLS 拦截（node 不信任其 CA）。出网统一走宿主机 clash：

```sh
adb reverse tcp:11433 tcp:11433   # vLLM（kylin100.sh tunnel 已含）
adb reverse tcp:8333  tcp:7890    # node 出网（DshService 已注入 HTTP(S)_PROXY=127.0.0.1:8333 + NODE_USE_ENV_PROXY）
adb reverse tcp:7890  tcp:7890    # 可选：设备系统代理全局通道（kylin100.sh proxy-on 同款）
```

**WebView 分流代理**（androidx.webkit ProxyController）：设备系统代理
`http_proxy=127.0.0.1:7890` 会把 WebView 连本地 UI 的请求也劫进代理（CONNECT 回环
拨到宿主机端口 → UI 卡死）。壳内 WebView 策略：`127.0.0.1/localhost` 直连（本地
dsh UI），其余（浏览器面板的外网页面）走 `http://127.0.0.1:7890` 反向隧道出网。

## 右侧面板：终端 + 浏览器（layout 8-11 新增）

dsh 自带的多标签右侧栏（ui-dockkit），两项关键适配：

- **终端面板**：`dsh-experimental-terminal-bundle` + node-pty。pty 绑定 Termux 只发
  linux prebuild，Android 上 dlopen 缺 libc++ `__ndk1` 符号 → build-runtime.sh 用
  NDK r29 静态链 libc++ 重编 `libptyexec.so` 进 jniLibs，loader patch 走
  `process.dlopen`；subprocess-local 的进程检查器把 android 当 linux（同 patch）；
  `SHELL=/system/bin/sh`（mksh）由 DshService 注入；bundle patch 将 PTY 后端指向
  `/system/bin/sh -i`。真机实证：mksh 提示符下 `ls`/`adb --help` 正常执行回显。
- **浏览器面板**：`ui-sidebar-browser` 插件上游 gate 到 desktop profile
  （`disabled: profileContext.name !== 'desktop'`），kylin-tools bundle patch 覆盖
  `disabled: false` 启用。web 构建以普通 iframe 渲染页面——Android WebView 完全
  可用，外网流量经上面的分流代理出网。真机实证：面板内依次打开
  example.com / kdocs.cn / baidu.com 全部渲染（tab 标题/地址栏/前进后退跟随；
  导航状态持久化，重开 tab 有「恢复页面」）。

已知怪癖：浏览器 tab 从空白首态直接输首个 URL 偶尔停在「正在打开…」（上游控制器
时序），关掉 tab 重开（或先建 tab 再输入）即正常；frame-busting 站点（百度搜索
结果页等）可能把顶层导航劫走——返回键会自动 reload 壳 UI 兜底。

### 壳内浏览器窗口 + 面板原生引擎（办公站正解）

浏览器面板的 UI（tab 条/地址栏/工具栏）100% 保留 dsh，**内容区换成原生 WebView**
（`BrowserOverlay` 单例引擎精确覆盖面板内容 rect）：

- iframe 被注入脚本置空，URL 由原生引擎渲染——无反嵌/跨域 302 限制，
  cookie/localStorage/缓存 app 级持久（登录一次全 tab 共享，重启仍在）
- **单例引擎 + tab 记忆**（多实例 key 路由在分栏 tab id 上打架后收敛）：JS 侧
  per-tab URL 表，切 tab 时回放该 tab 的页面；后退/前进/刷新/地址栏全部接管
- 后退键优先网页后退（原先直接退后台）；全屏弹窗自动让路；输入法 adjustResize
- 钉版坑：MutationObserver 回调里**无条件 setAttribute 会自触发无限微任务循环**
  （整页饿死、侧栏 0 宽）；WebView 静止时**暂停 rAF**（rect 流要用 setTimeout）；
  `evaluateJavascript` 注 Kotlin raw string 返回 null 要走 `eval(jsonEscape(...))`；
  载体 WebView 的 URL 投递不触发 `shouldOverrideUrlLoading`（`onPageStarted`
  才可靠）；GONE 的 WebView 视口 0×0
- `DshBrowserActivity`：`window.open`/外链的全屏窗口（同 task 返回即回 dsh），
  独立 CDP target（agent 自动化可达，跨域 iframe 永不可达）

真机实证：kdocs 面板内 621×740 对齐渲染；tab A(kdocs)↔tab B(baidu) 切换各自
恢复；后退键网页后退不退 app；localStorage probe 跨窗口/重启存活。

### 已知的一次性步骤

- 全新 DSH_HOME 首启：Web UI 出现「选择工作区」屏——手点 `wps` 一次即进入（以及
  「预览版说明」弹窗点「继续」）。均为 client 端一次性状态。
- preset 重述 patch 由 `--dump-config` 输出生成（对 dsh 0.2.1-rc.2 的四个 preset）；
  **升级 DSH_NPM_VERSION 时需重生成** `tools/kylin-tools/cordis.patch.yml`。

## 内置 Linux 用户态（targetSdk 28 + Termux rootfs）

主 APK 降到 **targetSdk 28**（Termux 的生存之道）：app 数据目录恢复可执行 →
**Termux bootstrap rootfs 直接装进 `files/dsh/linux`**（bash 5.3/coreutils/grep/
sed/awk/apt，398 个二进制），三处消费：

- **终端面板**：PTY 自动指向 rootfs 的 bash（用户层 patch 动态写入——rootfs 在
  则 bash、不在则回落 mksh；服务进程注入 `PATH`/`LD_LIBRARY_PATH` 供动态链接）
- **`linux` 工具**（agent 会话）：kylin-tools 注册，经 127.0.0.1:33990 桥执行
  rootfs 命令（60s 上限）——mksh `bash` 工具没有的程序/GNU 行为走这里
- **bootstrap 安装**（一次性）：宿主 `python3 -m http.server 8390` + `adb reverse
  tcp:8390 tcp:8390` → `POST /linux/bootstrap {"src":"http://127.0.0.1:8390/bootstrap-aarch64.zip"}`
  （官方源 termux-packages releases；设备 /sdcard 读取在 28 下要运行时权限，走
  隧道下载免权限；下载必须 `Proxy.NO_PROXY`——系统代理劫持 loopback 会 502）

targetSdk 28 的取舍：FGS/WebView/ProxyController/CDP 全部行为不变（都早于 29）；
废弃的是 scoped storage 等新约束（kiosk 工具 app 反而受益）。

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

- bash/终端类工具在 Android 上降级（有 /system/bin/sh，无 bash/git）——服务器与 UI 不受影响；`bash` 工具已由 kylin-tools bundle 替换为 mksh 直跑
- Android 15+ 对 dataSync 前台服务有 6h 上限，超时系统会停服务（重启 app 即恢复；onStart 保活已覆盖进程内拉活）
- **麒麟 ROM 装机后约 48s 可能一次性强停 FGS**（观察到 `Stop FGS timeout` + CompatChangeReporter 同刻触发；非装机场景 130s+/1.5h+ 稳定存活）。停后下次打开 app 由 onStart 拉活
- 附件上传（layout 6 修复）：内容寻址去重的两处 `link(2)`（别名=copy 保源、staged=rename）同样被 SELinux 禁——已加 android 分支
- `dsh-experimental-inspector`、libreoffice 文档预览（wasm 146MB）已裁，功能缺失但不影响主链
- 只带 arm64-v8a；x86_64 需换 Termux x86_64 deb 同法打包
- 版本: 壳 0.1.0 · dsh 0.2.1-rc.2 · node 24.22.1 · runtime layout 11（2026-10-30 真机验证：消息收发+会话落盘+工具替换+终端面板+浏览器面板+壳内浏览器窗口全绿；附件上传待用户实测）
