# Source notes

This branch carries the full manager source that produced the releases on this repository.

- Upstream: JingMatrix/Vector at `ddeed8ca1ffe` (2026-10-01), the whole tree — daemon, manager, manager-ui, zygisk and native parts.
- Local changes live under `manager/` and `manager-ui/`:
  - `root/` — root-mode daemon access (CLI client, read-only config database fallback, root shell), plus `ScopeDiagnosis` for reading the daemon's own refusal lines out of its logs.
  - `AppLogStore.kt` / `ui/screens/applog/` — the manager's own log, kept in memory and on disk, with its screen and filters.
  - `ui/screens/appscopes/` — the per-app reverse view: every module's scope folded into an app-to-modules map, drawn with the module list's row and header components.
  - Scope writes in root mode are verified by reading the scope back from the daemon, because the daemon's CLI reports success even when the database write inside it failed.
- `external/` entries are git submodules (upstream's own); the manager APK builds without them.

Build: JDK 21, Android SDK (compileSdk 37), `./gradlew :manager:assembleRelease`. Tags `v1.4`…`v1.6` on this branch match the releases here; the APK of each is the release asset of the same tag.
