# Benchmarks

Comparative micro-benchmark: `DeletionChecker` (packed set + mmap + two-level search) versus the
simplest possible baseline, a plain `HashSet<String>` per entity type.

Lives in the `benchmarks/` Gradle module (`dependsOn(:lib, :dataset-generator)`), all `@Tag("bench")`.
**Local only** — never runs in `check` or CI. Datasets are built through the real
`DatasetGenerator` → `DeletionChecker.load` path.

## Running

```
./gradlew :benchmarks:benchmark                       # full default sweep, ~15-25 min
./gradlew :benchmarks:benchmark -Dbench.publish=true   # …and overwrite reference.md
./gradlew :benchmarks:benchmark \                      # a quick subset
  -Dbench.sizes=1000,100000 -Dbench.shapes=uuid -Dbench.typeCounts=1 -Dbench.measured=50000
```

| Flag | Default | |
| --- | --- | --- |
| `-Dbench.shapes` | `uuid,alnum16,customer` | identifier shapes (see below) |
| `-Dbench.sizes` | `1000,10000,100000,1000000,10000000` | deleted-set size per entity type, sweep A |
| `-Dbench.typeCounts` | `1,5,10` | entity-type counts, sweep B |
| `-Dbench.typeSweepSize` | `1000000` | identifiers per type, sweep B |
| `-Dbench.measured` | `500000` | timed calls per direction (positive / negative) |
| `-Dbench.xmx` | `7g` | benchmark JVM heap — raise to `8g`+ if the 10M cells OOM |
| `-Dbench.publish` | `false` | also write `docs/benchmarks/reference.md` (requires both sweeps) |

Output: `benchmarks/build/reports/benchmarks/{results.csv, reference.md}` always;
`docs/benchmarks/reference.md` when `-Dbench.publish=true`.

[`analysis.md`](analysis.md) interprets the committed `reference.md` — update it when regenerating.

## Identifier shapes

| Shape | Example | Bytes | Stresses |
| --- | --- | ---: | --- |
| `uuid` | `f81d4fae-7dec-11d0-a765-00a0c91e6bf6` | 36 | wide, high-entropy keys |
| `alnum16` | `k3Jd0Pq7XcV1mR8w` | 16 | compact opaque tokens |
| `customer` | `customer-a1b2c3` | 15 | a 9-byte shared prefix → deep prefix-index descent |

## Sweeps

**A — per-type scaling** (1 entity type): shape × size × {`hashset`, `packed`}. The primary
latency-and-bytes/id comparison.

**B — entity-type-count scaling**: `typeCount` ∈ {1, 5, 10}, `typeSweepSize` identifiers each, shape
rotated across types. Measures how `DeletionChecker`'s map lookup, `load` time, and heap scale with
type count, and includes a **selective-load** row (`packed (1 of N)`) — loading one type out of N.

## Method & caveats

- **Latency** — per-call `System.nanoTime()`, 3 warm-up passes, `min(bench.measured, poolSize)`
  distinct probes sampled with replacement, sorted → p50 / p99 / p99.9 / max. Both implementations
  are timed against the identical probe arrays in one JVM, so JIT/GC state is shared.
  `packed contains()` rows time `PackedDeletionSet.contains(byte[])` with the probe **pre-encoded**,
  isolating the search from the `IdentifierCodec.encode` (one `byte[]` allocation) that `isDeleted`
  pays on every call and `HashSet.contains(String)` does not.
- **Memory** — GC-delta: settle with repeated full GCs (`-XX:+UseParallelGC`), read live heap
  before vs after building the structure while holding a strong reference. Accurate to *roughly*
  ±10%. `HashSet heap B/id` includes the `String` objects; `packed heap B/id` is only the loaded
  checker (prefix-index arrays + objects), and `packed mapped B/id` is the `.dat` file size — an
  upper bound on resident memory, since untouched pages are never faulted in and clean file-backed
  pages are reclaimable under pressure (DESIGN §9.1).
- **Build cost** — `packed generate` is the whole `DatasetGenerator.generate` call, which itself
  self-validates by loading the result once; `packed load` is a *second*, separate
  `DeletionChecker.load`.
- Numbers are machine- and run-specific. `reference.md` carries a header block naming the JVM, OS,
  and CPU it was produced on; regenerate and re-commit it when the implementation changes materially.
