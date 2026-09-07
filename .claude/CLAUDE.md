# deletion-checker-java

Local, exact deletion-state lookup over a packed immutable binary dataset. See `docs/DESIGN.md` for architecture and binary layout.

## Build & test
- Build: `./gradlew build` (runs Checkstyle + tests via `check`)
- All tests: `./gradlew test`
- Single test: `./gradlew test --tests "com.marksayson.deletionchecker.DeletionCheckerTest"` (or `--tests "….DeletionCheckerTest.methodName"`)
- Lint only: `./gradlew checkstyleMain checkstyleTest`
- Java version: 21 (Gradle toolchain, see `lib/build.gradle.kts`)

## Style
- Checkstyle (version in `gradle/libs.versions.toml`), config `config/checkstyle/checkstyle.xml`, shared by all modules. Violations fail the build.
- 4-space indent, 120-col lines, no wildcard imports, documented public API.

## Module layout
- `lib/` — the deletion-checker library (Gradle module, package `com.marksayson.deletionchecker`). Sources in `lib/src/main/java`, tests in `lib/src/test/java`.
- `data/` — generated `.dat` datasets.
- `docs/` — design docs (`DESIGN.md`).

Dataset generator is not yet implemented; when added it goes in its own module (`dataset-generator/`) with no runtime code, and generated `.dat` datasets live in `data/`.

## Invariants
- Public API is `isDeleted` and `filter`. Signatures in `docs/DESIGN.md` §1; don't change them.
- Dataset is immutable at runtime — no mutation paths.
- Binary format changes require explicit instruction; a change means bumping the header version.
- No new external dependencies without approval.

## Tests
- New/changed functionality needs tests in the matching `src/test/java`.
- `./gradlew build` enforces ≥90% line + branch coverage per module (JaCoCo, hard-fail); report at `lib/build/reports/jacoco/test/html/index.html`.
- `check` also runs `checkNoRuntimeDependencies` — `lib` must resolve zero runtime deps.
