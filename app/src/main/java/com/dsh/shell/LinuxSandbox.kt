package com.dsh.shell

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * In-app Linux userland: a Termux-bootstrap rootfs unpacked straight into
 * OUR filesDir (the main APK runs at targetSdk 28 — the Termux survival
 * trick — so app-data binaries are executable), plus a loopback HTTP bridge
 * so the Node-side kylin-tools `linux` tool can reach it:
 *
 *   GET  /linux/ping                  -> rootfs readiness
 *   POST /linux/exec    {cmd}         -> {"result": "rc=...\n..."}
 *   POST /linux/import  {path}        -> install from a local zip (adb push)
 *   POST /linux/bootstrap {src}       -> install from a URL (proxy-aware)
 *
 * Layout: files/dsh/linux/{bin,lib,...} with SYMLINKS.txt applied; env is
 * injected per exec (PATH/LD_LIBRARY_PATH/HOME/PREFIX/TMPDIR — Termux
 * binaries link against $PREFIX/lib and fail CANNOT LINK without it).
 */
object LinuxSandbox {

    private const val PORT = 33990

    private val pool = Executors.newCachedThreadPool()

    fun root(ctx: Context): File = File(ctx.filesDir, "dsh/linux")
    fun sh(ctx: Context): File = File(root(ctx), "bin/sh")
    fun bash(ctx: Context): File = File(root(ctx), "bin/bash")
    fun ready(ctx: Context): Boolean = sh(ctx).exists()

    fun start(ctx: Context) {
        val t = Thread({
            runCatching {
                android.util.Log.i("KylinOv", "linux bridge starting on $PORT")
                // SO_REUSEADDR before bind: the onTaskRemoved self-restart
                // races the dying process's socket and a plain
                // ServerSocket(port) bind fails silently (bridge-dead boot).
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 8)
                android.util.Log.i("KylinOv", "linux bridge listening")
                while (true) {
                    val s = server.accept()
                    pool.execute { handle(s, ctx.applicationContext) }
                }
            }.onFailure { android.util.Log.e("KylinOv", "linux bridge failed", it) }
        }, "dsh-linux-bridge").apply { isDaemon = true }
        t.start()
    }

    // ---------------------------------------------------------- HTTP layer

    private fun handle(sock: Socket, ctx: Context) {
        sock.use { s ->
            s.soTimeout = 480_000
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val (method, path) = parts[0] to parts[1]
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val lower = line.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = lower.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read)
            } else ""

            val (code, json) = try {
                when {
                    method == "GET" && path == "/linux/ping" ->
                        200 to JSONObject().put("result", "pong ready=${ready(ctx)} sh=${sh(ctx)}")
                    method == "POST" && path == "/linux/exec" -> {
                        val cmd = runCatching { JSONObject(body).optString("cmd") }.getOrDefault("")
                        if (cmd.isBlank()) 400 to JSONObject().put("error", "cmd required")
                        else 200 to JSONObject().put("result", exec(ctx, cmd))
                    }
                    method == "POST" && path == "/linux/import" -> {
                        val src = runCatching { JSONObject(body).optString("path") }.getOrDefault("")
                        if (src.isBlank()) 400 to JSONObject().put("error", "path required")
                        else 200 to JSONObject().put("result", install(ctx, File(src)))
                    }
                    method == "POST" && path == "/linux/bootstrap" -> {
                        val src = runCatching { JSONObject(body).optString("src") }.getOrDefault("")
                        if (src.isBlank()) 400 to JSONObject().put("error", "src required")
                        else 200 to JSONObject().put("result", bootstrap(ctx, src))
                    }
                    // ---- agent driving surface for the browser panel engine
                    method == "POST" && path.startsWith("/web/") -> {
                        val o = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
                        val res = when (path.substringAfter("/web/")) {
                            "open" -> BrowserOverlay.open(o.optString("url"))
                            "state" -> BrowserOverlay.state()
                            "dom" -> BrowserOverlay.dom(o.optString("selector"))
                            "click" -> BrowserOverlay.click(o.optString("selector"))
                            "type" -> BrowserOverlay.type(o.optString("selector"), o.optString("text"))
                            "screenshot" -> BrowserOverlay.screenshotB64()
                            "manual" -> BrowserOverlay.waitManual(
                                o.optString("reason"),
                                o.optLong("timeoutS", 300L).coerceIn(10, 900)
                            )
                            else -> "unknown web op"
                        }
                        200 to JSONObject().put("result", res)
                    }
                    else -> 404 to JSONObject().put("error", "not found")
                }
            } catch (e: Exception) {
                500 to JSONObject().put("error", "${e.javaClass.simpleName}: ${e.message?.take(120)}")
            }

            val payload = json.toString().toByteArray(Charsets.UTF_8)
            s.getOutputStream().apply {
                write(
                    ("HTTP/1.1 $code OK\r\nContent-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n").toByteArray()
                )
                write(payload)
                flush()
            }
        }
    }

    // ------------------------------------------------------ install / exec

    /** Install from a URL (honors system proxy settings; minutes). */
    fun bootstrap(ctx: Context, src: String): String {
        if (!src.startsWith("http")) return install(ctx, File(src))
        return try {
            val tmp = File(ctx.cacheDir, "bootstrap.zip")
            // NO_PROXY: the device system proxy (127.0.0.1:7890) would otherwise
            // hijack our loopback reverse-tunnel download and answer 502.
            val conn = URL(src).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) return "下载失败 HTTP ${conn.responseCode}"
            conn.inputStream.use { it.copyTo(FileOutputStream(tmp)) }
            install(ctx, tmp)
        } catch (t: Throwable) {
            "下载失败：${t.javaClass.simpleName}: ${t.message?.take(100)}"
        }
    }

    /** Unpack the Termux bootstrap zip (root-level layout + SYMLINKS.txt). */
    fun install(ctx: Context, zip: File): String {
        val started = System.currentTimeMillis()
        if (!zip.exists()) return "bootstrap 源不存在: ${zip.path}"
        return try {
            root(ctx).deleteRecursively()
            val prefix = root(ctx)
            var entries = 0
            val links = ArrayList<Pair<String, String>>()
            java.util.zip.ZipInputStream(FileInputStream(zip)).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    if (e.name == "SYMLINKS.txt") {
                        // Hand-rolled readLine — do NOT close the reader: it
                        // would close the shared ZipInputStream underneath.
                        val br = BufferedReader(InputStreamReader(zis))
                        while (true) {
                            val ln = br.readLine() ?: break
                            val parts2 = ln.split("←")
                            if (parts2.size == 2) links.add(parts2[0] to parts2[1])
                        }
                        continue
                    }
                    val out = File(prefix, e.name)
                    if (e.isDirectory) {
                        out.mkdirs()
                        out.setExecutable(true, false)
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { zis.copyTo(it) }
                        // zips carry no permission bits — Termux marks all exec.
                        out.setExecutable(true, false)
                        out.setReadable(true, false)
                        out.setWritable(true, false)
                    }
                    entries++
                }
            }
            var linkCount = 0
            for ((target, rel) in links) {
                val linkFile = File(prefix, rel)
                linkFile.parentFile?.mkdirs()
                linkFile.delete()
                runCatching { android.system.Os.symlink(target, linkFile.absolutePath) }
                    .onFailure { android.util.Log.w("LinuxSandbox", "symlink fail $rel: ${it.message?.take(40)}") }
                linkCount++
            }
            File(prefix, "tmp").mkdirs()
            File(prefix, "home").mkdirs()
            writeAptConfig(prefix)
            if (sh(ctx).exists()) {
                "bootstrap OK：$entries 条目 + $linkCount 链接，耗时 ${(System.currentTimeMillis() - started) / 1000}s"
            } else "解包完成但 ${sh(ctx)} 缺失（symlink 重建失败？）"
        } catch (t: Throwable) {
            "bootstrap 失败：${t.javaClass.simpleName}: ${t.message?.take(120)}"
        }
    }

    /**
     * apt/dpkg binaries compile their default paths against Termux's prefix
     * (/data/data/com.termux/files/usr) — at OUR prefix they die with
     * "Unable to determine a suitable packaging system type". APT_CONFIG
     * redirects the whole Dir family here; consumers must export it (see
     * [env] below and DshService).
     */
    /** Rewrite apt.conf for an already-installed rootfs (ours; keep fresh). */
    fun ensureAptConfig(ctx: Context) {
        val prefix = root(ctx)
        if (!sh(ctx).exists()) return
        writeAptConfig(prefix)
        mergeCaBundle(ctx, prefix)
        ensureRootfsFixups(prefix)
    }

    /**
     * One-time fixups a bootstrap needs before apt/dpkg work at OUR prefix
     * (all idempotent):
     *  - shebangs point at Termux's absolute prefix → rewrite to ours (the
     *    apt-key failure was exactly this);
     *  - trusted.gpg.d symlinks → same problem, re-point into our share/;
     *  - $PREFIX/data/data/com.termux/files/usr → $PREFIX: Termux debs carry
     *    full absolute paths; with dpkg --root + tar --keep-directory-symlink
     *    this lands ./data/.../usr/bin/git onto OUR bin/;
     *  - dpkg helper dummies in sbin (Termux ships them via termux-tools);
     *  - apt state/log/cache dirs the bootstrap omits.
     */
    private fun ensureRootfsFixups(prefix: File) {
        runCatching {
            val sh = File(prefix, "bin/sh").absolutePath
            // 1. shebang rewrite
            val bin = File(prefix, "bin")
            bin.listFiles()?.forEach { f ->
                if (f.isFile && f.length() < 512 * 1024) {
                    runCatching {
                        val head = f.inputStream().use { s ->
                            ByteArray(minOf(120L, f.length()).toInt()).also { s.read(it) }
                        }.decodeToString()
                        if (head.startsWith("#!/data/data/com.termux/files/usr/")) {
                            val text = f.readText()
                            f.writeText(text.replaceFirst(
                                "#!/data/data/com.termux/files/usr",
                                "#!$prefix"
                            ))
                            f.setExecutable(true, false)
                        }
                    }
                }
            }
            // 2. keyring symlinks
            val keyringSrc = File(prefix, "share/termux-keyring")
            val keyringDst = File(prefix, "etc/apt/trusted.gpg.d")
            if (keyringSrc.isDirectory) {
                keyringDst.mkdirs()
                keyringSrc.listFiles()?.forEach { k ->
                    val link = File(keyringDst, k.name)
                    link.delete()
                    runCatching { android.system.Os.symlink(k.absolutePath, link.absolutePath) }
                }
            }
            // 3. Termux absolute-path landing symlink
            val shim = File(prefix, "data/data/com.termux/files")
            shim.mkdirs()
            val usrLink = File(shim, "usr")
            if (!usrLink.exists()) {
                runCatching { android.system.Os.symlink(prefix.absolutePath, usrLink.absolutePath) }
            }
            // 4. dpkg helper dummies
            val sbin = File(prefix, "sbin").apply { mkdirs() }
            for (name in listOf("ldconfig", "install-info", "start-stop-daemon")) {
                val f = File(if (name == "start-stop-daemon") bin else sbin, name)
                if (!f.exists()) {
                    f.writeText("#!$sh\nexit 0\n")
                    f.setExecutable(true, false)
                }
            }
            // 5. apt state dirs
            for (d in listOf(
                "var/log/apt", "var/cache/apt/archives/partial",
                "var/lib/apt/lists/partial", "var/lib/dpkg/updates"
            )) File(prefix, d).mkdirs()
        }
    }

    /**
     * The bootstrap's CA set can lag (e.g. Google Trust Services WE1 — the
     * termux repo's current chain — was "issuer unknown" from it). The app's
     * own rt/cert.pem (the bundle node already verifies the outside world
     * with, through the tunnel) is newer: merge both into the rootfs bundle.
     * The merged file must be PURE PEM — a comment line made gnutls (apt's
     * https method) distrust the whole bundle; idempotency marker is a
     * sidecar file instead.
     */
    private fun mergeCaBundle(ctx: Context, prefix: File) {
        runCatching {
            val appCa = DshRuntime.certPem(ctx)
            if (!appCa.exists()) return
            val dst = File(prefix, "etc/tls/cert.pem")
            if (!dst.exists()) return
            val marker = File(prefix, "etc/tls/.dsh-merged-ca")
            if (marker.exists()) return
            val appText = appCa.readText().lines()
                .filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
            val rootText = dst.readText().lines()
                .filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
            dst.writeText(appText + "\n" + rootText + "\n")
            marker.writeText("ok")
        }
    }

    private fun writeAptConfig(prefix: File) {
        val etc = File(prefix, "etc/apt")
        etc.mkdirs()
        File(etc, "apt.conf").writeText(
            "Dir \"" + prefix.absolutePath + "\";\n" +
                "Dir::Etc \"" + File(prefix, "etc/apt").absolutePath + "\";\n" +
                "Dir::Etc::parts \"" + File(prefix, "etc/apt/apt.conf.d").absolutePath + "\";\n" +
                "Dir::Etc::sourcelist \"" + File(prefix, "etc/apt/sources.list").absolutePath + "\";\n" +
                "Dir::Etc::sourceparts \"" + File(prefix, "etc/apt/sources.list.d").absolutePath + "\";\n" +
                "Dir::State \"" + File(prefix, "var/lib/apt").absolutePath + "\";\n" +
                "Dir::State::status \"" + File(prefix, "var/lib/dpkg/status").absolutePath + "\";\n" +
                "Dir::Cache \"" + File(prefix, "var/cache/apt").absolutePath + "\";\n" +
                "Dir::Bin::dpkg \"" + File(prefix, "bin/dpkg").absolutePath + "\";\n" +
                // Same compiled-prefix problem: apt-key and gpgv live in OUR bin.
                "Dir::Bin::apt-key \"" + File(prefix, "bin/apt-key").absolutePath + "\";\n" +
                "Dir::Bin::gpgv \"" + File(prefix, "bin/gpgv").absolutePath + "\";\n" +
                "Apt::Key::Archive-Keyring \"" + File(prefix, "etc/apt/trusted.gpg").absolutePath + "\";\n" +
                "Apt::Key::Archive-Keyring-Unsigned \"" + File(prefix, "etc/apt/trusted.gpg~").absolutePath + "\";\n" +
                "Dir::Bin::methods \"" + File(prefix, "lib/apt/methods").absolutePath + "\";\n" +
                // Termux debs carry full paths (./data/data/com.termux/files/usr/...);
                // --root makes dpkg unpack under OUR prefix, where the
                // data/data/com.termux/files/usr symlink (see install())
                // redirects them onto the real rootfs layout.
                "DPkg::Options { \"--root=" + prefix.absolutePath + "\"; };\n" +
                "DPkg::Path \"" + File(prefix, "bin").absolutePath + ":" +
                    File(prefix, "sbin").absolutePath + ":/system/bin:/vendor/bin\";\n" +
                // The https method's compiled-in CA path is Termux's prefix;
                // redirect it or every fetch dies with "No system certificates".
                // (CaInfo ONLY — SslCert would be a client certificate.)
                "Acquire::https::CaInfo \"" + File(prefix, "etc/tls/cert.pem").absolutePath + "\";\n" +
                "APT::Get::AllowUnauthenticated \"false\";\n"
        )
    }

    /** Extra env every rootfs consumer should carry. */
    fun env(ctx: Context): Map<String, String> {
        val prefix = root(ctx)
        val m = LinkedHashMap<String, String>()
        // System bins last: screencap/input/dumpsys stay reachable for the
        // computer-use surface (agent screens/taps via the linux tool).
        m["PATH"] = File(prefix, "bin").absolutePath + ":" +
            File(prefix, "sbin").absolutePath + ":/system/bin:/vendor/bin"
        m["LD_LIBRARY_PATH"] = File(prefix, "lib").absolutePath
        m["HOME"] = File(prefix, "home").absolutePath
        m["PREFIX"] = prefix.absolutePath
        m["TMPDIR"] = File(prefix, "tmp").absolutePath
        m["LANG"] = "C.UTF-8"
        m["APT_CONFIG"] = File(prefix, "etc/apt/apt.conf").absolutePath
        m["DPKG_ADMINDIR"] = File(prefix, "var/lib/dpkg").absolutePath
        // Compiled-in CA paths point at Termux's prefix too — point the usual
        // env knobs at our cert bundle (curl/wget/git/openssl honor these).
        val ca = File(prefix, "etc/tls/cert.pem").absolutePath
        m["SSL_CERT_FILE"] = ca
        m["CURL_CA_BUNDLE"] = ca
        // Device-side adb (android-tools from the Termux repo) talks to the
        // HOST adb server over the reverse tunnel — the kylin-agent semantic.
        m["ADB_SERVER_SOCKET"] = "tcp:127.0.0.1:5037"
        // Rootfs egress via the host clash tunnel (same as the node env):
        // direct connections get gateway-MITMed with a 2009 VeriSign cert.
        m["HTTP_PROXY"] = "http://127.0.0.1:8333"
        m["HTTPS_PROXY"] = "http://127.0.0.1:8333"
        m["NO_PROXY"] = "127.0.0.1,localhost,10.44.11.11"
        return m
    }

    /** Run a command with the rootfs shell (60s cap, 60KB output cap). */
    fun exec(ctx: Context, cmd: String): String {
        if (!ready(ctx)) return "Linux rootfs 未就绪（${root(ctx)} 无 bin/sh——先 bootstrap）"
        if (cmd.isBlank()) return "命令为空"
        return try {
            val p = ProcessBuilder(sh(ctx).absolutePath, "-c", cmd)
                .directory(File(root(ctx), "home"))
                .redirectErrorStream(true)
            p.environment().putAll(env(ctx))
            val proc = p.start()
            val out = StringBuilder()
            val t = Thread {
                runCatching {
                    proc.inputStream.bufferedReader().forEachLine {
                        if (out.length < 60_000) out.appendLine(it)
                    }
                }
            }.apply { isDaemon = true }.also { it.start() }
            val done = proc.waitFor(60, TimeUnit.SECONDS)
            if (!done) {
                proc.destroyForcibly()
                return "超时（>60s）已强杀。已收：${out.take(300)}"
            }
            t.join(500)
            "rc=${proc.exitValue()}\n${out.take(120_000).ifBlank { "（无输出）" }}"
        } catch (t: Throwable) {
            "执行失败：${t.javaClass.simpleName}: ${t.message?.take(100)}"
        }
    }
}
