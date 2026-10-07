package org.matrix.vector.manager.root

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * Reads `modules_config.db` by copying it out from under the daemon and opening the copy locally.
 *
 * This is the fallback behind [RootCli]: for a framework that has no CLI to ask — a plain LSPosed
 * install, or a Vector whose cli binary never shipped — the database file is still the ground
 * truth, and a copy read read-only cannot corrupt anything. The cost of that safety is staleness:
 * what this reports is what the daemon last flushed, so writes are not offered in this mode.
 *
 * The schema has moved more than once — `modules` gained `module_pkg_name`, scope moved from
 * `mid` joins to package-name joins, and per-user state arrived as its own table — so nothing
 * about the shape is assumed; every table is probed with `PRAGMA table_info` first.
 */
internal class RootDatabase(private val context: Context) {

    data class Snapshot(
        val enabled: Set<String>,
        /** Scope rows per module package; a module absent from the map has no scope configured. */
        val scopes: Map<String, List<Pair<String, Int>>>,
        /** Modules whose `auto_include` flag is set — "include new apps" everywhere else. */
        val autoInclude: Set<String>,
    )

    private val staging: File get() = File(context.cacheDir, "lspd-config-readonly")

    /** Copies db + WAL sidecars through root and reads them locally. Null: unavailable. */
    fun read(): Snapshot? {
        if (!RootShell.ensureGranted()) return null
        val copied = copyDatabase() ?: return null
        return runCatching { query(copied) }
            .onFailure { Log.w(TAG, "root db read failed", it) }
            .getOrNull()
    }

    private fun copyDatabase(): File? {
        val dir = staging
        val dbPath = "/data/adb/lspd/config/modules_config.db"
        // Root writes into our cache dir and hands the modes back, so this uid can read what it
        // created. The WAL and SHM sidecars come along under their own names; SQLite replays the
        // WAL only when the names line up beside the main file.
        val shell =
            "mkdir -p '${dir.absolutePath}' && rm -f '${dir.absolutePath}'/modules_config.db* && " +
                "cp '$dbPath' '${dir.absolutePath}/' && " +
                "cp '$dbPath-wal' '${dir.absolutePath}/' 2>/dev/null; " +
                "cp '$dbPath-shm' '${dir.absolutePath}/' 2>/dev/null; " +
                "chmod 755 '${dir.absolutePath}' && chmod 644 '${dir.absolutePath}'/modules_config.db*"
        val result = RootShell.su(shell, timeoutMs = 20_000)
        val db = File(dir, "modules_config.db")
        if (!result.ok || !db.exists() || db.length() == 0L) {
            Log.w(TAG, "root db copy failed: code=${result.exitCode} ${result.err.take(200)}")
            return null
        }
        return db
    }

    private fun query(dbFile: File): Snapshot? {
        val db =
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        db.use {
            val moduleCols = columns(it, "modules") ?: return null
            val scopeCols = columns(it, "scope")
            val stateCols = columns(it, "modules_state")

            val byPackage = "module_pkg_name" in moduleCols
            val moduleKey = if (byPackage) "module_pkg_name" else "mid"

            // Enabled state: per-(module, user) in modules_state when that table exists, else the
            // legacy `enabled` column on modules itself.
            val enabled =
                when {
                    stateCols != null && "enabled" in stateCols -> {
                        val stateKey =
                            when {
                                "module_pkg_name" in stateCols -> "m.module_pkg_name = ms.module_pkg_name"
                                "mid" in stateCols -> "m.mid = ms.mid"
                                else -> null
                            } ?: return null
                        val sql =
                            "SELECT DISTINCT m.module_pkg_name FROM modules m JOIN modules_state ms " +
                                "ON $stateKey WHERE ms.enabled = 1"
                        it.rawQuery(sql, null).use { c ->
                            buildSet { while (c.moveToNext()) add(c.getString(0)) }
                        }
                    }
                    "enabled" in moduleCols -> {
                        it.rawQuery("SELECT module_pkg_name FROM modules WHERE enabled = 1", null)
                            .use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
                    }
                    else -> emptySet()
                }

            // `auto_include` is this schema's name for "include new apps", and carries no user
            // dimension; a schema without the column reads as "nobody has set it".
            val autoInclude =
                if ("auto_include" in moduleCols) {
                    it.rawQuery("SELECT module_pkg_name FROM modules WHERE auto_include = 1", null)
                        .use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
                } else emptySet()

            // Scope rows: package-name join in the current schema, mid join in the old one, and
            // `user_id` appears with the per-user schema — a legacy row targets user 0 only.
            val scopes = mutableMapOf<String, MutableList<Pair<String, Int>>>()
            if (scopeCols != null) {
                val scopeKey =
                    when {
                        "module_pkg_name" in scopeCols -> "s.module_pkg_name = m.module_pkg_name"
                        "mid" in scopeCols -> "s.mid = m.mid"
                        else -> null
                    }
                if (scopeKey != null) {
                    val userSel = if ("user_id" in scopeCols) "s.user_id" else "0"
                    val sql =
                        "SELECT m.module_pkg_name, s.app_pkg_name, $userSel FROM scope s " +
                            "JOIN modules m ON $scopeKey WHERE m.module_pkg_name != 'lspd'"
                    it.rawQuery(sql, null).use { c ->
                        while (c.moveToNext()) {
                            scopes
                                .getOrPut(c.getString(0)) { mutableListOf() }
                                .add(c.getString(1) to c.getInt(2))
                        }
                    }
                }
            }

            return Snapshot(
                enabled = enabled.filter { it != "lspd" }.toSet(),
                scopes = scopes,
                autoInclude = autoInclude,
            )
        }
    }

    private fun columns(db: SQLiteDatabase, table: String): List<String>? =
        runCatching {
            db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                val nameIndex = c.getColumnIndex("name")
                if (nameIndex < 0) return null
                buildList { while (c.moveToNext()) add(c.getString(nameIndex)) }
            }
        }
            .onFailure { Log.w(TAG, "schema probe of $table failed: ${it.message}") }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }

    companion object {
        private const val TAG = "VectorManager"
    }
}
