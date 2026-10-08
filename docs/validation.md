# Alpha validation

Validated on 2026-10-08 with Android Studio Rabbit 1, build `AI-262.9437.185.2621.16467767`, on macOS ARM64 using bundled JBR 25.0.3. Test JVM heap limit: 2 GiB.

## Automated checks

- 57 tests pass: model, K2 resolution, incoming contexts, execution effects, Room identity boundary, editor presentation, persisted settings, manual tool window, background invalidation, indexing, malformed code, cancellation, and scope disposal.
- Complete-source fixtures cover 1,050 functions, 130 source files, a 71-function call chain, recursion, and cross-file invalidation. No project-size or fixed-point-round caps are used.
- `test`, `buildPlugin`, `verifyPluginProjectConfiguration`, and `verifyPlugin` pass with the local pinned IDE. Plugin Verifier reports compatibility; experimental editor/analysis APIs remain deliberately confined to platform 262. No internal API usages are accepted.
- The standalone demo compiles with its Gradle 9.7.1 wrapper and JDK 25.
- The alpha ZIP is produced in `build/distributions/`. CI builds the same artifact and uploads reports.

## Performance sample

A fixture with 100 suspend functions and 200 suspend calls took 302 ms for its first analysis and less than 1 ms for the cached request. The test JVM used 501 MiB of heap at the measurement point; this includes IDE fixtures and is not the plugin's incremental memory footprint. These figures are one local run, not a latency guarantee.

Regression smoke thresholds are 30 seconds for the first fixture analysis and 1 second for cached access, allowing slower CI machines. Crossing a threshold fails the test; it never truncates analysis. The alpha recomputes the project graph after source/root changes, so CPU and memory costs grow with project size. Automatic work waits for a 750 ms typing pause and is cancellable; manual work runs only on request.

## Visual smoke checklist

- [ ] Light/dark themes: Main red, Default green, IO yellow, Unknown gray, identified custom purple; mixed entries retain separate colors.
- [ ] Declaration badges and optional call badges preserve source text, formatting, copy, undo, line numbers, and zoom behavior.
- [ ] Call badges default to off and persist when changed; declaration badges have no plugin-specific disable switch.
- [ ] Automatic/manual mode persists; Analyze project refreshes manual results and later edits make them stale.
- [ ] Suspend expect declarations and calls receive no hints.

The native sandbox loads the plugin and displays the Dispatcher Analyzer side panel. The checklist remains open until the demo import and visual checks finish.
