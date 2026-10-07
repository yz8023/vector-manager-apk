package org.matrix.vector.manager

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * The manager's own log, kept where the manager can always read it.
 *
 * Everything logged through [logE], [logW] and [logI] also reaches logcat, and from there —
 * while verbose logging is on and a daemon is alive — the daemon's verbose stream. Neither
 * condition holds in the case this exists for: a reader diagnosing the manager on a device whose
 * framework is not activated, or in root-fallback mode, has no log to open at all. So the same
 * text is kept here: a bounded in-memory ring plus one append-only file in the app's own storage,
 * readable with no daemon, no root and no permissions.
 *
 * One entry per [logE]/[logW]/[logI] call, stack trace and all — [emit] splits long text across
 * several `Log.println` calls for liblog's payload cap, but the store receives the text once,
 * whole. Lines are JSON-encoded on disk (`t` epoch millis, `p` priority, `s` text), one object
 * per line, so a stack trace's newlines survive round-trips that would break a plain-text format.
 * A line the parser cannot read is skipped, never fatal: this runs on the logging path of a
 * process that may be mid-failure.
 *
 * The file is capped at [MAX_FILE_BYTES] by dropping the oldest half on the way past the cap,
 * both at install and at write time. Ring and file agree on nothing else: the ring is what the
 * screen reads live, the file is what survives the process, and each is cheap to keep honest on
 * its own.
 */
object AppLogStore {

    /** One logged line, whole. [text] may contain newlines. */
    data class Entry(val timeMs: Long, val priority: Int, val text: String)

    private const val MAX_ENTRIES = 4000
    private const val MAX_FILE_BYTES = 1_000_000L
    private const val FILE_NAME = "app.log"

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private var file: File? = null

    /** Bumped on every write and every clear; the screen collects it to re-read the snapshot. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Installs the backing file and loads what a previous process left. Called once, at attach. */
    @Synchronized
    fun install(context: Context) {
        synchronized(lock) {
            if (file != null) return
            val dir = File(context.applicationContext.filesDir, "logs")
            val f = File(dir, FILE_NAME)
            file = f
            runCatching {
                if (f.length() > MAX_FILE_BYTES) truncateToTail(f)
                loadTail(f)
            }
            _revision.value += 1
        }
    }

    /** Everything currently held, oldest first. */
    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    /** Drops everything, in memory and on disk. */
    fun clear() {
        synchronized(lock) {
            entries.clear()
            runCatching { file?.writeText("") }
            _revision.value += 1
        }
    }

    /** Appends one entry. Never throws: a logging path must not become a crash path. */
    fun record(priority: Int, text: String) {
        synchronized(lock) {
            val entry = Entry(System.currentTimeMillis(), priority, text)
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
            val f = file ?: return
            runCatching {
                if (f.length() > MAX_FILE_BYTES) truncateToTail(f)
                f.appendText(encode(entry) + "\n")
            }
            _revision.value += 1
        }
    }

    private fun encode(entry: Entry): String =
        JSONObject()
            .put("t", entry.timeMs)
            .put("p", entry.priority)
            .put("s", entry.text)
            .toString()

    private fun decode(line: String): Entry? =
        runCatching {
            val o = JSONObject(line)
            Entry(o.getLong("t"), o.getInt("p"), o.getString("s"))
        }
            .getOrNull()

    /** Keeps roughly the newest half of the file, then reloads the ring from it. */
    private fun truncateToTail(f: File) {
        val bytes = f.readBytes()
        val keep = bytes.size / 2
        var start = 0
        if (keep in 1 until bytes.size) {
            start = bytes.indexOf('\n'.code.toByte(), bytes.size - keep)
            if (start < 0) start = 0 else start += 1
        }
        f.writeBytes(bytes.copyOfRange(start, bytes.size))
        synchronized(lock) { entries.clear() }
    }

    private fun loadTail(f: File) {
        if (!f.isFile) return
        // Only the tail is worth loading; the ring caps at MAX_ENTRIES anyway.
        val bytes = f.readBytes()
        val from = if (bytes.size > MAX_FILE_BYTES) bytes.size - MAX_FILE_BYTES else 0
        var start = from
        if (from > 0) {
            val nl = bytes.indexOf('\n'.code.toByte(), from)
            if (nl >= 0) start = nl + 1
        }
        String(bytes, start, bytes.size - start, Charsets.UTF_8)
            .lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line -> decode(line)?.let { entries.addLast(it) } }
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }
}
