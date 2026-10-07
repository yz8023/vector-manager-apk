package org.matrix.vector.manager.root

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.vector.manager.di.ServiceLocator

/**
 * Turns root access into a working manager when the daemon's binder never arrives.
 *
 * The handshake that normally ends the splash — the framework pushing its binder through
 * `Constants.setBinder` — is gated on the manager's signature matching the framework's. A manager
 * built elsewhere fails that gate, waits out the splash ceiling, and then draws every screen from
 * a null service: "not activated", forever, on a device where the framework is plainly alive.
 *
 * This object is the way out. Started alongside the splash's other prefetches, it probes for the
 * one door the signature check cannot close — `su` — and checks whether the framework can be
 * *read*: its CLI answered, or its database copied out. Either is enough to serve the manager's
 * whole surface, via [RootManagerService], so when the daemon's binder has failed to appear by the
 * end of the splash, [activate] binds the fallback instead and the manager behaves as though a
 * daemon had answered — read-write where the CLI reaches, read-only where only the database does.
 *
 * The phase is a flow because the shell banner reads it: the difference between "connected to the
 * daemon" and "reading through root" is one a user has to be able to see, since the two modes
 * differ in what writes will do.
 */
object RootFallback {

    enum class Phase {
        /** Not started. */
        IDLE,

        /** Probing for root and a readable framework. */
        PREPARING,

        /** The fallback service is bound and answering. */
        ACTIVE,

        /** Root is refused, or the framework is not there to read. */
        UNAVAILABLE,
    }

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    @Volatile private var service: RootManagerService? = null

    /**
     * The CLI behind the active fallback, or null when root mode is off.
     *
     * Read for diagnosis only — the CLI's [RootCli.lastScopeError] carries the daemon's own
     * refusal text, which the binder surface flattens into a bare `false`.
     */
    fun activeCli(): RootCli? = service?.cli

    /**
     * Starts the probe in the background, before anyone needs the answer.
     *
     * Called from the prefetch window the splash provides. Deliberately does nothing when a
     * daemon binder is already in place: a manager the framework has accepted must never prompt
     * for root, because it has nothing to ask root for.
     */
    fun prepare(context: Context) {
        if (_phase.value != Phase.IDLE || ServiceLocator.service.value != null) return
        _phase.value = Phase.PREPARING
        ServiceLocator.appScope.launch(Dispatchers.IO) { probe(context) }
    }

    /**
     * Binds the fallback, if the probe found something to serve.
     *
     * Runs the probe itself when [prepare] has not finished (or did not run), so a call from the
     * splash gate is complete in itself. Returns whether root mode is now on.
     */
    suspend fun activate(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            val fallback = probe(context)
            if (fallback == null) {
                _phase.value = Phase.UNAVAILABLE
                false
            } else {
                ServiceLocator.bind(fallback)
                _phase.value = Phase.ACTIVE
                true
            }
        }

    /** The probe, at most once per process. */
    private fun probe(context: Context): RootManagerService? {
        service?.let { return it }
        synchronized(this) {
            service?.let { return it }
            if (!RootShell.ensureGranted()) return null
            val cli = RootCli()
            // Either door is enough: the CLI is the good one, the database copy the tolerant one.
            val usable = cli.probe() || RootDatabase(context.applicationContext).read() != null
            if (!usable) return null
            return RootManagerService(context.applicationContext, cli).also { service = it }
        }
    }
}
