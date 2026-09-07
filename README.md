# deletion-checker-java

[![CI](https://github.com/msayson/deletion-checker-java/actions/workflows/ci.yml/badge.svg)](https://github.com/msayson/deletion-checker-java/actions/workflows/ci.yml)

**deletion-checker-java** lets a service answer "has this ID been deleted?" locally, with no runtime
network call. A build-time generator converts deleted IDs into an immutable, checksummed
dataset (one file per entity type, plus a manifest); at runtime the library memory-maps only the
entity types a service asks for.

- **Exact** — no false positives or negatives. The Bloom filter quickly rejects definite misses, but every possible match is verified against the stored UTF-8 bytes.
- **Sub-millisecond** p99.9 lookup; low GC overhead on the hot path.
- **Selective** — a service pays memory and startup cost only for the entity types it loads.
- **Zero runtime dependencies** in the library.

See [`docs/DESIGN.md`](docs/DESIGN.md) for the architecture and binary layout.

## When to use this?

`HashSet<String>` wins raw positive-lookup latency. `DeletionChecker` is designed to reduce heap usage, GC pressure, and startup time, and loads only the entity types a service needs.

| Situation | Use |
| --- | --- |
| One set, small enough to sit in heap, already loaded from your own store | `HashSet<String>` |
| Millions of IDs | `DeletionChecker` |
| Several entity types, each service needs a different subset | `DeletionChecker` |
| Tight container memory limit or a GC-pause SLA | `DeletionChecker` |
| Frequent restarts and large deletion sets | `DeletionChecker` |
| Need a versioned, checksummed, distributable dataset | `DeletionChecker` |

See [`docs/benchmarks/analysis.md`](docs/benchmarks/analysis.md) for measured trade-offs.

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

### Memory

Dataset files are memory-mapped: they don't count against the Java heap, but resident pages count
toward process RSS and container memory limits like any other memory. Size container limits for the
full working set of the entity types a service loads. See [`docs/DESIGN.md`](docs/DESIGN.md) §9.1 for
details.

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

## Contributing

Building, testing, and repo layout are in [`DEVELOPMENT.md`](DEVELOPMENT.md).
