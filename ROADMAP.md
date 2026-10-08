# Roadmap

Deliver an Android Studio plugin with editor-only dispatcher badges above suspend declarations and at call sites. See the [analysis contract](docs/analysis-model.md) for exact meaning and limitations.

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

The presentation prototype and dispatcher model can proceed in parallel after bootstrap. Each issue includes acceptance criteria; the alpha is complete when all seven pass.

## Initial scope

- Kotlin/JVM source, named suspend functions, direct calls, standard coroutine builders, and resolved withContext.
- Declaration badges show incoming contexts; call badges show callee effects. Preserve Unknown and label proven partial coverage.
- Use Main red, Default green, IO yellow, Unknown/analysis problems gray, and other identified dispatchers purple. Verify mixed-entry colors and readable contrast in the prototype; source remains unchanged.
- Include proven custom identities and a version-scoped Room summary with fixtures. Unresolved library dispatchers stay Unknown; proven standard dispatchers keep their standard color.
- Defer Flow/flowOn, general higher-order analysis, DI inference, user-configurable custom dispatcher mapping, and multiplatform support. Unsupported cases retain uncertainty.

## Repository status

Planning documents and GitHub issues are ready. Plugin sources, the Gradle wrapper, build configuration, and CI are deliverables of step 1; no executable implementation or passing build is claimed yet.
