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
| Node.js (release tooling) | 24.13.0 |

Set `JAVA_HOME` to JDK 25, then run:

```sh
./gradlew test buildPlugin verifyPluginProjectConfiguration verifyPlugin
./gradlew runIde
```

Gradle downloads the pinned Android Studio distribution. To reuse the same installed IDE, add `-PlocalIdePath="/path/to/Android Studio.app"` to each command. Keep machine-specific paths out of tracked files. The bundled Android Studio runtime can also serve as JDK 25.

Plugin archives are written to `build/distributions/`. CI runs the checks above on pushes, pull requests, and manual workflow dispatch. Pull requests and branches other than `main` use read-only validation. Only releases receive uploaded files; ordinary workflow runs do not upload plugin archives or reports. Analysis API and editor integration are tested against this target; broader compatibility requires its own checks.

The platform range deliberately ends at `262.*` because the analysis and inlay APIs are experimental. `verifyPluginProjectConfiguration` recommends removing that upper bound; this project retains it until another platform has been tested. Experimental-API reports are expected, but internal-API and binary compatibility failures must be fixed.

The [standalone demo](../samples/demo) has its own pinned wrapper. Open it in the sandbox and allow the Gradle import to finish before checking badges. Its sources are fixtures; running them is unnecessary. See the [validation record](validation.md) for results and the smoke checklist.

## Releases

Pushes to `main` and manual runs on `main` use a serialized release job. After the release-tooling tests and all Gradle checks pass, the job creates a GitHub release when commits since the previous reachable `v<SemVer>` tag warrant one. The first release uses `0.1.0-alpha.1`, configured alongside the prerelease identifier in `.github/release.json`.

The highest Conventional Commit change determines the next version:

| Commit | Change | Next version after `0.1.0-alpha.1` |
| --- | --- | --- |
| `fix:` or `perf:` | Patch | `0.1.1-alpha.1` |
| `feat:` | Minor | `0.2.0-alpha.1` |
| Any type with `!` or a `BREAKING CHANGE:` footer | Major | `1.0.0-alpha.1` |
| Other types without a breaking change | No release | — |

Alpha releases advance the core version and reset the prerelease suffix to `alpha.1`. The planner reads Git history and writes the release plan and notes under `build/`; it does not create version-bump commits. The planned version is passed to Gradle with `-PpluginVersion=<version>` so the plugin descriptor, ZIP filename, and release tag agree.

Use the pinned Node.js version to validate the release tooling locally:

```sh
npm ci --ignore-scripts
npm test
node scripts/plan-release.mjs
```

Planning requires complete history and release tags. The workflow creates a draft, uploads the installable ZIP and its `.sha256` checksum, then publishes the release. Reruns skip an already complete release at the same commit, resume drafts, and refuse conflicting tags. Superseded `main` runs skip publication. Use **Run workflow** on `main` to retry a failed release after resolving its cause.

Release assets are the only uploaded artifacts. GitHub release publication does not publish to JetBrains Marketplace.

## Analysis cache

Opening or importing a project starts background analysis after indexing. Saving Kotlin sources, including IDE autosave, updates the changed files and affected dependencies. Unsaved edits invalidate badges without starting another analysis. Settings > Tools > Dispatcher Analyzer controls call-site badges only. The editor toolbar’s Reanalyze file action saves and force-refreshes the current file and affected dependencies.

The disposable binary cache lives under the analyzed project’s `build/dispatcher-analyzer/` directory. It contains immutable source graphs, summaries, and dispatcher origins, with source/environment fingerprints and a versioned format. Keep it out of version control. Delete the directory to discard it; missing or incompatible cache data is rebuilt automatically. Structural source changes and dependency/root changes can require a full rebuild.
