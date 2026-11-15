package com.kotlinsurvivors.core.crash

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.util.Log
import androidx.annotation.RequiresApi
import com.kotlinsurvivors.engine.ecs.World
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CrashLogger
 *
 * In-app diagnostics for devices where no ADB / Android Studio is available.
 *
 * What it records:
 *  - Uncaught exceptions with full stack traces (any thread).
 *  - Periodic game statistics together with Java heap, process RSS,
 *    native heap, thread count and system memory state.
 *  - System memory pressure callbacks (onTrimMemory / onLowMemory).
 *  - Why the PREVIOUS process died, using the official
 *    ActivityManager.getHistoricalProcessExitReasons() API (Android 11+).
 *  - A foreground marker file: if it still exists at the next start, the
 *    previous session ended while the app was visible and not through a
 *    normal stop (Java crash, native crash or kill by the system).
 *
 * File location (debug builds carry the ".debug" suffix):
 *   /Android/data/com.kotlinsurvivors.debug/files/crash_log.txt
 *
 * The in-app viewer is hidden in Settings (tap "MIT Open Source" 5 times).
 */
object CrashLogger {

    private const val TAG        = "KS_Crash"
    private const val MAX_LINES  = 1000
    private const val FILE_NAME  = "crash_log.txt"
    private const val MARKER_NAME = "foreground.marker"
    private const val TRIM_THRESHOLD_BYTES = 256L * 1024L
    private const val MAX_READ_CHARS = 120_000

    private val ioScope  = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue    = ArrayBlockingQueue<String>(MAX_LINES + 200)
    private val fileLock = Any()
    private val dateFmt  = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private val initialised = AtomicBoolean(false)

    @Volatile private var logFile: File?       = null
    @Volatile private var markerFile: File?    = null
    @Volatile private var appContext: Context? = null
    @Volatile private var world: World?        = null

    // ── Init ────────────────────────────────────────────────────────────────

    /** Safe to call more than once; only the first call has an effect. */
    fun init(context: Context) {
        if (!initialised.compareAndSet(false, true)) return

        val app = context.applicationContext
        appContext = app

        val dir = app.getExternalFilesDir(null) ?: app.filesDir
        logFile    = File(dir, FILE_NAME)
        markerFile = File(dir, MARKER_NAME)

        // Must be read BEFORE the lifecycle callbacks recreate the marker.
        val previousWasUnclean = try { markerFile?.exists() == true } catch (_: Exception) { false }

        log("════════ SESSION START ════════")
        log(deviceSummary(app))
        log("Log file: ${logFile?.absolutePath}")

        installUncaughtExceptionHandler()

        (app as? Application)?.let {
            registerLifecycleCallbacks(it)
            registerMemoryCallbacks(it)
        }

        if (previousWasUnclean) {
            log("!!! PREVIOUS SESSION DID NOT STOP CLEANLY: the app was in the foreground " +
                "when its process ended (Java crash, native crash or killed by the system). " +
                "Check the EXIT REASON lines and any UNCAUGHT EXCEPTION above.")
        }

        ioScope.launch {
            logPreviousExitReasons(app)
            flushNow()
        }
    }

    fun attachWorld(w: World?) { world = w }

    // ── Logging ─────────────────────────────────────────────────────────────

    fun log(message: String) {
        val stamp = synchronized(dateFmt) { dateFmt.format(Date()) }
        val line  = "$stamp $message"
        Log.d(TAG, message)
        if (queue.size >= MAX_LINES) queue.poll() // drop oldest
        queue.offer(line)
    }

    fun logStats(
        elapsedTime : Float,
        frameCount  : Long,
        level       : Int,
        xp          : Int,
        xpNext      : Int,
        kills       : Int
    ) {
        val w = world
        log(buildString {
            append("STATS @${elapsedTime.toInt()}s frame=$frameCount")
            append(" level=$level xp=$xp/$xpNext kills=$kills")
            if (w != null) {
                append(" enemies=${w.enemies.size}")
                append(" proj=${w.projectiles.size}")
                append(" pickups=${w.pickups.size}")
                append(" particles=${w.particles.size}")
                append(" dmgNums=${w.damageNumbers.size}")
                append(" entities=${w.getLiveEntityCount()}")
                append(" destroyed=${w.destroyedTags.size}")
            }
            append(" | ")
            append(memorySnapshot())
        })
        // Stats arrive every few seconds: persist them right away so that an
        // abrupt kill still leaves the last known state on disk.
        flush()
    }

    fun logCrash(tag: String, throwable: Throwable) {
        log("═══ CRASH in $tag ═══")
        log("Exception: ${throwable::class.java.name}: ${throwable.message}")
        throwable.stackTrace.take(20).forEach { log("  at $it") }
        world?.let { log("World: ${it.getDiagnosticString()}") }
        log(memorySnapshot())
        flush()
    }

    fun logWorldState(tag: String) {
        val w = world ?: return
        log("[$tag] ${w.getDiagnosticString()}")
    }

    // ── File I/O ─────────────────────────────────────────────────────────────

    /** Asynchronously persists the in-memory queue. */
    fun flush() {
        ioScope.launch { flushNow() }
    }

    /** Synchronous flush. Used by the crash handler and by the viewer. */
    private fun flushNow() {
        try {
            synchronized(fileLock) {
                val file = logFile ?: return
                val lines = ArrayList<String>(64)
                queue.drainTo(lines)
                if (lines.isEmpty()) return
                file.appendText(lines.joinToString("\n") + "\n")
                if (file.length() > TRIM_THRESHOLD_BYTES) trimFile(file)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to flush log", e)
        }
    }

    /** Returns the log as individual lines (oldest first) for the viewer. */
    fun readLogLines(): List<String> {
        return try {
            flushNow()
            synchronized(fileLock) {
                val file = logFile
                if (file == null || !file.exists()) emptyList()
                else file.readLines().takeLast(MAX_LINES)
            }
        } catch (e: Exception) {
            listOf("Error reading log: ${e.message}")
        }
    }

    /** Returns the tail of the log as a single String. */
    fun readLog(): String = readLogLines().joinToString("\n").takeLast(MAX_READ_CHARS)

    fun getLogFilePath(): String = logFile?.absolutePath ?: "unavailable"

    /** Synchronous: when it returns the file is already empty. */
    fun clearLog() {
        queue.clear()
        try {
            synchronized(fileLock) { logFile?.writeText("") }
        } catch (_: Exception) { /* ignore */ }
        log("Log cleared by user")
        flushNow()
    }

    // ── Uncaught exception handler ───────────────────────────────────────────

    private fun installUncaughtExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                log("═══ UNCAUGHT EXCEPTION on thread '${thread.name}' ═══")
                log("${throwable::class.java.name}: ${throwable.message}")
                throwable.stackTrace.take(40).forEach { log("  at $it") }
                var cause = throwable.cause
                var depth = 0
                while (cause != null && depth < 5) {
                    log("Caused by: ${cause::class.java.name}: ${cause.message}")
                    cause.stackTrace.take(15).forEach { log("  at $it") }
                    cause = cause.cause
                    depth++
                }
                world?.let { log("World at crash: ${it.getDiagnosticString()}") }
                log(memorySnapshot())
            } catch (_: Throwable) {
                // Never let the diagnostics themselves hide the original crash.
            }
            // Flush synchronously before the process dies.
            flushNow()
            previous?.uncaughtException(thread, throwable)
        }
    }

    // ── Foreground marker ────────────────────────────────────────────────────

    private fun registerLifecycleCallbacks(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                writeMarker()
                log("APP FOREGROUND (${activity::class.java.simpleName} started)")
            }

            override fun onActivityStopped(activity: Activity) {
                // Rotations / config changes stop and restart the activity;
                // that is not a real exit, so keep the marker in that case.
                if (activity.isChangingConfigurations) return
                clearMarker()
                log("APP BACKGROUND (clean stop)")
                flush()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun writeMarker() {
        try { markerFile?.writeText(System.currentTimeMillis().toString()) } catch (_: Exception) {}
    }

    private fun clearMarker() {
        try { markerFile?.delete() } catch (_: Exception) {}
    }

    // ── Memory pressure callbacks ────────────────────────────────────────────

    private fun registerMemoryCallbacks(app: Application) {
        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                log("!!! onTrimMemory level=${trimLevelName(level)} | ${memorySnapshot()}")
                flush()
            }

            override fun onLowMemory() {
                log("!!! onLowMemory | ${memorySnapshot()}")
                flush()
            }

            override fun onConfigurationChanged(newConfig: Configuration) {}
        })
    }

    private fun trimLevelName(level: Int): String = when (level) {
        5    -> "RUNNING_MODERATE(5)"
        10   -> "RUNNING_LOW(10)"
        15   -> "RUNNING_CRITICAL(15)"
        20   -> "UI_HIDDEN(20)"
        40   -> "BACKGROUND(40)"
        60   -> "MODERATE(60)"
        80   -> "COMPLETE(80)"
        else -> "UNKNOWN($level)"
    }

    // ── Previous process exit reasons (Android 11+) ─────────────────────────

    private fun logPreviousExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            log("EXIT REASON: not available (needs Android 11+, this device is API ${Build.VERSION.SDK_INT})")
            return
        }
        try {
            readExitReasons(context)
        } catch (e: Exception) {
            log("EXIT REASON: failed to read (${e.javaClass.simpleName}: ${e.message})")
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readExitReasons(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val reasons = am.getHistoricalProcessExitReasons(context.packageName, 0, 6)
        if (reasons.isEmpty()) {
            log("EXIT REASON: no history recorded yet")
            return
        }
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        reasons.forEach { info ->
            log(
                "EXIT REASON: ${exitReasonName(info.reason)} " +
                "at=${fmt.format(Date(info.timestamp))} " +
                "status=${info.status} " +
                "importance=${importanceName(info.importance)} " +
                "pss=${info.pss / 1024}MB rss=${info.rss / 1024}MB " +
                "desc=${info.description ?: "-"}"
            )
        }
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR                    -> "ANR"
        ApplicationExitInfo.REASON_CRASH                  -> "CRASH (Java exception)"
        ApplicationExitInfo.REASON_CRASH_NATIVE           -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED        -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF              -> "EXIT_SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY             -> "LOW_MEMORY (killed by system)"
        ApplicationExitInfo.REASON_OTHER                  -> "OTHER"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE      -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED               -> "SIGNALED"
        ApplicationExitInfo.REASON_UNKNOWN                -> "UNKNOWN"
        ApplicationExitInfo.REASON_USER_REQUESTED         -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED           -> "USER_STOPPED"
        else                                              -> "REASON_$reason"
    }

    private fun importanceName(importance: Int): String = when (importance) {
        100  -> "FOREGROUND"
        125  -> "FOREGROUND_SERVICE"
        200  -> "VISIBLE"
        230  -> "PERCEPTIBLE"
        300  -> "SERVICE"
        400  -> "CACHED"
        else -> importance.toString()
    }

    // ── Memory helpers ───────────────────────────────────────────────────────

    /**
     * One-line memory summary:
     *  java   = Java heap used / max allowed for this app
     *  native = native heap allocated by the process
     *  rss    = resident set size of the whole process (from /proc)
     *  threads= live thread count (a growing value means a thread leak)
     *  sysAvail / lowMem = what the system reports
     */
    private fun memorySnapshot(): String {
        val rt = Runtime.getRuntime()
        val javaUsedMb = (rt.totalMemory() - rt.freeMemory()) / MB
        val javaMaxMb  = rt.maxMemory() / MB
        val nativeMb   = Debug.getNativeHeapAllocatedSize() / MB
        val proc       = readProcStatus()

        val sb = StringBuilder()
        sb.append("java=${javaUsedMb}/${javaMaxMb}MB native=${nativeMb}MB")
        sb.append(" rss=${proc.first}MB threads=${proc.second}")

        val ctx = appContext
        if (ctx != null) {
            try {
                val am   = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val info = ActivityManager.MemoryInfo()
                am.getMemoryInfo(info)
                sb.append(" sysAvail=${info.availMem / MB}MB lowMem=${info.lowMemory}")
            } catch (_: Exception) { /* ignore */ }
        }
        return sb.toString()
    }

    /** Returns (VmRSS in MB, thread count) read from /proc/self/status, or (-1, -1). */
    private fun readProcStatus(): Pair<Long, Int> {
        var rssKb = -1L
        var threads = -1
        try {
            File("/proc/self/status").forEachLine { line ->
                when {
                    line.startsWith("VmRSS:")  -> rssKb = line.filter { it.isDigit() }.toLongOrNull() ?: -1L
                    line.startsWith("Threads:") -> threads = line.filter { it.isDigit() }.toIntOrNull() ?: -1
                }
            }
        } catch (_: Exception) { /* ignore */ }
        return Pair(if (rssKb >= 0) rssKb / 1024 else -1L, threads)
    }

    private fun deviceSummary(context: Context): String {
        val am   = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return "device=${Build.MANUFACTURER} ${Build.MODEL} " +
            "android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) " +
            "pkg=${context.packageName} " +
            "ramTotal=${info.totalMem / MB}MB ramAvail=${info.availMem / MB}MB " +
            "lowRamDevice=${am.isLowRamDevice} " +
            "heapClass=${am.memoryClass}MB javaHeapMax=${Runtime.getRuntime().maxMemory() / MB}MB"
    }

    private fun trimFile(file: File) {
        try {
            val lines = file.readLines()
            if (lines.size > MAX_LINES) {
                file.writeText(lines.takeLast(MAX_LINES).joinToString("\n") + "\n")
            }
        } catch (_: Exception) { /* ignore */ }
    }

    private const val MB = 1024L * 1024L
}
