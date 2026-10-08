# Dispatcher Analyzer

Planned Android Studio plugin for understanding dispatcher usage in Kotlin `suspend` functions.

- **Above declarations:** editor-only badges such as `Dispatcher Main` or `Dispatcher Main | IO`, inferred from incoming calls.
- **At call sites:** badges describing the callee's execution, including internal `withContext` switches and `(partial)` coverage.
- **Unknown:** unresolved contexts remain visible, including alongside known dispatchers.
- **Colors:** Main red, Default green, IO yellow, Unknown/analysis problems gray, and other identified dispatchers (including custom/library dispatchers) purple. Mixed badges color each dispatcher separately; text stays readable in light and dark themes.
- **Presentation:** no source edits or added document lines; above-code hints occupy editor space.

Badges describe static analysis, not which dispatchers are safe or permitted. A `suspend` modifier alone does not choose a dispatcher.

## Status

Planning only; no runnable plugin yet. Follow the [roadmap](ROADMAP.md), [analysis contract](docs/analysis-model.md), and [contributor instructions](AGENTS.md).

## Technical direction

Use Kotlin, Gradle Kotlin DSL, IntelliJ Platform Gradle Plugin 2.x, and the Kotlin Analysis API in K2 mode. Target a pinned Android Studio stable build and its bundled Kotlin plugin; validate compatibility before widening support.

Prefer Code Vision above declarations and declarative inlay hints at calls. Prototype custom coloring before committing to a renderer.

Official references: [plugin build tooling](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html), [Android Studio targeting](https://plugins.jetbrains.com/docs/intellij/android-studio.html), [Kotlin Analysis API](https://kotlin.github.io/analysis-api/), and [editor inlays](https://plugins.jetbrains.com/docs/intellij/inlay-hints.html).
