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
                val server = ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
                while (true) {
                    val s = server.accept()
                    pool.execute { handle(s, ctx.applicationContext) }
                }
            }
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
            if (sh(ctx).exists()) {
                "bootstrap OK：$entries 条目 + $linkCount 链接，耗时 ${(System.currentTimeMillis() - started) / 1000}s"
            } else "解包完成但 ${sh(ctx)} 缺失（symlink 重建失败？）"
        } catch (t: Throwable) {
            "bootstrap 失败：${t.javaClass.simpleName}: ${t.message?.take(120)}"
        }
    }

    /** Run a command with the rootfs shell (60s cap, 60KB output cap). */
    fun exec(ctx: Context, cmd: String): String {
        if (!ready(ctx)) return "Linux rootfs 未就绪（${root(ctx)} 无 bin/sh——先 bootstrap）"
        if (cmd.isBlank()) return "命令为空"
        return try {
            val p = ProcessBuilder(sh(ctx).absolutePath, "-c", cmd)
                .directory(File(root(ctx), "home"))
                .redirectErrorStream(true)
            p.environment()["PATH"] = File(root(ctx), "bin").absolutePath
            p.environment()["LD_LIBRARY_PATH"] = File(root(ctx), "lib").absolutePath
            p.environment()["HOME"] = File(root(ctx), "home").absolutePath
            p.environment()["PREFIX"] = root(ctx).absolutePath
            p.environment()["TMPDIR"] = File(root(ctx), "tmp").absolutePath
            p.environment()["LANG"] = "C.UTF-8"
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
