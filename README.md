<!-- Follow GitHub's theme even when it differs from the system theme. -->
![Dispatcher Analyzer — See dispatcher context in your Kotlin code. An Android Studio plugin, illustrated with Main, IO, and Default dispatcher paths.](docs/images/readme-hero-light.png#gh-light-mode-only)
![Dispatcher Analyzer — See dispatcher context in your Kotlin code. An Android Studio plugin, illustrated with Main, IO, and Default dispatcher paths.](docs/images/readme-hero-dark.png#gh-dark-mode-only)

# Dispatcher Analyzer

Android Studio plugin for understanding dispatcher usage in Kotlin `suspend` functions.

- **Above declarations:** editor-only badges such as `Dispatcher.Main` or `Dispatcher.Main | IO`, inferred from incoming calls. Hover for a function-specific explanation with colored dispatcher names. Functions without project code calls receive no declaration badge; documentation references do not count.
- **At call sites:** badges describing the callee's execution, including internal `withContext` switches and `(partial)` coverage. Tooltips name explicit dispatcher switches, or execution contexts for calls that only inherit their context.
- **Settings:** choose all call-site badges, only calls that set a dispatcher (default), or no call-site badges. Declaration badges have no plugin-specific off switch. Suspend expect declarations and calls are excluded.
- **Unknown:** unresolved contexts remain visible, including alongside known dispatchers.
- **Colors:** Main red, Default green, IO yellow, Unknown/analysis problems gray, and other identified dispatchers (including custom/library dispatchers) purple. Mixed badges color each dispatcher separately; text stays readable in light and dark themes.
- **Navigation:** click a colored dispatcher name to open the source that selected it. Each name uses its own origins; multiple origins for the same dispatcher open a chooser. Unknown has no navigation target.
- **Presentation:** no source edits or added document lines; above-code hints occupy editor space.
- **Automatic analysis:** analyze after project import and saved changes, including IDE autosave. Typing invalidates results without starting analysis. A persistent cache in `build/dispatcher-analyzer/` reuses unchanged work; project size is not capped.

Badges describe static analysis, not which dispatchers are safe or permitted. A `suspend` modifier alone does not choose a dispatcher.

## Install and configure

The alpha targets Android Studio Rabbit 1 (`2026.2.1.8`, build `AI-262.9437.185.2621.16467767`, platform 262). It is not yet published on JetBrains Marketplace.

1. Open [GitHub Releases](https://github.com/ch4rl3x/dispatcher-analyzer/releases) and select the newest release, including prereleases while the plugin is in alpha.
2. Under **Assets**, download `dispatcher-analyzer-<version>.zip`. Keep this installable plugin ZIP intact; the adjacent `.sha256` file provides its checksum.
3. In Android Studio, open **Settings > Plugins** (on macOS: **Android Studio > Settings**, called Preferences in some versions).
4. Open the gear menu, select **Install Plugin from Disk…**, choose the downloaded plugin ZIP, and confirm. Restart Android Studio if prompted.
5. Open your project and let import/indexing finish. Analysis runs automatically; configure badges under **Settings > Tools > Dispatcher Analyzer**.

To update, download a newer release and repeat the disk-install steps. Release assets provide persistent downloads without a GitHub sign-in or an extra archive to extract. The pipeline attaches files only to releases; ordinary builds and pull requests run validation without uploaded artifacts. Installation uses the standard [plugin-from-disk flow](https://www.jetbrains.com/help/idea/managing-plugins.html#install_plugin_from_disk).

Validated changes on `main` produce releases automatically from Conventional Commits: `fix`/`perf` advance the patch version, `feat` the minor version, and breaking changes the major version. The first release is `0.1.0-alpha.1`; subsequent alpha releases advance the core version and retain `-alpha.1`. See the [release workflow](docs/development.md#releases) for examples and recovery behavior.

Alternatively, build locally with JDK 25 and `./gradlew buildPlugin`, then install the ZIP from `build/distributions/` using steps 3–4.

Open Settings > Tools > Dispatcher Analyzer.

- **Call-site badges:** choose **All calls**, **Only calls that set a dispatcher** (default), or **Do not show**. The filtered mode includes synchronous dispatcher selection inside the callee, including supported nested calls; merely inheriting a caller's dispatcher does not qualify.
- **Only in the function containing the caret:** optionally limit call badges to the current function. This is off by default and available when call badges are enabled. Moving the caret updates the display; outside a function, call badges are hidden.

Analysis runs in the background after import and when Kotlin files are saved; there is no side panel or manual mode. Edits make outdated results gray or hide them until saved changes have been analyzed. Declaration badges have no plugin-specific off switch; IDE-wide inlay controls still apply.

Use **Reanalyze dispatcher usage in file** in the editor’s upper-right toolbar to rebuild the current file’s analysis and affected dependencies. This saves the current document first and leaves automatic analysis enabled.

The cache updates changed files and their dependencies. Signature, import, project-root, or library changes can require a full rebuild. The cache is disposable: deleting `build/dispatcher-analyzer/` or running `clean` causes it to be rebuilt.

Open the [demo project](samples/demo) in a sandbox to explore Main, IO, Default, custom, partial, and unknown results.

## Scope

Analysis covers project Kotlin/JVM source with K2 symbol resolution, direct suspend calls, withContext, standard coroutine builders, and proven immutable dispatcher aliases. Unsupported library bodies, virtual targets, injected values, and incomplete analysis retain Unknown.

Room support recognizes explicit query-executor dispatcher conversions for verified versions; it does not infer arbitrary DAO or transaction contexts. Flow, general higher-order analysis, DI, multiplatform, and suspend expect functions are outside this alpha.

See the [roadmap](ROADMAP.md), [analysis contract](docs/analysis-model.md), [development instructions](docs/development.md), and [contributor instructions](AGENTS.md).

Official references: [plugin build tooling](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html), [Android Studio targeting](https://plugins.jetbrains.com/docs/intellij/android-studio.html), [Kotlin Analysis API](https://kotlin.github.io/analysis-api/), and [editor inlays](https://plugins.jetbrains.com/docs/intellij/inlay-hints.html).
