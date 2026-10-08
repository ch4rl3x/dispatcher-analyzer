# Contributor instructions

## Scope and communication

- Write all repository content, UI text, issues, and pull requests in English.
- Keep changes focused and documentation concise. Add comments only for non-obvious constraints or decisions.
- Read `ROADMAP.md` and `docs/analysis-model.md` before implementation; update them when behavior changes.
- Run relevant checks before committing. Close GitHub tickets only after their implementation is validated and pushed.

## Git workflow

- Commit and push all completed repository changes after validation.
- Use Conventional Commits in English. Keep the subject at most 50 characters; wrap body lines at 72 characters and separate the body with a blank line.
- Report any commit or push failure explicitly; never claim unpublished changes are pushed.

## Implementation

- Use Kotlin and Gradle Kotlin DSL with `org.jetbrains.intellij.platform` 2.x. Pin the wrapper, plugins, toolchain, and IDE target; commit the wrapper.
- Target the actual Android Studio platform build and bundled Kotlin plugin. Check official compatibility documentation when changing versions.
- Use Kotlin PSI for syntax and the Kotlin Analysis API for resolution. Avoid K1 compiler internals and text-only symbol matching.
- Isolate experimental Analysis API and Code Vision integration. Declare K2 compatibility when required by the target platform.
- Start with one Gradle module and separate model, analysis, and editor packages. Keep the dispatcher model independent of IDE classes.
- Use platform-managed, cancellable background work and appropriate read actions. Never resolve symbols or traverse the project on the UI thread.
- Debounce analysis after a typing pause, cancel superseded work, and publish only results for the current project revision. Do not impose arbitrary file, function, node, or fixed-point-round limits.
- Default to automatic analysis. In manual mode, analyze only on explicit request from the side panel; edits invalidate results without restarting work. Keep the analysis mode independent of call-site badge visibility.
- Cache immutable summaries or restorable pointers, not analysis sessions or lifetime-bound symbols. Invalidate dependent results on source and project changes.
- Handle indexing, incomplete code, project disposal, and pending or canceled analysis without stale certainty or exceptions in the editor.

## Product contract

- Badges are editor tooling only. Never add annotations, comments, or lines to the user's source.
- Provide declaration badges only when project code contains a call to the function; do not add a plugin-specific visibility switch. Documentation references and public visibility alone do not count as calls. Call-site badges use a dropdown for all calls, dispatcher-setting calls only, or none (default). Preserve legacy preferences. Exclude suspend expect declarations and their calls.
- Keep incoming contexts separate from callee execution effects. Preserve `Unknown`; never infer thread safety from a badge.
- Prefix declaration badges with `called within Dispatcher ` and call-site badges with `Dispatcher `.
- Make only colored dispatcher names navigate to their own source evidence. Multiple origins for the same dispatcher use a chooser. Unknown and stale results never navigate.
- Follow the documented `(partial)` rule. Do not count asynchronous child bodies as synchronous parent work.
- Resolve coroutine APIs by symbol identity. Unsupported or dynamic constructs must remain explicitly uncertain.
- Use theme-aware presentation and text labels; color must not carry meaning alone.
- Preserve the badge palette: Main red, Default green, IO yellow, Unknown/analysis problems gray, other identified dispatchers purple. Color mixed entries separately; `(partial)` keeps its dispatcher's color.
- Distinguish identified custom/library dispatchers from unresolved values. Show purple only with identity evidence; unknown custom values stay gray. A Room call alone does not establish a dispatcher.

## Validation

- For analysis changes, add focused semantic fixtures for supported behavior and conservative fallbacks.
- For editor changes, check positions, updates, settings, and unchanged document text in IDE tests and a sandbox smoke test.
- Run `./gradlew test buildPlugin verifyPluginProjectConfiguration`; run `./gradlew verifyPlugin` for platform/API changes and release candidates.
- Keep CI aligned with these checks. Document unavailable checks and verify the declared Android Studio compatibility range before release.
- Never put signing keys, publishing tokens, or machine-specific IDE paths in the repository.
