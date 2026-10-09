# Dispatcher analyzer demo

Open `samples/demo` as a separate Gradle project in Android Studio after importing the repository plugin into a sandbox IDE. The demo uses Kotlin 2.4.0, Java 25, and kotlinx.coroutines 1.11.0. Its functions are inspection fixtures and do not need to run.

Under Settings > Tools > Dispatcher Analyzer, choose All calls, Only calls that set a dispatcher (default), or Do not show. In the filtered mode, calls to `ioWrapper` and `mixedWorkload` retain their badges while inherited `delay` calls disappear. Optionally enable Only in the function containing the caret to restrict call badges to the current function. Declaration badges have no plugin-specific off switch.

Analysis runs automatically after import and after saving changes, including IDE autosave. Typing invalidates results until the next save. The disposable cache is stored in `build/dispatcher-analyzer/`; there is no analysis side panel. The Reanalyze file icon in the editor toolbar saves and force-refreshes the active file.

The pinned wrapper supports `./gradlew classes` with JDK 25. Wait for Android Studio's Gradle import to finish before inspecting badges.

Expected badges:

- `ioWrapper`: its private declaration has incoming `Main | Default`; each call has the `IO` execution effect.
- `mixedWorkload`: its call inside the Main launch has `Main (partial) | IO (partial)`.
- `injectedDispatcherWork`: the call includes gray `Unknown` because the injected dispatcher identity is unresolved.
- `databaseWork`: the call shows an identified custom dispatcher for the `databaseDispatcher` value, with a stable identity distinct from other custom dispatchers.
- `externallyCallableWork`: its project caller contributes Main only; public visibility does not add Unknown. Remove its project call to hide the declaration badge while its `delay(1)` call retains gray Unknown. This README reference does not count as a call.

Click a colored dispatcher name to open its source expression. The IO declaration entry for `readFromDisk` has multiple contributing IO expressions and offers a chooser; Main navigates directly to its own launch context.

`launchExamples` starts no work unless called. Its explicit launch contexts give the private `ioWrapper` two closed, known incoming paths.
