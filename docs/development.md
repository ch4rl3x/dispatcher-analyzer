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

Plugin archives are written to `build/distributions/`. CI runs the checks above and uploads the archive and reports. Analysis API and editor integration are tested against this target; broader compatibility requires its own checks.

The platform range deliberately ends at `262.*` because the analysis and inlay APIs are experimental. `verifyPluginProjectConfiguration` recommends removing that upper bound; this project retains it until another platform has been tested. Experimental-API reports are expected, but internal-API and binary compatibility failures must be fixed.

The [standalone demo](../samples/demo) has its own pinned wrapper. Open it in the sandbox and allow the Gradle import to finish before checking badges. Its sources are fixtures; running them is unnecessary. See the [validation record](validation.md) for results and the remaining manual checklist.
