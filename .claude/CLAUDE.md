# deletion-checker-java

Local, exact deletion-state lookup over a packed immutable binary dataset. See `docs/DESIGN.md` for architecture and binary layout.

## Build & test
- Build: `./gradlew build` (runs Checkstyle + tests via `check`)
- All tests: `./gradlew test` (excludes `@Tag("perf")`)
- Single test: `./gradlew test --tests "com.marksayson.deletionchecker.DeletionCheckerTest"` (or `--tests "….DeletionCheckerTest.methodName"`)
- Perf benchmarks (manual, not in `build`): `./gradlew perfTest` (`-Dperf.size=N`)
- Lint only: `./gradlew checkstyleMain checkstyleTest`
- Java version: 21 (Gradle toolchain, set by the `deletionchecker.java-conventions` plugin in `build-logic/`)

## Style
- Checkstyle (version in `gradle/libs.versions.toml`), config `config/checkstyle/checkstyle.xml`, shared by all modules. Violations fail the build.
- 4-space indent, 120-col lines, no wildcard imports, documented public API.

## Module layout
- `lib/` — the deletion-checker library (package `com.marksayson.deletionchecker`). Zero runtime dependencies. Sources in `lib/src/main/java`, tests in `lib/src/test/java`.
- `dataset-generator/` — build-time CLI (package `…deletionchecker.generator`) that packs a JSONL feed into a dataset. `dependsOn(lib)`, may take its own deps (picocli); nothing reaches `lib`.
- `build-logic/` — the shared Gradle convention plugin (`deletionchecker.java-conventions`).
- `data/` — generated `.dat` datasets; contents gitignored (keeps `.gitkeep`).
- `docs/` — design docs (`DESIGN.md`, `IMPLEMENTATION_PLAN.md`).

## Invariants
- Public API is `isDeleted` and `filter`. Signatures in `docs/DESIGN.md` §1; don't change them.
- Dataset is immutable at runtime — no mutation paths.
- Binary format changes require explicit instruction; a change means bumping the header version.
- No new external dependencies without approval.

## Tests
- New/changed functionality needs tests in the matching `src/test/java`.
- `./gradlew build` enforces ≥90% line + branch coverage per module (JaCoCo, hard-fail); reports at `<module>/build/reports/jacoco/test/html/index.html`. `perfTest` exec data is excluded from coverage.
- `check` also runs `checkNoRuntimeDependencies` — `lib` must resolve zero runtime deps.
- End-to-end tests (`DatasetIntegrationTest`) and perf benchmarks (`LookupPerfTest`, `@Tag("perf")`) build fixtures via `lib`'s own writers — no binary files are checked in.
