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
| `build-logic/` | Shared Gradle conventions (toolchain, Checkstyle, JaCoCo gate). |

All binary/manifest format code — read *and* write — lives in `lib` (`format/`, `manifest/`);
`dataset-generator` is orchestration only ([`docs/DECISIONS.md`](docs/DECISIONS.md) §1).

## Build & test

| Command | |
| --- | --- |
| `./gradlew build` | compile, Checkstyle, tests, and the ≥90% line + branch coverage gate |
| `./gradlew test` | tests only (excludes `@Tag("perf")` and `@Tag("bench")`) |
| `./gradlew test --tests "…DeletionCheckerTest"` | one class (or `"…DeletionCheckerTest.methodName"`) |
| `./gradlew perfTest` | p99.9 lookup-latency gate; manual, not part of `build`. `-Dperf.size=N` (default 1,000,000) |
| `./gradlew :benchmarks:benchmark` | full packed-vs-`HashSet` comparison; local only, ~15-25 min. See [docs/benchmarks/](docs/benchmarks/) |
| `./gradlew checkstyleMain checkstyleTest` | lint only |

`./gradlew build` also enforces that `lib` resolves zero runtime dependencies
(`checkNoRuntimeDependencies`). Test fixtures are built programmatically via `lib`'s own writers; no
binary files are checked in.

## Style

Checkstyle (config `config/checkstyle/checkstyle.xml`, shared by every module) — violations fail the
build. 4-space indent, 120-column lines, no wildcard imports, documented public API.
