# Analysis contract

## Two badge meanings

**Declaration:** union of possible incoming dispatcher contexts discovered in the analyzed project. This describes callers, not a thread-safety contract. Public/external entry points, unresolved callers, and incomplete analysis add `Unknown`; a closed private call graph can have only known entries. No discovered callers does not prove a dispatcher.

**Call site:** possible dispatcher contexts for executable work in the resolved callee, substituting this call's context into inherited effects. A known `withContext` can therefore produce an `IO` badge even when the incoming context is unknown. Ordinary execution after the call retains the caller's context.

| Situation | Badge |
| --- | --- |
| Closed function called from Main and IO | `Dispatcher Main \| IO` above its declaration |
| No dispatcher evidence | `Dispatcher Unknown` |
| Known Main caller plus unresolved entry paths | `Dispatcher Main \| Unknown` |
| Known IO work plus unresolved work | `Dispatcher IO \| Unknown`; tooltip: coverage unknown |
| Callee inherits a known Main context | `Dispatcher Main` at the call |
| Callee's complete workload is inside `withContext(Dispatchers.IO)` | `Dispatcher IO` at the call |
| Main caller; callee does work on Main and inside `withContext(Dispatchers.IO)` | `Dispatcher Main (partial) \| IO (partial)` at the call |

`(partial)` belongs to an individual known dispatcher: some analyzed workload can execute there and some can execute elsewhere. It is not a percentage. Missing evidence uses `Unknown`, not `(partial)` as a substitute. Tooltips distinguish alternatives across branches from switches within a path.

Count side-effecting argument/context evaluation, conditions, nested calls, and `catch`/`finally` work. Ignore structural entry/return and dispatcher hand-off mechanics, so a pure `withContext(IO) { work() }` wrapper can have complete IO coverage. Full coverage requires all relevant paths and effects to be resolved; unknown work prevents a completeness claim.

## Badge colors

| Dispatcher or state | Color |
| --- | --- |
| Main, including Main.immediate | Red |
| Default | Green |
| IO | Yellow |
| Unknown or analysis problem | Gray |
| Other identified dispatchers: Unconfined, custom or library-provided dispatchers | Purple |

Apply the same palette at declarations and calls. Color each dispatcher entry independently: `Dispatcher Main | IO` has red Main and yellow IO, with a neutral prefix and separator. `(partial)` keeps the associated dispatcher's color. Use theme-aware shades with readable contrast; keep labels and explanations available without relying on color.

Represent analysis failures with gray `Unknown` and an explanatory tooltip. Keep independently valid known entries colored; if a failure invalidates the whole result, replace stale entries with gray `Unknown`.

An identified custom dispatcher can use a label such as `Dispatcher Custom(MyDispatcher)` or `Dispatcher Room` when a verified library summary establishes that identity. A Room API name alone is insufficient. Proven standard dispatchers retain their standard label/color even when supplied through a library; unresolved custom values stay gray. General user-configurable mappings remain later work.

## Model and transfer rules

- Represent a set of `Main`, `IO`, `Default`, `Unconfined`, and identified `Custom(identity, label)` entries plus an independent unknown flag and provenance. Keep `Inherited` symbolic in function summaries until a call context is supplied. Keep the internal no-evidence state distinct from `Unknown` during fixed-point iteration.
- Track execution regions/path alternatives and coverage separately from incoming sets. Display order is Main, IO, Default, Unconfined, custom entries sorted by label and identity, Unknown. Fold `Main.immediate` into Main while preserving that detail in explanations; these are dispatcher identities, not physical-thread guarantees. Custom identities must not be merged solely because their labels match.
- Resolve API identities, aliases, and proven immutable values using the Analysis API. Names such as `IO`, `withContext`, or `viewModelScope` are insufficient evidence.
- Apply context composition by key: an explicit dispatcher replaces the inherited dispatcher; `CoroutineName` or `NonCancellable` alone does not. Unknown context elements that could override the dispatcher preserve uncertainty.
- Enter `withContext` with its merged context and restore the previous context after the block, including subsequent statements and exception paths.
- Model direct suspend calls and bounded interprocedural summaries. Join branches and recursion to a fixed point; add uncertainty when resolution or the analysis budget is incomplete.
- `launch`/`async` use their receiver scope plus explicit context. Model proven default-dispatcher insertion only when no dispatcher is present. Their child bodies do not contribute to the parent's synchronous call-site summary. Unsupported start modes remain uncertain.
- `coroutineScope`/`supervisorScope` inherit the current dispatcher. Calls inside their bodies participate in analysis. Distinguish eagerly executed lambdas from stored callbacks and deferred work.
- Unresolved virtual targets, unidentified custom/injected dispatchers, unknown scope provenance, and missing library summaries contribute `Unknown`. Preserve identified custom dispatchers and their provenance. A custom ViewModel scope prevents assuming Main solely from `viewModelScope`.

## Initial boundary and architecture

Start with Kotlin/JVM project sources, named suspend functions, direct calls, resolved `withContext`, standard builders, simple immutable aliases, and provably identified custom dispatchers. Include a version-scoped Room summary/fixture for an identifiable library-provided dispatcher, alongside standard-dispatcher and unresolved cases. Analyze external suspend implementations only through explicit verified summaries. General higher-order flow, Flow/`flowOn`, DI inference, user-configurable custom dispatcher mapping, and multiplatform analysis are later work; propagate uncertainty where these affect results.

Use a pure dispatcher model, a small Kotlin Analysis API adapter, an incremental project analysis service, and editor providers. Reuse immutable callee summaries for declaration and call-site presentations. Prioritize open files, bound project traversal, and invalidate callers when callees or their dependencies change.

Prototype `DaemonBoundCodeVisionProvider` above declarations and declarative `InlayHintsProvider` at calls. Code Vision is experimental; contain it behind an adapter. Verify the specified palette, including mixed entries; evaluate custom presentation if native APIs cannot supply these colors and record any remaining limitation. Provide separate visibility settings and explanations with evidence or uncertainty reasons. Above-code hints reserve visual height but never change document text or line numbers.

## Baseline and sources

Checked on 2026-10-08. The official stable release page lists Android Studio Quail 4 (2026.1.4). Use this release family as the initial candidate; the bootstrap must record an exact available patch, underlying platform build, bundled Kotlin version, and verified toolchain. Marketing versions alone do not establish API compatibility.

The current Gradle plugin documentation shows 2.19.0 and requires Gradle 9.0.0 or newer. Match the JVM toolchain to the actual IDE platform (Java 21 for IntelliJ Platform 2026.1). Check K2 descriptor requirements against that platform; current guidance removes the explicit compatibility tag from IntelliJ IDEA 2026.2 onward.

- [Android Studio stable releases](https://developer.android.com/studio/releases/)
- [Android Studio plugin targeting](https://plugins.jetbrains.com/docs/intellij/android-studio.html)
- [IntelliJ Platform Gradle Plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)
- [Platform build and Java compatibility](https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html)
- [K2 compatibility declaration](https://kotlin.github.io/analysis-api/declaring-k2-compatibility.html)
- [Analysis API lifetimes](https://kotlin.github.io/analysis-api/fundamentals.html)
- [Editor inlay APIs](https://plugins.jetbrains.com/docs/intellij/inlay-hints.html)
- [Coroutine contexts and dispatchers](https://kotlinlang.org/docs/coroutine-context-and-dispatchers.html)
- [withContext semantics](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-context.html)
- [Configurable ViewModel scopes](https://developer.android.com/reference/androidx/lifecycle/ViewModel)
- [Room dispatcher and executor configuration](https://developer.android.com/reference/androidx/room/RoomDatabase.Builder)
