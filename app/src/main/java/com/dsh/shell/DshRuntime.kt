package com.dsh.shell

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Owns the on-device layout of the bundled DeepSeek Harness runtime and the
 * one-shot install that turns the APK assets into an executable tree.
 *
 * APK layout (built by tools/build-runtime.sh):
 *   lib/arm64-v8a/libnode_exec.so  — Termux Node.js 24 LTS binary (exec'd from
 *                                     nativeLibraryDir, the only no-exec-exempt dir)
 *   lib/arm64-v8a/lib*.so          — the nine loader libraries under their exact
 *                                     sonames; LD_LIBRARY_PATH=nativeLibraryDir
 *   assets/dsh-runtime.zip          — CA bundle + pruned `@deepseek-ai/dsh`
 *                                     node_modules tree + node-addon-require-builtin
 *                                     shim overlay (data only, no native code)
 *
 * Installed layout under filesDir/dsh:
 *   rt/cert.pem                     — TLS trust store for HTTPS LLM calls
 *   web/                            — dsh npm runtime tree (server + web UI)
 *   home/                           — HOME/TMPDIR/cwd given to the server
 *   logs/                           — captured server stdout
 */
object DshRuntime {

    const val ASSET_ZIP = "dsh-runtime.zip"
    const val ASSET_VERSION = "dsh-runtime.version"

    /** Bump whenever the bundled runtime tree changes shape. */
    private const val LAYOUT_VERSION = "1"

    fun root(ctx: Context): File = File(ctx.filesDir, "dsh")
    fun certPem(ctx: Context): File = File(root(ctx), "rt/cert.pem")
    /** Harness home ($DSH_HOME): profiles, sessions, credentials. */
    fun dshHome(ctx: Context): File = File(homeDir(ctx), ".dsh")

    /**
     * Preseed the model configuration with the same local vLLM that
     * kylin-agent uses (LocalHarness: qwen3.5-35b over an adb-reverse
     * tunnel at 127.0.0.1:11433). Written into the profile patch layer —
     * the same file the web UI edits — and only when absent, so anything
     * the user later changes in the UI wins.
     */
    fun preseedModelConfig(ctx: Context) {
        val profileDir = File(dshHome(ctx), "profiles/web")
        val patch = File(profileDir, "cordis.patch.yml")
        if (patch.exists()) return
        profileDir.mkdirs()
        patch.writeText(
            """
            # DSH-Android preseed: Kylin local vLLM (same source as kylin-agent LocalHarness).
            # Reachable on-device via: bash kylin-agent/bin/kylin100.sh tunnel
            - id: llm-pi-ai
              config:
                providers:
                  kylin-vllm:
                    apiKeyEnv: KYLIN_VLLM_API_KEY
                    api: openai-completions
                    baseURL: http://127.0.0.1:11433/v1
                    models:
                      - id: qwen3.5-35b
            - id: agent-default-model
              config:
                provider: kylin-vllm
                model: qwen3.5-35b
            """.trimIndent()
        )
    }
    fun webDir(ctx: Context): File = File(root(ctx), "web")
    fun homeDir(ctx: Context): File = File(root(ctx), "home")
    fun logDir(ctx: Context): File = File(root(ctx), "logs")
    fun serverLog(ctx: Context): File = File(logDir(ctx), "server.log")
    fun pidFile(ctx: Context): File = File(root(ctx), "server.pid")

    fun dshEntry(ctx: Context): File =
        File(webDir(ctx), "node_modules/@deepseek-ai/dsh/lib/bin.js")

    /**
     * Idempotent install. Returns quickly when the current asset version is
     * already extracted. Reports progress via [onProgress] (0..100).
     */
    fun ensureInstalled(ctx: Context, onProgress: (Int, String) -> Unit) {
        val marker = File(root(ctx), ".installed-${LAYOUT_VERSION}-${assetVersion(ctx)}")
        if (marker.exists() && dshEntry(ctx).exists()) return

        onProgress(0, "准备安装目录")
        val dsh = root(ctx)
        // Wipe any previous layout, then extract fresh. The tree is disposable
        // (user data lives under home/ which we preserve).
        for (child in listOf(File(dsh, "rt"), File(dsh, "web"))) {
            child.deleteRecursively()
        }
        dsh.mkdirs()
        File(dsh, "home").mkdirs()
        File(dsh, "logs").mkdirs()

        val zipStream = ctx.assets.open(ASSET_ZIP)
        var done = 0L
        val total = zipStream.available().toLong().coerceAtLeast(1)
        ZipInputStream(zipStream.buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val out = File(dsh, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fout -> zin.copyTo(fout) }
                    done += entry.compressedSize.coerceAtLeast(0)
                    if (done % 97 == 0L || entry.isDirectory) {
                        // coarse progress; entry counts are fine for a one-time install
                    }
                }
                zin.closeEntry()
            }
        }
        onProgress(100, "安装完成")
        marker.writeText("ok")
    }

    private fun assetVersion(ctx: Context): String = try {
        ctx.assets.open(ASSET_VERSION).bufferedReader().use { it.readText().trim() }
    } catch (_: IOException) {
        "unknown"
    }

    /**
     * Kill stale server processes left behind after the app process died.
     * The node child runs under our uid, and /proc only exposes same-uid
     * entries to us, so a cmdline scan is precise — java.lang.Process has
     * no child-pid accessor in public Android API.
     */
    fun killStaleServer(ctx: Context) {
        val procDir = File("/proc")
        val candidates = procDir.listFiles() ?: return
        for (d in candidates) {
            val name = d.name
            if (name.isEmpty() || !name[0].isDigit()) continue
            val pid = name.toIntOrNull() ?: continue
            if (pid == android.os.Process.myPid()) continue
            try {
                val cmdline = File(d, "cmdline").readText()
                if (cmdline.contains("libnode_exec")) {
                    android.os.Process.killProcess(pid)
                }
            } catch (_: Exception) {
                // not ours / already gone
            }
        }
        pidFile(ctx).delete()
    }
}
