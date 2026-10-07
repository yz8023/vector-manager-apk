package org.matrix.vector.manager.root

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.matrix.vector.ipc.ScopeEntry
import org.matrix.vector.manager.logW

/**
 * Speaks to the Vector daemon through its own root CLI at `/data/adb/lspd/cli`.
 *
 * The daemon ships a command-line surface that runs as root, authenticates to its local socket
 * with a token compiled into the daemon itself, and executes every operation through the same
 * `ModuleDatabase`/`PreferenceStore` code the binder service uses. Talking to *that* means a
 * manager whose signature the framework refuses can still read and write the real configuration
 * with none of the hazards of editing `modules_config.db` behind a running daemon.
 *
 * Every call is one `su` spawn of the CLI, so each costs a root shell plus a JVM start — roughly a
 * second. Nothing here caches; that is the service layer's job, because only it knows what a
 * screen will ask twice.
 */
internal class RootCli(private val cliPath: String = "/data/adb/lspd/cli") {

    data class Response(val success: Boolean, val data: Any?, val error: String?)

    data class ModuleRow(val packageName: String, val uid: Int, val enabled: Boolean)

    private val gson = Gson()

    /** True once `cli --json status` has answered successfully this process. */
    var alive: Boolean = false

    /**
     * The CLI's own error text from the last failed [setModuleScope], or null.
     *
     * The daemon refuses an out-of-static-scope target with a message that names the module, the
     * packages it claims and the ones it will not take
     * (`"… fixes its scope in module.prop, so X cannot be added. It claims: …"`). That text is
     * the whole diagnosis, and the CLI already carries it back — it is kept here rather than
     * discarded so the refusal dialog can quote the daemon instead of guessing.
     */
    @Volatile var lastScopeError: String? = null
        private set

    /** Values from `status`, kept because several binder calls read one field of it each. */
    var statusData: Map<String, Any?> = emptyMap()
        private set

    /**
     * Probes the CLI with `status`. A failure here is the difference between "this framework has a
     * CLI" and "fall back to reading the database behind the daemon's back", so the answer is kept.
     */
    fun probe(): Boolean {
        val response = invoke("status")
        if (response?.success == true) {
            alive = true
            (response.data as? Map<*, *>)?.let { statusData = it as Map<String, Any?> }
        }
        return alive
    }

    /** Runs one CLI command with `--json` and parses the response envelope. Null: CLI not usable. */
    fun invoke(vararg args: String): Response? {
        val quoted = (listOf("--json") + args.toList()).joinToString(" ") { shellQuote(it) }
        val result = RootShell.su("$cliPath $quoted", timeoutMs = 20_000)
        if (!result.ok) {
            Log.w(TAG, "cli ${args.firstOrNull()} failed: code=${result.exitCode} ${result.err.take(200)}")
            return null
        }
        return parse(result.out)
    }

    private fun parse(raw: String): Response? =
        runCatching {
            // The CLI prints exactly one JSON document on stdout when --json is given.
            val json = gson.fromJson(raw.trim(), JsonObject::class.java)
            Response(
                success = json.get("success")?.asBoolean ?: false,
                data = gson.fromJson(json.get("data"), Any::class.java),
                error = json.get("error")?.takeIf { !it.isJsonNull }?.asString,
            )
        }
            .onFailure { Log.w(TAG, "cli output unparsable: ${it.message}") }
            .getOrNull()

    // ---- modules -------------------------------------------------------------------------------

    /** `modules ls`: every module the daemon sees, with its configured state. */
    fun modules(): List<ModuleRow> {
        val response = invoke("modules", "ls") ?: return emptyList()
        if (!response.success) return emptyList()
        return (response.data as? List<*>)
            .orEmpty()
            .mapNotNull { row ->
                val map = row as? Map<*, *> ?: return@mapNotNull null
                val pkg = map["PACKAGE"] as? String ?: return@mapNotNull null
                ModuleRow(
                    packageName = pkg,
                    uid = (map["UID"] as? Double)?.toInt() ?: 0,
                    enabled = map["STATUS"] == "enabled",
                )
            }
    }

    fun setModuleEnabled(packageName: String, enabled: Boolean): Boolean {
        val verb = if (enabled) "enable" else "disable"
        val response = invoke("modules", verb, packageName) ?: return false
        if (!response.success) return false
        // The daemon answers with Enabled/Failed lists rather than one boolean; our package has to
        // be in the success list, because a batch of one that failed still reports success=true.
        val data = response.data as? Map<*, *> ?: return false
        val good = (data[if (enabled) "Enabled" else "Disabled"] as? List<*>) ?: emptyList<Any>()
        return packageName in good
    }

    // ---- scope ---------------------------------------------------------------------------------

    /** `scope ls <pkg>`: the module's scope, or null when the daemon does not know the module. */
    fun moduleScope(packageName: String): List<ScopeEntry>? {
        val response = invoke("scope", "ls", packageName) ?: return null
        if (!response.success) return null
        return (response.data as? List<*>)
            .orEmpty()
            .mapNotNull { row ->
                val map = row as? Map<*, *> ?: return@mapNotNull null
                ScopeEntry().apply {
                    this.packageName = map["APP_PACKAGE"] as? String ?: return@mapNotNull null
                    userId = (map["USER_ID"] as? Double)?.toInt() ?: 0
                }
            }
    }

    /**
     * `scope set`, or `scope rm` of every current entry when the new scope is empty — the CLI
     * refuses an overwrite with no targets, so clearing goes through removal one app at a time.
     */
    fun setModuleScope(packageName: String, scope: List<ScopeEntry>): Boolean {
        lastScopeError = null
        if (scope.isNotEmpty()) {
            val targets = scope.joinToString(" ") { "${it.packageName}/${it.userId}" }
            val response =
                invoke("scope", "set", packageName, *targets.split(" ").toTypedArray())
            if (response?.success == true) {
                // Verify before believing it. The daemon's CLI reports success whenever the
                // command ran, whether or not the database write inside it took — the handler
                // ignores the boolean the database returns — so "success" has reached back as a
                // lie at least twice: for a module the daemon does not have in its table (the
                // write dies on the missing row, the success message does not notice), and for
                // any database failure the CLI swallows the same way. Both read back as the old
                // scope, so the answer is to read back: what the daemon holds now is compared
                // against what was asked, and a mismatch is a refusal like any other.
                val onRecord = moduleScope(packageName)
                if (onRecord == null) {
                    lastScopeError =
                        "the daemon answered success, but it does not have $packageName in " +
                            "its module table at all — the write never landed"
                    logW("scope: write to $packageName did not land: $lastScopeError")
                    return false
                }
                val recorded = onRecord.map { "${it.packageName}/${it.userId}" }.toSet()
                val wanted =
                    scope.map {
                        "${it.packageName}/${if (it.packageName == "system") 0 else it.userId}"
                    }.toSet()
                if (recorded != wanted) {
                    lastScopeError =
                        "the daemon answered success, but the scope on record is: " +
                            (if (recorded.isEmpty()) "(empty)" else recorded.sorted().joinToString())
                    logW("scope: write to $packageName did not land: $lastScopeError")
                    return false
                }
                return true
            }
            lastScopeError =
                response?.error ?: "cli scope set returned no answer (root or daemon absent)"
            // Into the manager's own log with the scope prefix, so the app log's Scope filter
            // carries the daemon's answer and the refusal dialog's quote can be checked against
            // the record rather than trusted from memory.
            logW("scope: cli set failed for $packageName: $lastScopeError")
            return false
        }
        val current = moduleScope(packageName) ?: return false
        if (current.isEmpty()) return true
        val targets = current.joinToString(" ") { "${it.packageName}/${it.userId}" }
        return invoke("scope", "rm", packageName, *targets.split(" ").toTypedArray())?.success ==
            true
    }

    // ---- config --------------------------------------------------------------------------------

    fun configGet(key: String): Boolean? {
        val response = invoke("config", "get", key) ?: return null
        val data = response.data as? Map<*, *> ?: return null
        return (data["VALUE"] as? Boolean)
    }

    fun configSet(key: String, value: Boolean): Boolean {
        val response = invoke("config", "set", key, value.toString()) ?: return false
        return response.success
    }

    /** The framework version line, straight from the daemon's own BuildConfig. */
    fun frameworkVersionName(): String? = statusData["Framework Version"] as? String

    fun frameworkVersionCode(): Long =
        (statusData["Version Code"] as? Double)?.toLong()
            ?: (statusData["Version Code"] as? Long)
            ?: 0L

    fun libxposedApiVersion(): Int =
        (statusData["API Version"] as? Double)?.toInt() ?: (statusData["API Version"] as? Long)?.toInt() ?: 0

    fun statusNotificationEnabled(): Boolean =
        (statusData["Status Notification"] as? Boolean) ?: true

    private fun shellQuote(s: String): String =
        if (s.isEmpty() || s.any { it in " \t\"'\\$;&|<>(){}*?[]#~" }) "'${s.replace("'", "'\\''")}'" else s

    companion object {
        private const val TAG = "VectorManager"
    }
}
