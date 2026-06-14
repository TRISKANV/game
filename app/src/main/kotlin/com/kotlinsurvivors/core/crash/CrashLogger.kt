package com.kotlinsurvivors.core.crash

import android.content.Context
import android.util.Log
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

/**
 * CrashLogger
 *
 * Captures uncaught exceptions, periodic game stats, and crash-time
 * world state. Writes to a plain text file on external storage so you
 * can pull it via a file manager even without ADB or Android Studio.
 *
 * File location:
 *   /Android/data/com.kotlinsurvivors/files/crash_log.txt
 *
 * Keeps only the last MAX_LINES entries to prevent unbounded growth.
 */
object CrashLogger {

    private const val TAG       = "KS_Crash"
    private const val MAX_LINES = 1000
    private const val FILE_NAME = "crash_log.txt"

    private val scope   = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue   = ArrayBlockingQueue<String>(MAX_LINES + 200)
    private val dateFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var logFile: File? = null
    @Volatile private var world: World?  = null

    // ── Init ────────────────────────────────────────────────────────────────

    fun init(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        logFile = File(dir, FILE_NAME)
        log("SESSION START — ${Date()} — device=${android.os.Build.MODEL} " +
            "sdk=${android.os.Build.VERSION.SDK_INT} " +
            "ram=${availableRamMb(context)}MB available")

        installUncaughtExceptionHandler()
        log("CrashLogger initialised. Log: ${logFile?.absolutePath}")
    }

    fun attachWorld(w: World?) { world = w }

    // ── Logging ─────────────────────────────────────────────────────────────

    fun log(message: String) {
        val line = "${dateFmt.format(Date())} $message"
        Log.d(TAG, line)
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
        val mem = Runtime.getRuntime().let {
            (it.totalMemory() - it.freeMemory()) / (1024 * 1024)
        }
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
            append(" heapMB=$mem")
        })
    }

    fun logCrash(tag: String, throwable: Throwable) {
        log("═══ CRASH in $tag ═══")
        log("Exception: ${throwable::class.java.simpleName}: ${throwable.message}")
        throwable.stackTrace.take(20).forEach { log("  at $it") }
        world?.let { log("World: ${it.getDiagnosticString()}") }
        val mem = Runtime.getRuntime().let {
            (it.totalMemory() - it.freeMemory()) / (1024 * 1024)
        }
        log("Heap used: ${mem}MB / ${Runtime.getRuntime().maxMemory() / (1024*1024)}MB max")
        flush()
    }

    fun logWorldState(tag: String) {
        val w = world ?: return
        log("[$tag] ${w.getDiagnosticString()}")
    }

    // ── File I/O ─────────────────────────────────────────────────────────────

    /** Flush the in-memory queue to disk. Called after crashes and periodically. */
    fun flush() {
        scope.launch {
            try {
                val file = logFile ?: return@launch
                val lines = mutableListOf<String>()
                queue.drainTo(lines)
                if (lines.isEmpty()) return@launch
                file.appendText(lines.joinToString("\n") + "\n")
                // Trim file to last MAX_LINES if it grew too large
                trimFile(file)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to flush crash log", e)
            }
        }
    }

    /** Returns the full log content as a String for display in-app. */
    fun readLog(): String {
        return try {
            // Return queued (not-yet-flushed) + file content
            val queued = queue.toList().joinToString("\n")
            val file   = logFile
            val onDisk = if (file != null && file.exists()) file.readText() else ""
            "$onDisk\n$queued".takeLast(50_000) // last 50k chars max
        } catch (e: Exception) {
            "Error reading log: ${e.message}"
        }
    }

    fun getLogFilePath(): String = logFile?.absolutePath ?: "unavailable"

    fun clearLog() {
        queue.clear()
        scope.launch {
            try { logFile?.writeText("") } catch (e: Exception) { /* ignore */ }
        }
        log("Log cleared by user")
    }

    // ── Uncaught exception handler ───────────────────────────────────────────

    private fun installUncaughtExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            log("═══ UNCAUGHT EXCEPTION on thread '${thread.name}' ═══")
            log("${throwable::class.java.name}: ${throwable.message}")
            throwable.stackTrace.forEach { log("  at $it") }
            var cause = throwable.cause
            while (cause != null) {
                log("Caused by: ${cause::class.java.name}: ${cause.message}")
                cause.stackTrace.take(10).forEach { log("  at $it") }
                cause = cause.cause
            }
            world?.let { log("World at crash: ${it.getDiagnosticString()}") }
            val mem = Runtime.getRuntime().let {
                (it.totalMemory() - it.freeMemory()) / (1024 * 1024)
            }
            log("Heap: ${mem}MB used / ${Runtime.getRuntime().maxMemory()/(1024*1024)}MB max")
            // Flush synchronously before the process dies
            try {
                val file   = logFile ?: return@setDefaultUncaughtExceptionHandler
                val lines  = mutableListOf<String>()
                queue.drainTo(lines)
                file.appendText(lines.joinToString("\n") + "\n")
            } catch (_: Exception) {}
            previous?.uncaughtException(thread, throwable)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun trimFile(file: File) {
        try {
            val lines = file.readLines()
            if (lines.size > MAX_LINES) {
                file.writeText(lines.takeLast(MAX_LINES).joinToString("\n") + "\n")
            }
        } catch (_: Exception) {}
    }

    private fun availableRamMb(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024 * 1024)
    }
}
