# dsh-android vs dsh-Go 源码对比

> 2026-11-04 · 对比对象：本仓（dsh-android v0.1.0）与 [ccch1mneyyy/dsh-Go](https://github.com/ccch1mneyyy/dsh-Go) v1.0.10（本地 `/usr-data/data2/kylin/dsh-Go` @ b4f2d24，Trendshift 全语言日榜 #1 2026-10-30）。
> 方法：两个并行子代理分头深挖 dsh-Go（运行时嵌入线 + 应用架构线），dsh-android 侧以仓内 README + 盘上源码核验为准；关键论断均带 file:line 锚点（dsh-Go 侧锚点相对其仓库根）。

## 要点速览

1. **同一个上游、同一道逃生门，两条完全不同的承载路线**：两边都跑官方 npm 包 `@deepseek-ai/dsh`（我们 rc.2，他们 rc.4），都**不 fork 上游**，都靠 `targetSdk 28`（Termux 同款豁免）拿到 app 数据目录 exec 权——但 dsh-android 用 **bionic 直跑**（Termux node 以 jniLibs 进程 exec），dsh-Go 用 **glibc+proot**（Ubuntu 24.04 rootfs，ptrace 翻译执行）。
2. **规模差 40 倍，但那是产品雄心的差距，不是方案的差距**：dsh-android 6 个 Kotlin 文件 2275 行 + 1107 行构建脚本，做"壳"；dsh-Go 是 ~22 万行 Dart + ~4.8 万行 Kotlin + Cloudflare Worker + 插件市场 + SDK 的完整消费级产品（7 天 11 版，2026-10-29 首个源码提交）。
3. **UI 路线相反**：我们 WebView 直载官方 dsh web UI（零 UI 代码、上游升级零成本）；dsh-Go 用 Flutter 自绘全部 UI（Riverpod 3 + 自研 `dsh_client` 纯 Dart 协议栈），官方 web UI 反而只是可切换的兜底（`UiMode{native,web}`）。
4. **同坑不同解的活教材**：SELinux 禁 hardlink、`.node` addon 加载、exec 限制三个坑两边都撞上了，解法完全不同（见对照表）——dsh-Go 的 15 个 sha256 钉死补丁纪律值得直接吸收。
5. **dsh-Go 的 "Direct" 直启模式（opt-in）本质上就是我们默认路线的 glibc 版**——他们被 Android 16 seccomp 威胁所以只敢 opt-in（`DirectLaunch.kt:33-35`）；我们用 bionic 从根上免了 glibc loader，代价是每个 native addon 都要 NDK 重编或 shim。

## 总表

| 维度 | dsh-android（本仓） | dsh-Go |
|---|---|---|
| 定位 | 麒麟工作站 kiosk 壳（`com.dsh.shell` v0.1.0） | 消费级 Android 客户端（v1.0.10+1，AGPL-3.0） |
| 规模 | 33 文件；Kotlin 2275 行 + 工具链 1107 行 | 1411 文件；Dart ~22 万行（扣 l10n 生成 ~7 万行）+ Kotlin ~4.8 万行（含测试）+ TS worker |
| dsh 版本 | 0.2.1-rc.2（npm 树瘦身，zip 82MB，APK 200MB） | **0.2.1-rc.4**（`build-runtime.sh:40`，Ubuntu rootfs 全量内置 + lock 复现） |
| Node | Termux nodejs-lts 24.22.1 **bionic**，`libnode_exec.so` jniLibs 直接 exec | 官方 glibc node 24.22.1 tar.xz 装进 Ubuntu 24.04.5 rootfs，**proot 5.1.107.98** 承载（同版 Node，殊途同归） |
| targetSdk | 28（minSdk 26，compileSdk 35） | 28（minSdk 26）——同款取舍，`app/android/app/build.gradle.kts:44`，非 Play 上架故关 lint |
| UI | WebView 直载官方 web UI + BrowserOverlay 原生引擎补浏览器面板 | Flutter 自绘为主（`ui_mode_state.dart:8-21` 双模式），web UI 仅兜底 |
| 上游魔改 | 构建期 sed/patch + `cordis.patch.yml` + NDK 重编 addon | 构建期 15 个 diff + **pins.json 每补丁×版本×目标文件 sha256 钉死**（`build-runtime.sh:389-391`，未审核版本 fail build） |
| 状态管理 | 无（壳无状态） | Riverpod 3，`state/` 99 文件一文件一域，~250 provider |
| 权限画像 | INTERNET/ACCESS_NETWORK_STATE/FGS×2/POST_NOTIFICATIONS | +SYSTEM_ALERT_WINDOW（桌宠）/RECORD_AUDIO/REQUEST_INSTALL_PACKAGES/Shizuku API_V23/WRITE_SECURE_SETTINGS/POST_PROMPTED_NOTIFICATIONS |
| 测试 | 手工真机 E2E（README 实证记录） | 839 测试文件 vs 765 源文件（>1:1）+ Kotlin 单测 108 + CI 全链 |
| 迭代 | 壳 0.1.0（内部） | 7 天 11 版（2026-10-29 → 11-04），中英双语 changelog |

## 核心架构对比

### 运行时管线（最本质的分歧）

```
dsh-android:  APK jniLibs ──exec──> libnode_exec.so (bionic, Termux node 24)
              + dsh-runtime.zip (assets) → filesDir 解压（dsh node_modules 树）
              addon: NDK r29 重编成 .so（flock/pty）+ require-builtin shim
              SELinux: link(2) 禁 → android 分支改 rename/copy

dsh-Go:       APK jniLibs ──exec──> libproot.so（唯一必须 exec 的宿主二进制）
              + Ubuntu rootfs tar.xz (assets) → filesDir 解压（RuntimeInstaller staging 原子换入）
              rootfs 内: glibc node + dsh + CPython 3.12 + pnpm 全套原生可用
              proot 参数: --kill-on-exit --link2symlink --sysvipc -L -0，
                         bind /dev /proc /sys /system /apex，空目录盖 /sys/fs/selinux
                         （runtime/lib/rtbuild.py:1322-1339）
              addon: proot 下 glibc .node 直接 dlopen；
                     Direct 模式 registerHooks 包 process.dlopen + /proc/self/fd
                     别名路径绕 glibc dlopen 缓存（direct/modules.mjs:115-123）
```

**trade-off 一句话**：bionic 直跑 = 进程模型干净、无性能税、内存省，但 Linux 二进制生态每个都要手工救（我们的 flock/pty/require-builtin 三场仗）；glibc+proot = apt/addon/CPython 开箱即用，但吃 ptrace 性能税——他们自己都受不了，做了两层对冲：

- `boot/boot-cache.mjs`：proot 下每次路径查找都 trap tracer，dsh 启动期插件 loader 对只读树做数千次 realpath/existsSync 探测——按路径组件的 lstat 缓存只覆盖 `/opt/dsh /opt/node /opt/dsh-go`，打出 URL 行后直通 `node:fs`（`boot-cache.mjs:1-18`）。
- **Direct 直启**（opt-in）：显式 `ld-linux-*.so --library-path … node --import preload.mjs`（`DirectLaunch.kt:76-78`）；Android 16/x86_64 seccomp 会杀 glibc loader 所以默认关，且 proot↔direct 自动回退（`DshProcess.kt:165-167`）。

**我们的路线天然免掉他们一半的工程量（无 proot 层），他们 rootfs 生态天然免掉我们另一半的工程量（addon/native 全免救）。** 两边连 Node 版本都收敛到 24.22.1，互相印证这是当前 dsh 的最低可行版本。

### 补丁纪律（最值得抄的部分）

dsh-Go 的 patch 体系是三层防护：

1. diff 按文件名 `zz-`/`zzz-`/`zzzzz-` 前缀排序保证应用顺序；
2. `runtime/patches/pins.json` 对每补丁 × 每 dsh 版本 × 每目标文件记 before/after sha256，**升级 dsh 版本时校验不过直接 fail build**（`build-runtime.sh:11-12, 389-391`）；
3. `session-deletion/generate.py` 在内存中先应用既有补丁链再 diff——保证"已打补丁安装树"与"原始 npm 树"生成同一补丁（`generate.py:1-14`）。

对比我们：`build-runtime.sh` 内嵌 patch + `cordis.patch.yml`，升级 rc.2→rc.4 时是裸奔状态（README 自己标了"升级 DSH_NPM_VERSION 时需重生成"）。

他们 15 个补丁的全清单（`runtime/patches/`）：

| 补丁 | 用途 | 与我们的关系 |
|---|---|---|
| file-upload-resolver-dispose | file-upload Agent resolver dispose 幂等（Cordis traced read 不保函数身份） | 未撞过 |
| **fs-create-exclusive** | Android 共享存储拒硬链接时保住 no-clobber 写入语义 | **同坑**：我们的 link→rename patch（README layout 3 / 附件上传修复） |
| install-log-redaction | pnpm 安装日志对 `https://user:pass@` 凭据打码 | — |
| plugin-manager-active-run-guard | install 事件携带 stopped 状态（活跃 run 守卫） | — |
| plugin-manager-cancel-tree | 取消进程树时 ESRCH 与真实错误区分，不吞异常 | — |
| **route-guard** | dsh-host-webserver 加 dsh-app 路由守卫（Host/Origin 栅栏 + cookie） | **我们缺这层**（见"下一步"） |
| zz-direct-runtime | Direct 引擎钩子：typed path 边界、L2S 拒绝、node-pty 调用 | 我们无 direct/proot 之分 |
| zzz-config-editor-path | ConfigEditor preflight 复用 boot 的 patch parser | — |
| zzz-title-cache-refresh | 会话列表冷投影 title 缓存刷新 | — |
| zzzz-plugin-config-readability | 只读 profile ownership 探测 + plugin-config JSON 503 | — |
| zzzzz-plugin-config-bundles | 异步 config authorizers 纳入 HMR/profile 锁 | — |
| zzzzzz-session-deletion | 安全会话删除（fencing + tombstone + 派生态清理） | — |
| zzzzzzz-input-recovery | inbox 未受理 claim/请求身份跨失败、重放、fork 保留 | — |
| zzzzzzzz-model-defaults | 记住上次选的模型（rememberLastSelection） | 功能项，可借鉴 |
| zzzzzzzz-schedule-authorization | schedule 授权路径引入 RemoteError | — |

### 网络与 UI 管线

| | dsh-android | dsh-Go |
|---|---|---|
| 本地服务 | `dsh web --port 0` → stdout 吐 token URL | `dsh web --host 127.0.0.1 --port {PORT} --no-open`，stdout 正则抓 token（`rtbuild.py:1356-1374`） |
| 鉴权 | token URL 直载，cookie 名按 host 哈希（431 修复） | token → GET 换 `dsh-auth` cookie + route-guard 栅栏 |
| 主 UI | WebView（androidx.webkit，ProxyController 分流） | Flutter widget 直连 HTTP/SSE/WS（`dsh_client` 35+ API 面） |
| 桥接 | loopback `/web/*` 桥（AgentWeb 操控浏览器面板） | `runtime/bridge/dsh-go-bridge` cordis 插件（装 rootfs `/opt/dsh-go/`，`--patch` overlay 加载），暴露 `/dsh-go/v1/{inventory,occupancy,contributions,views,invoke,assets}` + `WS /dsh-go/v1/stream`（native plugin protocol v1/v1.2） |
| 浏览器 | BrowserOverlay 原生 WebView 引擎（agent 所见即所控 + web_screenshot） | `ui/web` 纯 WebView 容器（12 文件），无 agent 操控层 |

dsh-Go 的 `ui/chat/dsh_ui` **不是** web UI 复刻，而是模型回复里 ` ```dsh-ui ` 围栏 JSON 蓝图的原生渲染器（"模型输出即原生界面"，`dsh_ui.dart:1-11`），还兼容 `@changfenhuang/dsh-genui` 方言的 40+ 组件名降级渲染。

## 功能面对比

**dsh-Go 有、我们没有**（消费级产品面）：

- 手机本体 a11y 操控：`phone/` 113 个 Kotlin 文件（a11y 36 + background 43 = Shizuku/自连 ADB 特权"不打扰"通道），三档确认、应用规则
- 桌宠 Deepy/鲸娘：`pet/` 29 文件 + `PetOverlayService`（SYSTEM_ALERT_WINDOW）+ Live Activity
- 远程遥控桌面 DSH：`packages/dsh_remote`（对接第三方 ds-harness-remote MIT），Control WS → Relay 数据 WS 或 WebRTC DataChannel（LAN/P2P/TURN，flutter_webrtc），`Noise_IK_25519_ChaChaPoly_SHA256` 端到端加密 + Host key pinning；**远程 Host 复用与本地引擎同一个 DshClient API**（`dsh_remote.dart:1-21`）
- 会话分享：App 侧 AES-256-GCM 端到端加密 + Cloudflare Worker 托管（2×R2+KV+Analytics Engine，go.dshtui.com）+ 回放时间轴 viewer
- 插件市场：`market/`（npm tarball SHA-512 核验、OSI 许可、50MiB 限、npm 公告 high/critical 阻断、启发式风险分级 → index.json + dsh-go.json sidecar + audit.json）；`sdk/plugin-kit` 给插件作者加 `package.json.dshGo` 协议 sidecar
- 应用内增量更新、定时任务（header_schedule）、98 ARB 双语 i18n、订阅 OAuth 桥（ChatGPT/Claude grant）

**我们有、他们没有**（工作站/本地化面）：

- kylin 本地 vLLM 预置（qwen3.5-35b 私有化默认模型，幂等合并进 profile）
- `web_search` 的 DDG/Bing provider 替换（原工具名/UX 不变换后端）
- 壳内浏览器窗口 AgentWeb：agent 操控面板里**可见的同一个页面**（所见即所控）+ `web_manual` human-in-the-loop 门 + `web_screenshot` 多模态 ground truth（dsh-Go 的 `ui/web` 是纯容器）
- mksh 直跑的 `bash` 工具替换 + Termux bootstrap 的 apt 生态（APT_CONFIG 全家迁移改造）

**刻意分歧（边界钉版）**：dsh-android 明确禁 a11y/不操控系统浏览器与其他 app（安全隐私）；dsh-Go 的 a11y+Shizuku+WRITE_SECURE_SETTINGS 是反方向全押。两者都是有意为之，无对错，但权限面和安全暴露面完全不同量级。

## 工程质量对比

| | dsh-android | dsh-Go |
|---|---|---|
| 测试 | 手工真机 E2E（README 实证记录，2026-11-02 最近） | >1:1 测试比；Kotlin 单测 108 进 CI；market 离线测试 + 真实 tgz 夹具；bridge 对"真实打补丁 DSH 主机"的路由守卫集成测试（ci.yml） |
| CI | 无 | ci.yml 全家（Flutter 测试 3 分片+golden、生成物同步检查、l10n_merge --check、Android JVM）+ pr-gate.yml 自动关外部 PR |
| 供应链 | termux deb + npm 在线拉 | 三重锁：Ubuntu noble deb 闭包（gpgv 验 InRelease→Packages→deb 哈希链，`build-runtime.sh:157-198`）+ pnpm tarball integrity 双钉 + dsh package-lock（`runtime/locks/`） |
| 文档 | README.md（单文件，实证密度高） | architecture.md（含平台通道契约表）/official-parity.md/roadmap/12 章用户指南/插件文档，全双语且 guide_sync.py 校验 |

壳小到手工验证可接受，但**patch 层的回归风险与 dsh-Go 不是一个安全等级**——他们升级 dsh 版本有 sha256 门禁，我们没有。

## 风险与边界

- **AGPL-3.0 传染警告**：借鉴 dsh-Go 的思想/坑位佐证没问题，但任何代码级吸收（包括抄补丁 diff 文本）会把 AGPL 引进 dsh-android。可吸收的是"他们也这么撞坑"的佐证与方案形态，实现走自己的路。
- **版本落后**：我们钉 rc.2，dsh-Go 已 rc.4 且有 lockfile 复现链；升级时若无补丁校验纪律最容易翻车。
- dsh-Go 不接受外部 PR（`pr-gate.yml`）+ `market/` 待拆独立仓——别指望给它提 patch 换上游收敛，坑各自消化。
- dsh-Go 是极早期爆发型项目（首提交 2026-10-29，7 天 11 版），其 API/补丁面随 dsh 上游 rc 快速变动，其方案细节的时效性以 changelog 为准。

## 下一步（吸收清单，按性价比排序）

1. **补丁纪律**：给 `build-runtime.sh` 加 pins.json 式 sha256 门禁（每 patch×版本×目标文件 before/after），再考虑升 rc.4。
2. **route-guard 自查**：对照 dsh-Go 的 route-guard 补丁给我们的 webserver 加 Host/Origin 栅栏（WebView↔localhost 模式同款暴露面；431 cookie 我们修了 host 哈希，Origin 栅栏还没做）。
3. **fs-create-exclusive 对照**：核对他们的 no-clobber 语义保法与我们 link→rename patch 的语义等价性（尤其附件上传路径）。
4. 若需要消费级功能面（分享/遥控/插件市场），dsh-Go 架构可作选型参考，注意 AGPL 边界。
