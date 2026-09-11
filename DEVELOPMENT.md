# Development

Building, testing, and repo layout for `deletion-checker-java`. For the architecture and binary
layout see [`docs/DESIGN.md`](docs/DESIGN.md); for invariants and design rationale see
[`docs/DECISIONS.md`](docs/DECISIONS.md).

## Prerequisites

Java 21. The Gradle toolchain (configured by the `deletionchecker.java-conventions` plugin in
`build-logic/`) provisions it if absent — nothing else to install. Use the bundled `./gradlew`.

## Modules

| Module | Purpose |
| --- | --- |
| `lib/` | The runtime library (`com.marksayson.deletionchecker`). Zero runtime dependencies. |
| `dataset-generator/` | Build-time CLI that turns a deletion feed into a packed dataset. Depends on `lib`; not shipped to services. |
| `benchmarks/` | Local-only comparative benchmark vs a `HashSet<String>` baseline ([docs/benchmarks/](docs/benchmarks/)). |
| `build-logic/` | Shared Gradle conventions (toolchain, Checkstyle, SpotBugs, JaCoCo gate). |

All binary/manifest format code — read *and* write — lives in `lib` (`format/`, `manifest/`);
`dataset-generator` is orchestration only ([`docs/DECISIONS.md`](docs/DECISIONS.md) §1).

## Build & test

| Command | |
| --- | --- |
| `./gradlew build` | compile, Checkstyle, SpotBugs, tests, and the ≥90% line + branch coverage gate |
| `./gradlew test` | tests only (excludes `@Tag("perf")` and `@Tag("bench")`) |
| `./gradlew test --tests "…DeletionCheckerTest"` | one class (or `"…DeletionCheckerTest.methodName"`) |
| `./gradlew perfTest` | p99.9 lookup-latency gate; manual, not part of `build`. `-Dperf.size=N` (default 1,000,000) |
| `./gradlew :benchmarks:benchmark` | full packed-vs-`HashSet` comparison; local only, ~15-25 min. See [docs/benchmarks/](docs/benchmarks/) |
| `./gradlew checkstyleMain checkstyleTest` | lint only |
| `./gradlew spotbugsMain spotbugsTest` | static analysis only; reports at `<module>/build/reports/spotbugs/` |

`./gradlew build` also enforces that `lib` resolves zero runtime dependencies
(`checkNoRuntimeDependencies`). Test fixtures are built programmatically via `lib`'s own writers; no
binary files are checked in.

## CI build matrix

`ci.yml` builds and tests on this JDK × OS matrix (`main` branch):

| | ubuntu | windows | macos |
|---|---|---|---|
| **JDK 21** | [![ubuntu-latest / JDK 21](https://img.shields.io/github/check-runs/msayson/deletion-checker-java/main?nameFilter=Build%20%28ubuntu-latest%2C%20jdk21%29&label=)](https://github.com/msayson/deletion-checker-java/actions/workflows/ci.yml) | [![windows-latest / JDK 21](https://img.shields.io/github/check-runs/msayson/deletion-checker-java/main?nameFilter=Build%20%28windows-latest%2C%20jdk21%29&label=)](https://github.com/msayson/deletion-checker-java/actions/workflows/ci.yml) | [![macos-latest / JDK 21](https://img.shields.io/github/check-runs/msayson/deletion-checker-java/main?nameFilter=Build%20%28macos-latest%2C%20jdk21%29&label=)](https://github.com/msayson/deletion-checker-java/actions/workflows/ci.yml) |
| **JDK 25** | [![ubuntu-latest / JDK 25](https://img.shields.io/github/check-runs/msayson/deletion-checker-java/main?nameFilter=Build%20%28ubuntu-latest%2C%20jdk25%29&label=)](https://github.com/msayson/deletion-checker-java/actions/workflows/ci.yml) | [![windows-latest / JDK 25](https://img.shields.io/github/check-runs/msayson/deletion-checker-java/main?nameFilter=Build%20%28windows-latest%2C%20jdk25%29&label=)](https://github.com/msayson/deletion-checker-java/actions/workflows/ci.yml) | [![macos-latest / JDK 25](https://img.shields.io/github/check-runs/msayson/deletion-checker-java/main?nameFilter=Build%20%28macos-latest%2C%20jdk25%29&label=)](https://github.com/msayson/deletion-checker-java/actions/workflows/ci.yml) |

Per-cell badges read GitHub's check-runs API for `main`'s latest commit; they read as "no check runs"
on a commit that never triggered CI (`ci.yml` skips markdown-only pushes).

## Style

Checkstyle (config `config/checkstyle/checkstyle.xml`, shared by every module) — violations fail the
build. 4-space indent, 120-column lines, no wildcard imports, documented public API.

SpotBugs (exclude filter `config/spotbugs/exclude.xml`, shared by every module, effort MAX) — any
finding fails the build. Confirmed false positives get a narrowly-scoped `<Match>` in the exclude
filter, not a code workaround.
