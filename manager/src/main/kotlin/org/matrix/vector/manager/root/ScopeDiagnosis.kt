package org.matrix.vector.manager.root

/**
 * Reads the daemon's own refusal lines out of its log files, which is where the reason a scope
 * write was refused is written down.
 *
 * The daemon logs every scope refusal as `"<module> fixes its scope; refusing to add <apps>"`
 * (three entry points converge on that line: the manager's write, the socket CLI, and a module's
 * own `requestScope`), and rotates ten parts under `/data/adb/lspd/log` with the previous run's
 * set moved to `log.old`. Only root can read any of it — the daemon chmods the directory 0700 —
 * so this runs through [RootShell] and answers null when root is absent, refused, or the files
 * are simply not there. A null is "unknown", not "refused for no reason": the scope editor's
 * refusal dialog shows the manager-side diagnosis either way and the excerpt only when it exists.
 *
 * grep's `-h` drops the file names, and both directories are probed in one command so the
 * previous run's refusals are found even when the current run has not logged one yet.
 */
internal object ScopeDiagnosis {

    private const val LOG_DIR = "/data/adb/lspd/log"
    private const val OLD_LOG_DIR = "/data/adb/lspd/log.old"

    /**
     * The most recent daemon refusal lines naming [modulePackage], oldest first, or null when
     * nothing was found or root could not read the logs. Lines are capped by [limit]; each line
     * already carries the daemon's own timestamp.
     */
    fun refusalLines(modulePackage: String, limit: Int = 8): String? {
        if (!RootShell.ensureGranted()) return null
        // The CLI's own error from the refused write, when root mode made the write: the daemon
        // names the module, what it claims and what it will not take, which is the whole answer.
        val cliError =
            runCatching { RootFallback.activeCli()?.lastScopeError }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { "cli: $it" }
        // The pattern is fixed in the daemon; the module package is the only interpolation, and it
        // is a package name — but it still goes through the shell, so it is single-quoted after
        // any single quote is stripped rather than trusted.
        // The refusal the staticScope check writes is one failure; a write that stayed inside the
        // claim and still failed comes back as `Failed to set scope` with a stack trace beside it
        // — the database refusing the write is invisible to the manager otherwise, because the
        // binder answer is the same bare `false` either way.
        val safe = modulePackage.replace("'", "")
        val shell =
            "grep -h 'refusing\\|fixes its scope\\|Failed to set scope\\|Failed to prune' " +
                "'$LOG_DIR'/modules_*.log '$LOG_DIR'/verbose_*.log " +
                "'$OLD_LOG_DIR'/modules_*.log '$OLD_LOG_DIR'/verbose_*.log 2>/dev/null " +
                "| grep '$safe' | tail -n $limit"
        val out = runCatching { RootShell.su(shell, timeoutMs = 10_000).out }.getOrNull()
        val logged = out?.trim()?.takeIf { it.isNotEmpty() }
        return listOfNotNull(cliError, logged).joinToString("\n").takeIf { it.isNotEmpty() }
    }
}
