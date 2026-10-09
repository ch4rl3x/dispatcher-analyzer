# Alpha validation

Validated on 2026-10-09 with Android Studio Rabbit 1, build `AI-262.9437.185.2621.16467767`, on macOS ARM64 using bundled JBR 25.0.3. Test JVM heap limit: 2 GiB.

## Automated checks

- All 147 semantic and IDE tests pass, covering dispatcher identities, incoming contexts, execution effects, scoped Room evidence, source navigation, settings migration, caret filtering, startup/save triggers, incremental dependencies, cache restart/corruption/deletion, cancellation, indexing, incomplete code, and disposal.
- Complete-source fixtures cover 1,050 functions, 130 source files, and a 71-function call chain. No project-size or fixed-point-round caps are used.
- `test`, `buildPlugin`, `verifyPluginProjectConfiguration`, and `verifyPlugin` pass with the pinned IDE. Plugin Verifier reports compatibility without internal API usage. Experimental analysis/editor APIs limit the advertised range to platform 262; the exact build above is the tested target.
- Editor fixtures check positions, unchanged source and line counts, copy, formatting, undo, zoom, settings, and live caret updates. Suspend expect declarations and calls are excluded.
- Placement regressions cover trailing-lambda headers, redundant direct-dispatcher badge suppression, value/import aliases, shadowed symbols, wrapper effects, saved edits, and rejection of caches with old badge positions.
- Filtered-mode regressions verify that mixed Main/IO workload displays only IO (partial), while All calls retains both entries. Tests cover selection origins, nested and transitive switches, unknown selections and bodies, save/settings updates, and rejection of caches without selection identities.
- The standalone demo compiles with its Gradle 9.7.1 wrapper and JDK 25.

## Performance sample

A fixture with 100 suspend functions and 200 suspend calls took 289 ms for its first analysis and less than 1 ms for cached access. The test JVM used 341 MiB of heap at the measurement point, including IDE fixtures. This is one local run, not the plugin's incremental memory footprint or a latency guarantee.

Regression thresholds are 30 seconds for initial analysis and 1 second for cached access, allowing slower CI machines. Crossing a threshold fails the test; it never truncates analysis. Source resolution reuses unchanged file graphs and updates affected dependencies after saves. Structural or environment changes can require full analysis. Background work is cancellable and coalesces saves; typing alone does not start analysis. CPU and memory use still grow with project size.

## Native sandbox checks

The filtered-entry change passed all automated checks on 2026-10-09. Its native visual check remains unverified: the Gradle sandbox started, but the available UI automation rejected its Java app identity and could not access the window. The sandbox was closed after the attempt. The earlier badge-placement visual recheck also remains unverified; the light/dark results below describe the earlier baseline.

The imported demo displays the required mixed palette in [Islands Light](images/dispatcher-light.png) and [Islands Dark](images/dispatcher-dark.png), with no source edits or added source lines.

- Automatic startup produces badges and the cache under `build/dispatcher-analyzer/`.
- The refresh icon appears immediately beside the editor overflow menu; clicking it regenerates the cache for unchanged source.
- Settings expose the three call-badge modes and the optional caret-function filter. The checkbox is disabled for Do not show and retains its value.
- Moving between `ioWrapper` and `mixedWorkload` moves call badges with the caret. Moving outside functions hides them; declaration badges remain visible.
- Clicking Main jumps directly to the selecting context. Clicking IO offers only its two contributing origins; choosing the second opens its exact expression. Navigation also works after force-refreshing the cache, without requiring a preceding hover.
- Dispatcher selection, partial coverage, Unknown, and custom colors are also verified by semantic/editor fixtures.

## Distribution

The installable alpha ZIP is generated in `build/distributions/`. The [Build workflow](https://github.com/ch4rl3x/dispatcher-analyzer/actions/workflows/build.yml) runs release-tooling tests and the full Gradle checks before publication. Qualifying Conventional Commits on `main` produce versioned [GitHub releases](https://github.com/ch4rl3x/dispatcher-analyzer/releases) with the plugin ZIP and its `.sha256` checksum. Pull requests and ordinary builds upload no artifacts or reports. See the [installation guide](../README.md#install-and-configure) and [release rules](development.md#releases).

All 21 release-tooling tests pass, covering version selection, prerelease progression, missing or conflicting tags, repeated runs, and draft recovery. Actionlint 1.7.12 validates the workflow. The publisher checks the ZIP filename and plugin descriptor against the planned version, verifies uploaded asset sizes and available digests, then verifies the published tag's commit. Marketplace publication is separate.

Flow/flowOn, general higher-order inference, DI, multiplatform analysis, and suspend expect functions remain outside this alpha. Room evidence is limited to the verified cases in the [analysis contract](analysis-model.md).
