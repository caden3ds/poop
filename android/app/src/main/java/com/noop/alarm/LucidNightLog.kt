package com.noop.alarm

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A persistent, bounded timeline of what the lucid night path actually did.
 *
 * WHY A FILE AND NOT THE STRAP LOG
 * ────────────────────────────────
 * The strap log is an in-memory ring: it dies with the process, so by morning — after a restart, an
 * update, or an OS kill — the night it recorded is gone. Every diagnosis so far has been reconstructed
 * from aggregate counters, which say WHICH link broke but never WHEN or WHY. This writes to disk.
 *
 * WHAT IT RECORDS
 * ───────────────
 * Transitions and decisions, NOT every sample. The strap streams at roughly 1 Hz, so a full night is
 * ~29,000 ticks; logging each one would produce a file nobody reads and would itself cost battery.
 * Instead: state changes (stream armed/released, REM entered/left, cue fired, hold reason changed) plus
 * a periodic heartbeat carrying the current numbers. That is enough to reconstruct the night.
 *
 * Bounded two ways so it can never grow without limit: the file is truncated to the most recent
 * [MAX_BYTES] whenever it exceeds it, and each line is capped.
 */
object LucidNightLog {

    /** Cap on the log file. A night of transitions plus 5-minute heartbeats is far under this. */
    private const val MAX_BYTES = 256 * 1024

    private const val FILE_NAME = "lucid-night.log"

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    /** Guards the append + truncate pair; called from the BLE collector and the alarm receiver. */
    private val lock = Any()

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /**
     * Append one line. Cheap and best-effort: a logging failure must never take down the feature it is
     * describing, so every path here swallows.
     */
    fun log(context: Context, line: String) {
        runCatching {
            synchronized(lock) {
                val f = file(context)
                f.appendText("${stamp.format(Date())}  ${line.take(400)}\n")
                if (f.length() > MAX_BYTES) trimTo(f, MAX_BYTES / 2)
            }
        }
    }

    /** Read the log back, newest last. Empty when nothing has been written yet. */
    fun read(context: Context): String =
        runCatching { file(context).takeIf { it.isFile }?.readText().orEmpty() }.getOrDefault("")

    fun clear(context: Context) {
        runCatching { synchronized(lock) { file(context).delete() } }
    }

    /** Keep the most recent [keepBytes], dropping whole lines from the front. */
    private fun trimTo(f: File, keepBytes: Int) {
        runCatching {
            val text = f.readText()
            if (text.length <= keepBytes) return
            val cut = text.length - keepBytes
            val from = text.indexOf('\n', cut).let { if (it < 0) cut else it + 1 }
            f.writeText("--- trimmed ---\n" + text.substring(from))
        }
    }
}
