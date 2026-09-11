# deletion-checker-java

[![CI](https://github.com/msayson/deletion-checker-java/actions/workflows/ci.yml/badge.svg)](https://github.com/msayson/deletion-checker-java/actions/workflows/ci.yml)

**deletion-checker-java** lets a service answer "has this ID been deleted?" locally, with no runtime network call. A build-time generator packs a feed of deleted IDs into an immutable, checksummed dataset (one file per entity type, plus a manifest); at runtime the library memory-maps only the entity types a service asks for.

- **Exact** — no false positives or negatives.
- **Near-zero heap** — scales to millions of IDs without adding GC pauses or memory pressure to your service.
- **Loads in tens of milliseconds** — mmap and a checksum check, even at 10M IDs.
- **Selective** — a service pays memory and startup cost only for the entity types it loads.
- **Zero runtime dependencies** in the library.

See [`docs/DESIGN.md`](docs/DESIGN.md) for the architecture and binary layout.

## DeletionChecker vs HashSet

Lookup latency is close and workload-dependent. `HashSet<String>` is faster when most lookups are for IDs that *are* deleted; `DeletionChecker` matches or beats it when most are *not* — the common access-control case — thanks to a Bloom-filter front end that skips non-members without touching the identifier data.

`DeletionChecker` consumes hundreds of times less heap than a `HashSet<String>` and loads ~30x faster, benefiting memory-constrained services and fast cold starts.

| Situation | Use |
| --- | --- |
| A single small set already held in memory | `HashSet<String>` |
| Lookups are mostly for IDs that *are* deleted, and the set fits in heap | `HashSet<String>` |
| Millions of IDs | `DeletionChecker` |
| Several entity types, each service loading a different subset | `DeletionChecker` |
| Tight container memory limit or a GC-pause SLA | `DeletionChecker` |
| Frequent restarts or autoscaling | `DeletionChecker` |
| A versioned, checksummed, distributable dataset | `DeletionChecker` |

See [`docs/benchmarks/analysis.md`](docs/benchmarks/analysis.md) for measured trade-offs.

## Using the library

```java
import com.marksayson.deletionchecker.DeletionChecker;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

// Load once at startup; the instance is immutable and thread-safe.
DeletionChecker checker = DeletionChecker.load(Path.of("/data/deletions"), Set.of("user", "order"));

boolean gone = checker.isDeleted("user", "5f3c1e77-...");        // exact membership

List<Order> live = checker.filter("order", orders, Order::id);   // keep the non-deleted, in order

String version = checker.datasetVersion();  // ISO-8601 stamp of the loaded release
Instant loaded = checker.loadedAt();        // when this instance became active
```

`load` fails fast if the manifest or a requested file is missing, malformed, version-mismatched, or fails its checksum. Calling `isDeleted` / `filter` with an entity type that was not requested at load throws `IllegalArgumentException`, as does an identifier or entity type that breaks the [input constraints](#input-constraints).

## Input constraints

The library and the generator enforce these and throw `IllegalArgumentException` for violations, with the generator specifying the invalid line in its error message.

**Identifiers** — the `id` passed to `isDeleted` / `filter`, and the `id` field in the feed:

- non-null, non-empty, valid Unicode text
- at most 64 bytes once UTF-8-encoded

**Entity type names** — the `entityType` passed to `load` / `isDeleted` / `filter`, and the
`entityType` field in the feed:

- 1 to 64 printable ASCII characters — no spaces, `/` or `\`. E.g. `user`, `payment_method`,
  `api-key`. The name is used verbatim in the packed file header, the manifest, and the generated
  file name.

**Generator feed** (JSONL):

- one flat JSON object per line, UTF-8: `{"entityType": "...", "id": "..."}` — no nesting;
- duplicate identifiers within an entity type collapse to one; the dataset is a true set;
- `--generator-version` must begin with a digit; `--dataset-version`, if provided, must be an
  ISO-8601 timestamp.

## Memory

Dataset files are memory-mapped and do not occupy Java heap; resident mapped pages still count toward process RSS and container memory limits like any other memory. Set container memory limits based on the full working set of the entity types a service loads. See [`docs/DESIGN.md`](docs/DESIGN.md) §9.1 for details.

## Scale

Comfortable up to **~10 million deleted IDs per entity type** (the benchmarked target). A single
entity type has a hard ceiling today — roughly 30M–140M IDs depending on average ID length — past which `DeletionChecker.load` (or generation) fails; splitting an entity type across multiple files isn't supported yet. No limit on the number of entity types or total dataset size, which are bounded only by the container memory the loaded working set needs (see [Memory](#memory)).

`filter` builds a result list the size of its input and processes it sequentially — fine for request-scoped batches, but chunk very large ones.

See [`docs/SCALE_LIMITS.md`](docs/SCALE_LIMITS.md) for exact numbers and a `HashSet` memory comparison.

## Generating a dataset

The generator reads **JSONL** — one flat object per line, UTF-8:

```json
{"entityType": "user", "id": "5f3c1e77-2a9b-4c1d-8e6f-0a1b2c3d4e5f"}
{"entityType": "order", "id": "ord_10293"}
```

```sh
./gradlew :dataset-generator:run --args="\
  --input deletions.jsonl \
  --output /data/deletions \
  --generator-version 3.2.1 \
  --dataset-version 2026-09-06T17:00:00Z"
```

| Option | |
| --- | --- |
| `--input`, `-i` | JSONL feed (required) |
| `--output`, `-o` | dataset directory, created if absent (required) |
| `--generator-version` | recorded in the manifest; must begin with a digit (required) |
| `--dataset-version` | ISO-8601 timestamp; defaults to now |
| `--bloom-fpr` | Bloom-filter target false-positive rate in `(0, 1]`; defaults to `0.01`. `1.0` writes no filter |
| `--help`, `--version` | |

The pipeline groups by entity type, validates and UTF-8-encodes each identifier, sorts by encoded
bytes, drops duplicates, writes one packed file per type plus `manifest.json`, then **self-validates**
by loading the result with `DeletionChecker`. A given input always produces byte-identical output.
Malformed input exits `2` with a one-line message naming the offending line.

## Contributing

Building, testing, and repo layout are in [`DEVELOPMENT.md`](DEVELOPMENT.md).
