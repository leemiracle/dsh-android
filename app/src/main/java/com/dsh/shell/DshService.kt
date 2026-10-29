package com.dsh.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Foreground service that owns the DeepSeek Harness server process.
 *
 * Lifecycle: install runtime (one-shot) -> exec Node from nativeLibraryDir with
 * `--expose-internals` (required by the node-addon-require-builtin shim that the
 * bundled runtime carries) -> parse the tokenized URL from stdout -> broadcast
 * it so MainActivity can load the WebView.
 */
class DshService : Service() {

    companion object {
        const val ACTION_START = "com.dsh.shell.action.START"
        const val ACTION_STOP = "com.dsh.shell.action.STOP"

        const val BROADCAST_STATUS = "com.dsh.shell.STATUS"
        const val EXTRA_STATE = "state"
        const val EXTRA_DETAIL = "detail"
        const val EXTRA_URL = "url"

        const val STATE_INSTALLING = "installing"
        const val STATE_STARTING = "starting"
        const val STATE_RUNNING = "running"
        const val STATE_STOPPED = "stopped"
        const val STATE_ERROR = "error"

        private val URL_REGEX = Regex("""http://127\.0\.0\.1:(\d+)/\?token=(\S+)""")
    }

    private val main = Handler(Looper.getMainLooper())
    private val stopping = AtomicBoolean(false)
    /**
     * Single-flight guard for boot(): MainActivity's onCreate and onStart can
     * both deliver ACTION_START within milliseconds on a cold launch, and two
     * concurrent boots race inside ensureInstalled's delete-and-extract (the
     * FileTreeWalk assertion crashes the whole app).
     */
    private val booting = AtomicBoolean(false)
    private var proc: Process? = null
    private var serverUrl: AtomicReference<String?> = AtomicReference(null)
    private var restartAttempts = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopping.set(true)
                shutdownServer()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                stopping.set(false)
                startForegroundInternal()
                if ((proc == null || proc?.isAlive != true) && booting.compareAndSet(false, true)) {
                    thread(name = "dsh-boot") {
                        try {
                            boot()
                        } finally {
                            booting.set(false)
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF)
        )
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Deliberately keep the server running; the foreground service and
            // its dataSync type justify the wakelock-equivalent lifetime.
        }
    }

    override fun onDestroy() {
        shutdownServer()
        runCatching { unregisterReceiver(screenOffReceiver) }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- boot

    private fun boot() {
        try {
            DshRuntime.killStaleServer(this)
            broadcast(STATE_INSTALLING, "正在安装运行时（首次约 30-60 秒）")
            DshRuntime.ensureInstalled(this) { pct, msg ->
                broadcast(STATE_INSTALLING, "$msg $pct%")
            }
            DshRuntime.preseedModelConfig(this)

            broadcast(STATE_STARTING, "正在启动服务器…")
            startServer()
        } catch (e: Exception) {
            broadcast(STATE_ERROR, "启动失败: ${e.message}")
        }
    }

    private fun startServer() {
        val nativeDir = applicationInfo.nativeLibraryDir
        val nodeBin = File(nativeDir, "libnode_exec.so")
        if (!nodeBin.exists()) {
            broadcast(STATE_ERROR, "找不到 Node 运行时（$nativeDir）")
            return
        }
        val entry = DshRuntime.dshEntry(this)
        if (!entry.exists()) {
            broadcast(STATE_ERROR, "运行时树不完整：${entry.path}")
            return
        }

        val home = DshRuntime.homeDir(this)
        home.mkdirs()
        File(home, "tmp").mkdirs()

        val cmd = listOf(
            nodeBin.absolutePath,
            "--expose-internals",
            entry.absolutePath,
            "web", "--no-open", "--port", "0"
        )
        val pb = ProcessBuilder(cmd).apply {
            redirectErrorStream(true)
            directory(home) // the harness workspace root = persistent home
            environment().apply {
                put("HOME", home.absolutePath)
                put("TMPDIR", File(home, "tmp").absolutePath)
                // Harness home: keep profiles/sessions out of the workspace cwd.
                put("DSH_HOME", DshRuntime.dshHome(this@DshService).absolutePath)
                // The preseeded kylin-vllm provider runs keyless vLLM; the
                // credential seam requires a resolvable env reference.
                put("KYLIN_VLLM_API_KEY", "local-vllm-noauth")
                // Libs live in nativeLibraryDir: for targetSdk 29+ the dynamic
                // linker may only load shared objects from the app's native
                // lib dir (app-writable paths are no-exec for code).
                put("LD_LIBRARY_PATH", nativeDir)
                // The patched node-addon-system flock loader dlopens the
                // NDK-built libflocknode.so from here (same no-exec reason).
                put("DSH_NATIVE_DIR", nativeDir)
                put("SSL_CERT_FILE", DshRuntime.certPem(this@DshService).absolutePath)
                put("PATH", "/system/bin:/vendor/bin")
                // Desktop-ish locale keeps toolchain output sane on the device.
                put("LANG", "C.UTF-8")
                remove("ANDROID_ROOT")
                remove("ANDROID_DATA")
            }
        }

        DshRuntime.logDir(this).mkdirs()
        val logFile = DshRuntime.serverLog(this)
        val logWriter = FileWriter(logFile, /* append= */ false)

        val p = pb.start()
        proc = p

        thread(name = "dsh-stdout") {
            try {
                BufferedReader(InputStreamReader(p.inputStream)).forEachLine { line ->
                    synchronized(logWriter) { logWriter.write(line + "\n"); logWriter.flush() }
                    if (serverUrl.get() == null) {
                        URL_REGEX.find(line)?.let { m ->
                            val url = m.value
                            serverUrl.set(url)
                            val port = m.groupValues[1].toIntOrNull() ?: 0
                            main.post {
                                broadcast(STATE_RUNNING, "端口 $port", url)
                                updateNotification(port)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // stream closed on shutdown
            }
            // stdout ended: process exited
            synchronized(logWriter) { logWriter.close() }
            DshRuntime.pidFile(this@DshService).delete()
            val url = serverUrl.get()
            if (stopping.get()) {
                broadcast(STATE_STOPPED, "已停止")
            } else if (restartAttempts < 3) {
                restartAttempts++
                serverUrl.set(null)
                broadcast(STATE_STARTING, "服务器意外退出，重启中（第 $restartAttempts 次）")
                main.postDelayed({ startServer() }, 1500L * restartAttempts)
            } else {
                broadcast(STATE_ERROR, "服务器反复退出，详见 ${logFile.path}")
            }
        }
    }

    private fun shutdownServer() {
        proc?.let { p ->
            runCatching { p.destroyForcibly() }
        }
        proc = null
        DshRuntime.killStaleServer(this)
    }

    // ---------------------------------------------------------- plumbing

    private fun startForegroundInternal() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                getString(R.string.notification_channel),
                getString(R.string.notification_title),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        startForeground(1, buildNotification(getString(R.string.notification_text_idle)))
    }

    private fun updateNotification(port: Int) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, buildNotification(getString(R.string.notification_text_running, port)))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, DshService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder =
            if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, getString(R.string.notification_channel))
            else Notification.Builder(this)
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "停止", stop)
            .setOngoing(true)
            .build()
    }

    private fun broadcast(state: String, detail: String, url: String? = null) {
        val intent = Intent(BROADCAST_STATUS)
            .setPackage(packageName)
            .putExtra(EXTRA_STATE, state)
            .putExtra(EXTRA_DETAIL, detail)
            .putExtra(EXTRA_URL, url ?: serverUrl.get())
        sendBroadcast(intent)
    }
}
