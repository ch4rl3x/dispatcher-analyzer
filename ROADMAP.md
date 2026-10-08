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
| 8 | [Navigate dispatcher badges to source evidence](https://github.com/ch4rl3x/dispatcher-analyzer/issues/8) | Per-name navigation to contributing dispatcher origins | #6 |
| 9 | [Add call-site badge display modes](https://github.com/ch4rl3x/dispatcher-analyzer/issues/9) | All calls, dispatcher-setting calls, or none | #6 |

The presentation prototype and dispatcher model can proceed in parallel after bootstrap. Navigation and call-site display modes extend the integrated badges before final alpha validation. Each issue includes acceptance criteria; the alpha is complete when all nine pass.

## Initial scope

- Kotlin/JVM source, named suspend functions, direct calls, standard coroutine builders, and resolved withContext.
- Declaration badges use `called within Dispatcher …` for incoming contexts; call badges use `Dispatcher …` for callee effects. Preserve Unknown and label proven partial coverage.
- Functions without project code calls have no declaration badge; public visibility and documentation references alone are insufficient. Declaration badges have no plugin-specific off switch. A dropdown selects all call-site badges, only dispatcher-setting calls, or none (default). Suspend expect declarations and calls are excluded.
- Analyze asynchronously after a 750 ms typing pause, cancel superseded work, and refresh only current results. Do not cap project size or convergence rounds arbitrarily.
- Offer automatic analysis (default) or manual analysis with an Analyze project button in the Dispatcher Analyzer side panel. Manual mode keeps stale results gray until explicitly refreshed.
- Use Main red, Default green, IO yellow, Unknown/analysis problems gray, and other identified dispatchers purple. Verify mixed-entry colors and readable contrast in the prototype; source remains unchanged.
- Include proven custom identities and a version-scoped Room summary with fixtures. Unresolved library dispatchers stay Unknown; proven standard dispatchers keep their standard color.
- Make each colored dispatcher name navigate to its own source evidence. Only multiple origins for that same dispatcher open a chooser; Unknown, prefixes, separators, and partial suffixes have no action.
- Defer Flow/flowOn, general higher-order analysis, DI inference, user-configurable custom dispatcher mapping, and multiplatform support. Unsupported cases retain uncertainty.

## Repository status

The alpha implementation, Gradle wrapper, demo, and CI are present. Local semantic/editor tests, packaging, and Plugin Verifier pass on the pinned Android Studio target. Final visual smoke testing and CI validation are tracked in #7. See the [validation record](docs/validation.md).
