# Dispatcher Analyzer

Android Studio plugin for understanding dispatcher usage in Kotlin `suspend` functions.

- **Above declarations:** editor-only badges such as `called within Dispatcher Main` or `called within Dispatcher Main | IO`, inferred from incoming calls. Functions without project code calls receive no declaration badge; documentation references do not count.
- **At call sites:** badges describing the callee's execution, including internal `withContext` switches and `(partial)` coverage.
- **Settings:** choose all call-site badges, only calls that set a dispatcher, or no call-site badges (default). Declaration badges have no plugin-specific off switch. Suspend expect declarations and calls are excluded.
- **Unknown:** unresolved contexts remain visible, including alongside known dispatchers.
- **Colors:** Main red, Default green, IO yellow, Unknown/analysis problems gray, and other identified dispatchers (including custom/library dispatchers) purple. Mixed badges color each dispatcher separately; text stays readable in light and dark themes.
- **Navigation:** click a colored dispatcher name to open the source that selected it. Each name uses its own origins; multiple origins for the same dispatcher open a chooser. Unknown has no navigation target.
- **Presentation:** no source edits or added document lines; above-code hints occupy editor space.
- **Responsiveness:** background analysis starts after a 750 ms typing pause. New edits cancel superseded work; pending results are gray or omitted until call evidence is available. Project size is not capped by file or function counts.

Badges describe static analysis, not which dispatchers are safe or permitted. A `suspend` modifier alone does not choose a dispatcher.

## Install and configure

The alpha targets Android Studio Rabbit 1 (`2026.2.1.8`, platform 262). Build with JDK 25 and `./gradlew buildPlugin`, then select the archive in `build/distributions/` through Settings > Plugins > Install Plugin from Disk.

Open Settings > Tools > Dispatcher Analyzer, or use **Settings…** in the Dispatcher Analyzer side panel.

- Automatic analysis is on by default and runs after typing pauses. Turn it off to analyze manually with **Analyze project** in the **Dispatcher Analyzer** side panel (View > Tool Windows).
- **Call-site badges:** choose **All calls**, **Only calls that set a dispatcher**, or **Do not show** (default). The filtered mode includes synchronous dispatcher selection inside the callee, including supported nested calls; merely inheriting a caller's dispatcher does not qualify.

After source changes, outdated results become gray or disappear until analysis finishes. In manual mode, they remain pending until you press **Analyze project** again. Declaration badges have no plugin-specific off switch; IDE-wide inlay controls still apply.

Open the [demo project](samples/demo) in a sandbox to explore Main, IO, Default, custom, partial, and unknown results.

## Scope

Analysis covers project Kotlin/JVM source with K2 symbol resolution, direct suspend calls, withContext, standard coroutine builders, and proven immutable dispatcher aliases. Unsupported library bodies, virtual targets, injected values, and incomplete analysis retain Unknown.

Room support recognizes explicit query-executor dispatcher conversions for verified versions; it does not infer arbitrary DAO or transaction contexts. Flow, general higher-order analysis, DI, multiplatform, and suspend expect functions are outside this alpha.

See the [roadmap](ROADMAP.md), [analysis contract](docs/analysis-model.md), [development instructions](docs/development.md), and [contributor instructions](AGENTS.md).

Official references: [plugin build tooling](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html), [Android Studio targeting](https://plugins.jetbrains.com/docs/intellij/android-studio.html), [Kotlin Analysis API](https://kotlin.github.io/analysis-api/), and [editor inlays](https://plugins.jetbrains.com/docs/intellij/inlay-hints.html).
