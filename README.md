# deletion-checker-java

Local, exact **deletion-state lookup** for services that need to know whether an entity ID has been
deleted — without a runtime network call. A build-time tool packs the deleted IDs into an immutable
binary dataset (one file per entity type, plus a manifest); at runtime the library memory-maps only
the entity types a service asks for and answers membership with a two-level binary search.

- **Exact** — no false positives or negatives; IDs are compared as raw UTF-8 bytes, never hashed.
- **Sub-millisecond** p99.9 lookup; low GC overhead on the hot path.
- **Selective** — a service pays memory and startup cost only for the entity types it loads.
- **Zero runtime dependencies** in the library.

See [`docs/DESIGN.md`](docs/DESIGN.md) for the architecture and binary layout, and
[`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md) for build history.

## Modules

| Module | Purpose |
| --- | --- |
| `lib/` | The runtime library (`com.marksayson.deletionchecker`). Zero runtime dependencies. |
| `dataset-generator/` | Build-time CLI that turns a deletion feed into a packed dataset. Depends on `lib`; not shipped to services. |
| `benchmarks/` | Local-only comparative benchmark vs a `HashSet<String>` baseline ([docs/benchmarks/](docs/benchmarks/)). |
| `build-logic/` | Shared Gradle conventions (toolchain, Checkstyle, JaCoCo gate). |

## Using the library

```java
import com.marksayson.deletionchecker.DeletionChecker;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

// Load once at startup; the instance is immutable and thread-safe.
DeletionChecker checker = DeletionChecker.load(Path.of("/data/deletions"), Set.of("user", "order"));

boolean gone = checker.isDeleted("user", "5f3c1e77-...");        // exact membership

List<Order> live = checker.filter("order", orders, Order::id);   // keep the non-deleted, in order

String version = checker.datasetVersion();  // ISO-8601 stamp of the loaded release
Instant loaded = checker.loadedAt();        // when this instance became active
```

`load` fails fast (during construction, never on the lookup path) if the manifest or a requested
file is missing, malformed, version-mismatched, or fails its checksum. Calling `isDeleted` / `filter`
with an entity type that was not requested at load throws `IllegalArgumentException`; an identifier
that is null, empty, over 36 UTF-8 bytes, or contains an unpaired surrogate also throws
`IllegalArgumentException`.

### Memory accounting

The dataset is **off-heap, file-backed memory**. Mapping a file does not add to the Java heap, but as
lookups touch pages, page cache / RSS / page-table entries accumulate and count toward a container's
memory limit the same as any resident memory. Size container limits assuming the full working set of
the requested entity types can become resident. Clean file-backed pages are reclaimable by the kernel
under pressure without an OOM kill, but do not under-budget on that basis.

## Generating a dataset

The generator reads **JSONL** — one flat object per line, UTF-8:

```json
{"entityType": "user", "id": "5f3c1e77-2a9b-4c1d-8e6f-0a1b2c3d4e5f"}
{"entityType": "order", "id": "ord_10293"}
```

```
./gradlew :dataset-generator:run --args="\
  --input deletions.jsonl \
  --output /data/deletions \
  --generator-version 3.2.1 \
  --dataset-version 2026-09-06T17:00:00Z \
  --bucket-size 128"
```

| Option | |
| --- | --- |
| `--input`, `-i` | JSONL feed (required) |
| `--output`, `-o` | dataset directory, created if absent (required) |
| `--generator-version` | recorded in the manifest; must begin with a digit (required) |
| `--dataset-version` | ISO-8601 timestamp; defaults to now |
| `--bucket-size` | prefix-index bucket target `K`; defaults to 128 |
| `--bloom-fpr` | Bloom-filter target false-positive rate in `(0, 1]`; defaults to `0.01`. `1.0` writes no filter |
| `--help`, `--version` | |

The pipeline groups by entity type, validates and UTF-8-encodes each identifier, sorts by encoded
bytes, drops duplicates, writes one packed file per type plus `manifest.json`, then **self-validates**
by loading the result with `DeletionChecker`. A given input always produces byte-identical output.
Malformed input exits `2` with a one-line message naming the offending line.

## Build & test

| | |
| --- | --- |
| `./gradlew build` | compile, Checkstyle, tests, and the ≥90% line + branch coverage gate |
| `./gradlew test` | tests only (excludes `@Tag("perf")` and `@Tag("bench")`) |
| `./gradlew perfTest` | p99.9 lookup-latency gate; manual, not part of `build`. `-Dperf.size=N` (default 1,000,000) |
| `./gradlew :benchmarks:benchmark` | full packed-vs-`HashSet` comparison; local only, ~15-25 min. See [docs/benchmarks/](docs/benchmarks/) |
| `./gradlew checkstyleMain checkstyleTest` | lint only |

Java 21 (Gradle toolchain). Test fixtures are built programmatically via `lib`'s own writers; no
binary files are checked in.
