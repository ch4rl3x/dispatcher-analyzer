# Roadmap

Deliver an Android Studio plugin with editor-only dispatcher badges above suspend declarations, optionally above non-suspend declarations, and at call sites. See the [analysis contract](docs/analysis-model.md) for exact meaning and limitations.

## Implementation order

| Step | GitHub issue | Outcome | Depends on |
| --- | --- | --- | --- |
| 1 | [Bootstrap the Android Studio plugin with K2 support](https://github.com/ch4rl3x/dispatcher-analyzer/issues/1) | Reproducible build, pinned Android Studio/Kotlin target, CI | — |
| 2 | [Prototype editor-only dispatcher badges and color support](https://github.com/ch4rl3x/dispatcher-analyzer/issues/2) | Above-declaration and inline call badges; specified palette | #1 |
| 3 | [Implement the dispatcher model and semantic context resolution](https://github.com/ch4rl3x/dispatcher-analyzer/issues/3) | Standard/custom identities, Room evidence, inheritance, Unknown | #1 |
| 4 | [Analyze suspend-function execution effects and partial coverage](https://github.com/ch4rl3x/dispatcher-analyzer/issues/4) | withContext, nested effects, partial coverage | #3 |
| 5 | [Infer incoming dispatchers across project call sites](https://github.com/ch4rl3x/dispatcher-analyzer/issues/5) | Incoming contexts across files, scopes, and recursion | #3, #4 |
| 6 | [Connect analysis to declaration and call-site badges](https://github.com/ch4rl3x/dispatcher-analyzer/issues/6) | Live badges, settings, evidence, editor tests | #2, #4, #5 |
| 7 | [Validate responsiveness and package the first alpha](https://github.com/ch4rl3x/dispatcher-analyzer/issues/7) | Responsive analysis, compatibility checks, installable alpha | #6 |
| 8 | [Navigate dispatcher badges to source evidence](https://github.com/ch4rl3x/dispatcher-analyzer/issues/8) | Per-name navigation to contributing dispatcher origins | #6 |
| 9 | [Add call-site badge display modes](https://github.com/ch4rl3x/dispatcher-analyzer/issues/9) | All calls, dispatcher-setting calls, or none | #6 |
| 10 | [Cache automatic analysis after saved changes](https://github.com/ch4rl3x/dispatcher-analyzer/issues/10) | Import/save triggers, persistent cache, per-file refresh icon | #6, #9 |
| 11 | [Limit call badges to the caret function](https://github.com/ch4rl3x/dispatcher-analyzer/issues/11) | Filtered default, optional caret-based call badges | #9 |
| 12 | [Publish downloadable plugin build artifacts](https://github.com/ch4rl3x/dispatcher-analyzer/issues/12) | Manual CI trigger and installable plugin ZIP distribution | #10, #11 |
| 13 | [Document plugin installation before Marketplace](https://github.com/ch4rl3x/dispatcher-analyzer/issues/13) | Download, disk installation, and update instructions | #12 |

The presentation prototype and dispatcher model can proceed in parallel after bootstrap. Navigation, call-site display modes, and saved-file caching extend the integrated badges before the final build-distribution and installation tasks, followed by alpha validation. Each issue includes acceptance criteria; the alpha is complete when all thirteen pass.

## Initial scope

- Kotlin/JVM source, named suspend functions, direct calls, standard coroutine builders, and resolved withContext.
- An opt-in setting shows incoming-context badges above called non-suspend functions. Direct ordinary helper chains propagate contexts and origins; callbacks, unresolved entries, and virtual targets preserve uncertainty. The setting only changes declaration presentation, with no new ordinary call-site badges.
- Declaration and call badges use `Dispatcher.…` for incoming contexts and callee effects respectively. Tooltips describe incoming contexts at declarations and explicit switches at calls, using the badge palette; inherited calls describe execution contexts. Preserve Unknown and label proven partial coverage.
- Place declaration badges below Code Vision usages, closer to the function, with a fixed inlay priority.
- Place call badges immediately after the call's argument list, before any trailing lambda; never after its closing brace. Calls without parentheses use the callee name or type arguments as the anchor.
- Omit redundant `withContext` badges when its argument directly identifies a standard dispatcher. Keep badges for value aliases, unresolved values, and ordinary calls whose callee switches dispatchers internally.
- Functions without project code calls have no declaration badge; public visibility and documentation references alone are insufficient. Public visibility does not add Unknown to known project call contexts. Suspend declaration badges have no plugin-specific off switch. A dropdown selects all call-site badges, only dispatcher-setting calls (default), or none. Filtered badges show only explicitly selected dispatcher entries and their origins, while `(partial)` still reflects the complete workload. An optional checkbox limits call badges to the function containing the caret. Expect declarations and calls are excluded.
- Analyze automatically after project import and saved changes, including IDE autosave. Typing cancels and invalidates work without starting a new analysis. No manual mode or side panel is needed.
- Provide a Reanalyze dispatcher usage in file icon in the editor toolbar to save and force-refresh the active file and its affected dependencies.
- Cache immutable per-file graphs under `build/dispatcher-analyzer/`, validate fingerprints on reopen, and update changed files and affected dependencies. Rebuild after cache removal, structural changes, or environment changes. Do not cap project size or convergence rounds arbitrarily.
- Use Main red, Default green, IO yellow, Unknown/analysis problems gray, and other identified dispatchers purple. Verify mixed-entry colors and readable contrast in the prototype; source remains unchanged.
- Include proven custom identities and a version-scoped Room summary with fixtures. Unresolved library dispatchers stay Unknown; proven standard dispatchers keep their standard color.
- Make each colored dispatcher name navigate to its own source evidence. Only multiple origins for that same dispatcher open a chooser; Unknown, prefixes, separators, and partial suffixes have no action.
- Defer Flow/flowOn, general higher-order analysis, DI inference, user-configurable custom dispatcher mapping, and multiplatform support. Unsupported cases retain uncertainty.

## Repository status

Optional non-suspend declaration badges are implemented and validated with 168 tests, packaging, project configuration checks, Plugin Verifier, and the updated demo. Their native visual smoke check is unavailable because UI automation cannot access the Java sandbox window.

Tooltips omit generic context descriptions and the thread-safety disclaimer, retaining function-specific descriptions and analysis diagnostics.

The alpha implementation, Gradle wrapper, demo, installation guide, and release automation are present. All 168 local tests, packaging, and Plugin Verifier pass on the pinned Android Studio target. Earlier native light/dark smoke checks passed; the latest badge-placement, filtered-entry, and tooltip changes are covered by IDE tests, with their native visual rechecks unavailable. GitHub Actions validates changes before releasing qualifying Conventional Commits on `main` with a SemVer tag, installable ZIP, and checksum. Files are attached only to releases; normal workflow runs upload no artifacts. Release versions are injected during the build without version-bump commits. See the [release rules](docs/development.md#releases) and [validation record](docs/validation.md).
