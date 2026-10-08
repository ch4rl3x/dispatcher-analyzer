# Development

## Pinned environment

| Component | Version |
| --- | --- |
| Android Studio | Rabbit 1, `2026.2.1.8` |
| IDE build | `AI-262.9437.185.2621.16467767` |
| Bundled Kotlin plugin | `262.9437.185.2621.16467767-AS` |
| Java toolchain | 25 |
| Kotlin compiler | 2.4.0 |
| Gradle | 9.7.1 |
| IntelliJ Platform Gradle Plugin | 2.19.0 |
| Plugin Verifier | 1.410 |

Set `JAVA_HOME` to JDK 25, then run:

```sh
./gradlew test buildPlugin verifyPluginProjectConfiguration verifyPlugin
./gradlew runIde
```

Gradle downloads the pinned Android Studio distribution. To reuse the same installed IDE, add `-PlocalIdePath="/path/to/Android Studio.app"` to each command. Keep machine-specific paths out of tracked files. The bundled Android Studio runtime can also serve as JDK 25.

Plugin archives are written to `build/distributions/`. CI runs the checks above on pushes, pull requests, and manual workflow dispatch. It uploads the ZIP as `dispatcher-analyzer-plugin` and reports as `validation-reports`, each retained for 14 days. Analysis API and editor integration are tested against this target; broader compatibility requires its own checks.

The platform range deliberately ends at `262.*` because the analysis and inlay APIs are experimental. `verifyPluginProjectConfiguration` recommends removing that upper bound; this project retains it until another platform has been tested. Experimental-API reports are expected, but internal-API and binary compatibility failures must be fixed.

The [standalone demo](../samples/demo) has its own pinned wrapper. Open it in the sandbox and allow the Gradle import to finish before checking badges. Its sources are fixtures; running them is unnecessary. See the [validation record](validation.md) for results and the smoke checklist.

## Analysis cache

Opening or importing a project starts background analysis after indexing. Saving Kotlin sources, including IDE autosave, updates the changed files and affected dependencies. Unsaved edits invalidate badges without starting another analysis. Settings > Tools > Dispatcher Analyzer controls call-site badges only. The editor toolbar’s Reanalyze file action saves and force-refreshes the current file and affected dependencies.

The disposable binary cache lives under the analyzed project’s `build/dispatcher-analyzer/` directory. It contains immutable source graphs, summaries, and dispatcher origins, with source/environment fingerprints and a versioned format. Keep it out of version control. Delete the directory to discard it; missing or incompatible cache data is rebuilt automatically. Structural source changes and dependency/root changes can require a full rebuild.
