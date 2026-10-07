package org.matrix.vector.manager.root

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.matrix.vector.ipc.DeviceUser
import org.matrix.vector.ipc.IFrameworkInstallReceiver
import org.matrix.vector.ipc.IManagerService
import org.matrix.vector.ipc.ModuleLoadFailure
import org.matrix.vector.ipc.ScopeEntry
import rikka.parcelablelist.ParcelableListSlice

/**
 * The daemon's binder surface, answered in this process from what root can see.
 *
 * Everything the manager asks a daemon for has an answer here, and the answers come from the same
 * places the daemon itself would read: the CLI for configuration and writes, the package manager
 * for installed packages, `su` for anything under `/data/adb`. A screen cannot tell the difference
 * by looking, which is the point — the repositories and view models were built against a daemon
 * that answers, so the fallback has to be one that answers.
 *
 * What is genuinely out of reach is stated rather than faked: include-new-apps writes touch a
 * table the CLI does not expose and are refused, bug reports carry the logs but none of the
 * daemon's own diagnostics, and cross-user activity starts happen on this user. A refused write
 * snaps its switch back; a silently no-op'd one would lie.
 *
 * Calls arrive on whatever thread [org.matrix.vector.manager.ipc.DaemonClient] dispatched them on
 * (the fallback is in-process, so there is no binder hop), and every `su` costs a root shell, so
 * answers that a screen asks repeatedly are cached for a few seconds — long enough that a refresh
 * does not triple its own cost, short enough that a toggle made elsewhere is seen on the next look.
 */
class RootManagerService internal constructor(
    private val context: Context,
    internal val cli: RootCli,
) : IManagerService.Stub() {

    private val db = RootDatabase(context)

    /** Framework availability probed once; every status call reads this instead of re-running su. */
    private val frameworkPresent: Boolean by lazy { probeFramework() }

    private val logDir = "/data/adb/lspd/log"

    // ---- identity -------------------------------------------------------------------------------

    override fun getProtocolVersion(): Int = IManagerService.PROTOCOL_VERSION

    override fun getFrameworkVersionCode(): Long = cli.frameworkVersionCode()

    override fun getFrameworkVersionName(): String? = cli.frameworkVersionName()

    override fun getBuildStamp(): String? = null

    override fun getLibxposedApiVersion(): Int = cli.libxposedApiVersion()

    override fun getRootImplementation(): Int = detectRootImplementation()

    // ---- framework health -------------------------------------------------------------------------

    override fun isSystemServerAttached(): Boolean = frameworkPresent

    override fun isSepolicyLoaded(): Boolean = frameworkPresent

    override fun isDex2OatInliningDisabled(): Boolean = false

    override fun getDex2OatWrapperState(): Int = 0

    // ---- modules ----------------------------------------------------------------------------------

    override fun getEnabledModules(): MutableList<String> {
        if (cli.alive) {
            return cli.modules().filter { it.enabled }.map { it.packageName }.toMutableList()
        }
        return dbSnapshot()?.enabled?.toMutableList() ?: mutableListOf()
    }

    override fun setModuleEnabled(packageName: String?, enabled: Boolean): Boolean {
        if (packageName == null) return false
        return cli.setModuleEnabled(packageName, enabled)
    }

    override fun getModuleScope(packageName: String?): MutableList<ScopeEntry>? {
        if (packageName == null) return null
        if (cli.alive) return cli.moduleScope(packageName)?.toMutableList()
        val snapshot = dbSnapshot() ?: return mutableListOf()
        // Null means "unknown module"; the empty list means "known, nothing scoped". The daemon's
        // distinction, kept.
        return snapshot.scopes[packageName]
            ?.map { (app, user) ->
                ScopeEntry().apply {
                    this.packageName = app
                    userId = user
                }
            }
            ?.toMutableList()
    }

    override fun setModuleScope(packageName: String?, scope: MutableList<ScopeEntry>?): Boolean {
        if (packageName == null || scope == null) return false
        return cli.setModuleScope(packageName, scope)
    }

    override fun getModuleLoadFailures(): MutableList<ModuleLoadFailure> = mutableListOf()

    override fun getIncludeNewApps(packageName: String?): Boolean =
        if (packageName == null) false else packageName in (dbSnapshot()?.autoInclude ?: emptySet())

    override fun setIncludeNewApps(packageName: String?, enable: Boolean): Boolean = false

    // ---- preferences --------------------------------------------------------------------------------

    override fun isStatusNotificationEnabled(): Boolean =
        cli.configGet("status-notification") ?: cli.statusNotificationEnabled()

    override fun setStatusNotificationEnabled(enabled: Boolean) {
        cli.configSet("status-notification", enabled)
    }

    override fun isVerboseLogEnabled(): Boolean = cli.configGet("verbose-log") ?: false

    override fun setVerboseLogEnabled(enabled: Boolean) {
        cli.configSet("verbose-log", enabled)
    }

    override fun isForcedLauncherIcons(): Boolean =
        runCatching {
            settingsGet("show_hidden_icon_apps_enabled")?.let { it == "1" } ?: true
        }
            .getOrDefault(true)

    override fun setForcedLauncherIcons(force: Boolean) {
        RootShell.su(
            "settings put global show_hidden_icon_apps_enabled ${if (force) 1 else 0}",
        )
    }

    // ---- logs ----------------------------------------------------------------------------------------

    override fun getLiveLogPart(verbose: Boolean): ParcelFileDescriptor? = null

    override fun getLogParts(verbose: Boolean): MutableList<String> {
        val prefix = if (verbose) "verbose_" else "modules_"
        return RootShell.su("ls -1 '$logDir' 2>/dev/null")
            .out
            .lineSequence()
            .filter { it.startsWith(prefix) && it.endsWith(".log") }
            .sorted()
            .toMutableList()
    }

    override fun getLogPart(verbose: Boolean, name: String?): ParcelFileDescriptor? {
        val part = name ?: return null
        if (part !in getLogParts(verbose)) return null
        val local = pullFile("$logDir/$part") ?: return null
        return runCatching {
            ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY)
        }
            .getOrNull()
    }

    override fun startNewLogPart(verbose: Boolean) {
        // The daemon rotates its own log parts; nothing for a reader to do here.
    }

    override fun writeBugReport(zipFd: ParcelFileDescriptor?) {
        if (zipFd == null) return
        runCatching {
            ZipOutputStream(java.io.FileOutputStream(zipFd.fileDescriptor)).use { os ->
                os.setLevel(Deflater.BEST_COMPRESSION)
                fun add(name: String, content: ByteArray) {
                    if (content.isEmpty()) return
                    os.putNextEntry(ZipEntry(name))
                    os.write(content)
                    os.closeEntry()
                }
                for (verbose in listOf(false, true)) {
                    for (part in getLogParts(verbose)) {
                        val bytes = RootShell.su("cat '$logDir/$part'").out.toByteArray()
                        add("${if (verbose) "verbose" else "modules"}/$part", bytes)
                    }
                }
                // The daemon's own report carries its version line from BuildConfig; ours carries
                // whatever the CLI answered with, which names the framework the same way.
                add(
                    "version.txt",
                    "framework=${getFrameworkVersionName()} code=${getFrameworkVersionCode()}"
                        .toByteArray(),
                )
            }
        }
            .onFailure { Log.w(TAG, "bug report export failed", it) }
    }

    // ---- packages ------------------------------------------------------------------------------------

    override fun getInstalledPackagesFromAllUsers(
        flags: Int,
        filterNoProcess: Boolean,
    ): ParcelableListSlice<PackageInfo> {
        val packages =
            runCatching { context.packageManager.getInstalledPackages(flags) }
                .getOrElse {
                    runCatching { context.packageManager.getInstalledPackages(0) }
                        .getOrDefault(mutableListOf())
                }
        return ParcelableListSlice(packages)
    }

    override fun queryIntentActivitiesAsUser(
        intent: Intent?,
        flags: Int,
        userId: Int,
    ): ParcelableListSlice<ResolveInfo> {
        if (intent == null) return ParcelableListSlice(emptyList())
        val resolves =
            runCatching { context.packageManager.queryIntentActivities(intent, flags) }
                .getOrDefault(emptyList())
        return ParcelableListSlice(resolves.toMutableList())
    }

    override fun getUsers(): MutableList<DeviceUser> {
        val users = parseUsers()
        if (users.isNotEmpty()) return users.toMutableList()
        return mutableListOf(
            DeviceUser().apply {
                id = 0
                name = "System"
            }
        )
    }

    override fun startActivityAsUser(intent: Intent?, userId: Int, noUserSwitch: Boolean): Int {
        if (intent == null) return -1
        return runCatching {
            context.startActivity(
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            0
        }
            .getOrDefault(-1)
    }

    override fun forceStopPackage(packageName: String?, userId: Int) {
        if (packageName == null) return
        RootShell.su("am force-stop '$packageName'")
    }

    override fun uninstallPackage(packageName: String?, userId: Int): Boolean {
        if (packageName == null) return false
        return RootShell.su("pm uninstall --user $userId '$packageName'").ok
    }

    override fun optimizePackage(packageName: String?): Boolean {
        if (packageName == null) return false
        return RootShell.su("cmd package compile -m speed -f '$packageName'").ok
    }

    // ---- power -----------------------------------------------------------------------------------------

    override fun softReboot() {
        RootShell.su("setprop ctl.restart zygote")
    }

    override fun reboot() {
        RootShell.su("reboot")
    }

    // ---- self-install ------------------------------------------------------------------------------------

    override fun getManagerApk(): ParcelFileDescriptor? =
        runCatching {
            val path = context.applicationInfo.sourceDir ?: return@runCatching null
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        }
            .getOrNull()

    override fun installFrameworkZip(zipPath: String?, receiver: IFrameworkInstallReceiver?) {
        // Installing a framework zip means running a Magisk module script, whose environment only
        // the root manager provides. Reporting the install as unavailable is honest and renders
        // correctly; a half-run script could leave the device bootlooped.
        receiver?.onFinished(IFrameworkInstallReceiver.INSTALL_NO_ROOT)
    }

    // ---- internals ----------------------------------------------------------------------------------------

    /**
     * One db snapshot, remembered for a few seconds. Reads go through `su` and a file copy, which
     * is far too much to do for every row a screen renders.
     */
    private fun dbSnapshot(): RootDatabase.Snapshot? {
        val now = System.currentTimeMillis()
        snapshot?.let { (snap, at) -> if (now - at < SNAPSHOT_TTL_MS) return snap }
        val fresh = db.read() ?: return snapshot?.first
        snapshot = fresh to now
        return fresh
    }

    @Volatile private var snapshot: Pair<RootDatabase.Snapshot, Long>? = null

    private fun probeFramework(): Boolean = cli.probe() || dbSnapshot() != null

    private fun detectRootImplementation(): Int {
        val which = RootShell.su("which magisk ksud apd 2>/dev/null")
        val found = which.out.trim()
        return when {
            found.contains("magisk") -> IManagerService.ROOT_MAGISK
            found.contains("ksud") -> IManagerService.ROOT_KERNELSU
            found.contains("apd") -> IManagerService.ROOT_APATCH
            else -> IManagerService.ROOT_UNKNOWN
        }
    }

    /** `settings get global <key>` through root, returning the raw value or null when unset. */
    private fun settingsGet(key: String): String? {
        val out = RootShell.su("settings get global '$key'").out.trim()
        return out.ifEmpty { null }?.takeIf { it != "null" }
    }

    private fun parseUsers(): List<DeviceUser> {
        val out = RootShell.su("pm list users 2>/dev/null").out
        val regex = Regex("""UserInfo\{(\d+):([^:}]+)""")
        return regex
            .findAll(out)
            .map { match ->
                DeviceUser().apply {
                    id = match.groupValues[1].toInt()
                    name = match.groupValues[2]
                }
            }
            .toList()
    }

    private fun pullFile(remotePath: String): File? {
        val local = File(context.cacheDir, "root-pull-${remotePath.hashCode().toUInt()}")
        val result =
            RootShell.su("cat '$remotePath' > '${local.absolutePath}' && chmod 644 '${local.absolutePath}'")
        return local.takeIf { result.ok && it.exists() && it.length() > 0 }
    }

    companion object {
        private const val TAG = "VectorManager"
        private const val SNAPSHOT_TTL_MS = 5_000L
    }
}
