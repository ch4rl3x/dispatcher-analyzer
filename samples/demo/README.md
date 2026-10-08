# Dispatcher analyzer demo

Open `samples/demo` as a separate Gradle project in Android Studio after importing the repository plugin into a sandbox IDE. The demo uses Kotlin 2.4.0, Java 25, and kotlinx.coroutines 1.11.0. Its functions are inspection fixtures and do not need to run.

Under Settings > Tools > Dispatcher Analyzer, choose All calls, Only calls that set a dispatcher, or Do not show (default). In the filtered mode, calls to `ioWrapper` and `mixedWorkload` retain their badges while inherited `delay` calls disappear. Declaration badges have no plugin-specific off switch.

Automatic analysis runs after typing pauses. Disable it in the same settings page to use Analyze project in the Dispatcher Analyzer side panel. Source changes invalidate results until the next manual run.

The pinned wrapper supports `./gradlew classes` with JDK 25. Wait for Android Studio's Gradle import to finish before inspecting badges.

Expected badges:

- `ioWrapper`: its private declaration has incoming `Main | Default`; each call has the `IO` execution effect.
- `mixedWorkload`: its call inside the Main launch has `Main (partial) | IO (partial)`.
- `injectedDispatcherWork`: the call includes gray `Unknown` because the injected dispatcher identity is unresolved.
- `databaseWork`: the call shows an identified custom dispatcher for the `databaseDispatcher` value, with a stable identity distinct from other custom dispatchers.
- `externallyCallableWork`: its Main caller contributes Main; public visibility adds Unknown. Remove its project call to hide the declaration badge while its `delay(1)` call retains gray Unknown. This README reference does not count as a call.

Click a colored dispatcher name to open its source expression. The IO declaration entry for `readFromDisk` has multiple contributing IO expressions and offers a chooser; Main navigates directly to its own launch context.

`launchExamples` starts no work unless called. Its explicit launch contexts give the private `ioWrapper` two closed, known incoming paths.
