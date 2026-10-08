# Dispatcher analyzer demo

Open `samples/demo` as a separate Gradle project in Android Studio after importing the repository plugin into a sandbox IDE. The demo uses Kotlin 2.4.0, Java 25, and kotlinx.coroutines 1.11.0. Its functions are inspection fixtures and do not need to run.

Enable call-site badges under Settings > Tools > Dispatcher Analyzer to inspect call effects; they are off by default. Declaration badges have no plugin-specific off switch.

Automatic analysis runs after typing pauses. Disable it in the same settings page to use Analyze project in the Dispatcher Analyzer side panel. Source changes invalidate results until the next manual run.

The pinned wrapper supports `./gradlew classes` with JDK 25. Wait for Android Studio's Gradle import to finish before inspecting badges.

Expected badges:

- `ioWrapper`: its private declaration has incoming `Main | Default`; each call has the `IO` execution effect.
- `mixedWorkload`: its call inside the Main launch has `Main (partial) | IO (partial)`.
- `injectedDispatcherWork`: the call includes gray `Unknown` because the injected dispatcher identity is unresolved.
- `databaseWork`: the call shows an identified custom dispatcher for the `databaseDispatcher` value, with a stable identity distinct from other custom dispatchers.
- `externallyCallableWork`: its public declaration includes `Unknown` for possible external callers.

`launchExamples` starts no work unless called. Its explicit launch contexts give the private `ioWrapper` two closed, known incoming paths.
