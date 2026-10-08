# Analysis contract

## Two badge meanings

**Declaration:** union of possible incoming dispatcher contexts discovered in the analyzed project. Show a badge only when project code contains a call to the function. Public visibility or a README reference alone is insufficient; uncalled functions have no declaration badge. When calls exist, public/external entry points, unresolved callers, and incomplete analysis add `Unknown`; a closed private call graph can have only known entries. This describes callers, not a thread-safety contract.

**Call site:** possible dispatcher contexts for executable work in the resolved callee, substituting this call's context into inherited effects. A known `withContext` can therefore produce an `IO` badge even when the incoming context is unknown. Ordinary execution after the call retains the caller's context.

Calls inside an unused function still receive call-site badges when enabled. For example, an uncalled `externallyCallableWork` has no declaration badge, while its `delay(1)` call displays `Dispatcher Unknown` because the inherited dispatcher is unresolved. A callable reference alone does not create a declaration badge; when actual calls also exist, escaping references can add uncertainty.

Declaration badges begin with `called within Dispatcher `; call-site badges begin with `Dispatcher `. The prefix is neutral; each dispatcher retains its own color.

Call-site badges appear immediately after the call's argument list. For trailing lambdas, place the badge before the lambda, never after its closing brace. If parentheses are omitted, anchor after the callee name or its explicit type arguments. Calls inside the lambda retain their own badges; declaration badges remain above functions.

Omit redundant badges on `withContext` calls whose context directly references a standard dispatcher, including `Dispatchers.Main.immediate` and parenthesized references. Resolve these references by symbol identity, including import aliases. Value aliases and unresolved dispatcher variables retain badges; coroutine builders such as `launch` and `async` have no call badges. This display rule applies in every call-site mode and does not change execution effects, incoming contexts, or badges on ordinary calls to functions that switch dispatchers internally.

Only a colored dispatcher name is clickable. It navigates to the project expression that selected that dispatcher, such as `Dispatchers.Main` in a builder context, a `withContext` argument, an immutable alias initializer, or an identified dispatcher factory. A proven implicit default points to its coroutine builder. Prefixes, separators, `(partial)`, and Unknown have no navigation action. When the same dispatcher has several contributing origins, clicking that name opens a chooser containing only those origins. Declaration origins follow incoming contexts; call-site origins follow execution effects. Context overrides discard replaced origins. Outdated snapshots cannot navigate.

Call-site badges use a persistent enum dropdown: **All calls**, **Only calls that set a dispatcher** (default), or **Do not show**. Explicit existing enabled/disabled preferences migrate to All calls/Do not show. Declaration badges have no plugin-specific off switch but require project call evidence. IDE-wide inlay controls still apply. Suspend expect declarations and their calls are outside the current scope and receive no badges.

An optional **Only in the function containing the caret** setting is off by default and available only when call badges are enabled. It limits call badges to the innermost named function containing the primary caret; outside a function no call badges are shown. Nested named functions use their own ownership. Caret movement refreshes presentation without running analysis. Declaration badges still follow their usual call-evidence rule.

The filtered call-site mode uses a separate semantic flag for synchronous dispatcher selection inside the callee. A supported synchronous callee that performs a `withContext` dispatcher switch qualifies; direct standard-dispatcher calls still follow the redundancy rule above. `delay` merely inherits its caller's dispatcher and does not qualify. Explicit unresolved dispatcher values remain visible with Unknown. `NonCancellable` or `CoroutineName` alone does not select a dispatcher, and dispatcher choices confined to asynchronous child bodies do not qualify the parent. This flag is independent of the final dispatcher union and `(partial)` coverage. Pending results cannot establish dispatcher selection and are omitted in filtered mode.

Analysis always runs automatically after project import and saved source changes, including IDE autosave. There is no manual mode or analysis side panel. The editor toolbar’s Reanalyze file action saves the current document and forces its graph and affected dependencies to be regenerated, even when its content hash is unchanged. Typing cancels superseded work and invalidates results without starting analysis. Outdated results with previously established call evidence are gray Unknown while their file remains unchanged; edited or unanalyzed positions are omitted until a current snapshot is available.

| Situation | Badge |
| --- | --- |
| Closed function called from Main and IO | `called within Dispatcher Main \| IO` above its declaration |
| No project code call | No declaration badge |
| Project call exists but its dispatcher is unresolved | `called within Dispatcher Unknown` |
| Known Main caller plus unresolved entry paths | `called within Dispatcher Main \| Unknown` |
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

An identified custom dispatcher can use a label such as `Dispatcher Custom(MyDispatcher)` or `Dispatcher Room(query executor)` when a verified library summary establishes that identity. A Room API name alone is insufficient. Proven standard dispatchers retain their standard label/color even when supplied through a library; unresolved custom values stay gray. General user-configurable mappings remain later work.

## Model and transfer rules

- Represent a set of `Main`, `IO`, `Default`, `Unconfined`, and identified `Custom(identity, label)` entries plus an independent unknown flag and provenance. Store immutable source origins separately for each concrete dispatcher and merge them through joins and inheritance until both identities and evidence converge. Keep `Inherited` symbolic in function summaries until a call context is supplied. Keep the internal no-evidence state distinct from `Unknown` during fixed-point iteration.
- Track execution regions/path alternatives and coverage separately from incoming sets. Display order is Main, IO, Default, Unconfined, custom entries sorted by label and identity, Unknown. Fold `Main.immediate` into Main while preserving that detail in explanations; these are dispatcher identities, not physical-thread guarantees. Custom identities must not be merged solely because their labels match.
- Resolve API identities, aliases, and proven immutable values using the Analysis API. Names such as `IO`, `withContext`, or `viewModelScope` are insufficient evidence.
- Apply context composition by key: an explicit dispatcher replaces the inherited dispatcher; `CoroutineName` or `NonCancellable` alone does not. Unknown context elements that could override the dispatcher preserve uncertainty.
- Enter `withContext` with its merged context and restore the previous context after the block, including subsequent statements and exception paths.
- Model direct suspend calls and interprocedural summaries. Join branches and recursion until the monotone analysis reaches a fixed point; add uncertainty when resolution is incomplete. Detect alias cycles explicitly rather than cutting off an arbitrary recursion depth.
- `launch`/`async` use their receiver scope plus explicit context. Model proven default-dispatcher insertion only when no dispatcher is present. Their child bodies do not contribute to the parent's synchronous call-site summary. Unsupported start modes remain uncertain.
- `coroutineScope`/`supervisorScope` inherit the current dispatcher. Calls inside their bodies participate in analysis. Distinguish eagerly executed lambdas from stored callbacks and deferred work.
- Unresolved virtual targets, unidentified custom/injected dispatchers, unknown scope provenance, and missing library summaries contribute `Unknown`. Preserve identified custom dispatchers and their provenance. A custom ViewModel scope prevents assuming Main solely from `viewModelScope`.

## Initial boundary and architecture

Start with Kotlin/JVM project sources, named suspend functions, direct calls, resolved `withContext`, standard builders, simple immutable aliases, and provably identified custom dispatchers. Include a version-scoped Room summary/fixture for an identifiable library-provided dispatcher, alongside standard-dispatcher and unresolved cases. Analyze external suspend implementations only through explicit verified summaries. General higher-order flow, Flow/`flowOn`, DI inference, user-configurable custom dispatcher mapping, and multiplatform analysis are later work; propagate uncertainty where these affect results.

The alpha's Room summary covers explicit `database.queryExecutor.asCoroutineDispatcher()` for Room 2.7.2 and 2.8.4 when symbol identity and artifact version are proven. Implicit DAO and transaction dispatchers remain unknown. Standard dispatchers selected explicitly in project source retain their usual identities. Unknown library effects prevent full-coverage claims.

A project startup activity schedules analysis even when no Kotlin editor is open. Platform-managed project coroutines wait for indexing and committed documents, coalesce save events, and resolve project sources in cancellable read actions. The editor only reads results; an inlay pass never starts symbol resolution. New revisions supersede pending or running work. Only a snapshot matching the current saved source/root revision is published; publication refreshes inlays. Pending or invalidated results are gray when existing call evidence remains usable, otherwise omitted. They never reuse stale certainty.

Persist immutable per-file graphs and summaries under `build/dispatcher-analyzer/`. Cache entries include source content and structure hashes, dependency edges, and an environment fingerprint. Validate these on reopen before reusing results. A saved body change re-resolves that file and its reverse source dependencies, then recomputes affected execution effects and incoming contexts. Structural declaration/import changes, added or removed files, project roots, and library changes conservatively rebuild the graph. Missing, incompatible, or corrupt caches are cache misses; `clean` is safe. Disk IO runs in the background outside read actions and writes atomically. Never persist PSI, analysis sessions, or lifetime-bound symbols.

There are no arbitrary file, function, node, or fixed-point-round caps. Cancellation and project disposal stop background work. CPU and memory use still depend on project size; asynchronous execution does not imply a wall-clock or memory guarantee.

Use a pure dispatcher model, a small Kotlin Analysis API adapter, a background project analysis service, and editor providers. Reuse immutable callee summaries for declaration and call-site presentations. Opaque ordinary library calls are modeled at their invocation context; unsupported source helpers and callback behavior retain uncertainty about nested effects.

Use platform-managed `InlayHintsProvider` block and inline presentations. The target's declarative API lacks per-entry color controls, so the classic provider supplies the required mixed palette through custom presentations. Provide settings for call-site visibility, with evidence or uncertainty explanations in badges. Above-code hints reserve visual height but never change document text or line numbers.

## Baseline and sources

Verified on 2026-10-08 against the installed IDE and official release metadata: Android Studio Rabbit 1 (download version `2026.2.1.8`), build `AI-262.9437.185.2621.16467767`, bundled Kotlin plugin `262.9437.185.2621.16467767-AS`. The plugin targets platform 262 and Java 25. It declares K2 compatibility explicitly for Plugin Verifier, although the IDE no longer requires that declaration.

The build pins IntelliJ Platform Gradle Plugin 2.19.0, Gradle 9.7.1, and Kotlin 2.4.0. The Gradle wrapper validates the distribution checksum. See [development instructions](development.md) for reproducible commands and the optional local IDE override.

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

- [Project startup activities](https://plugins.jetbrains.com/docs/intellij/plugin-components.html#project-open)
- [Platform threading model](https://plugins.jetbrains.com/docs/intellij/threading-model.html)
- [Virtual file system events](https://plugins.jetbrains.com/docs/intellij/virtual-file-system.html)
