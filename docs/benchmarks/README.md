# Benchmarks

Two local-only micro-benchmarks in the `benchmarks/` Gradle module
(`dependsOn(:lib, :dataset-generator)`, all `@Tag("bench")`, never in `check` or CI). Datasets are
built through the real `DatasetGenerator` → `DeletionChecker.load` path; the `benchmark` task runs
with no JaCoCo agent so instrumentation does not distort the timings.

1. **`ComparativeBenchmarkTest`** → [`reference.md`](reference.md) — `DeletionChecker` vs a plain
   `HashSet<String>` baseline, over identifier shape × set size × entity-type count.
2. **`BucketSizeBenchmarkTest`** → [`bucket-size.md`](bucket-size.md) — packed set only, comparing
   prefix-index bucket sizes `K` ∈ {128 … 4096} × shape × entity-type count.

Both interpreted in [`analysis.md`](analysis.md).

## Running

```
./gradlew :benchmarks:benchmark                        # both suites, full default run
./gradlew :benchmarks:benchmark -Dbench.publish=true    # …and overwrite the committed .md files
./gradlew :benchmarks:benchmark --tests '*BucketSize*'  # just the K sweep
./gradlew :benchmarks:benchmark --tests '*Comparative*' \
  -Dbench.sizes=1000,100000 -Dbench.shapes=uuid -Dbench.typeCounts=1 -Dbench.measured=50000
```

| Flag | Default | |
| --- | --- | --- |
| `-Dbench.shapes` | `uuid,alnum16,customer` | identifier shapes (see below) |
| `-Dbench.sizes` | `1000,10000,100000,1000000,10000000` | deleted-set size per entity type, sweep A |
| `-Dbench.typeCounts` | `1,5,10` | entity-type counts, sweep B |
| `-Dbench.typeSweepSize` | `1000000` | identifiers per type, comparative sweep B |
| `-Dbench.measured` | `500000` | timed calls per direction, comparative sweep |
| `-Dbench.bloomFpr` | `0.01` | Bloom-filter target FPR for generated datasets; `1.0` disables the filter (see [`analysis.md`](analysis.md) §6) |
| `-Dbench.xmx` | `7g` | benchmark JVM heap — raise to `8g`+ if the 10M cells OOM |
| `-Dbench.publish` | `false` | also overwrite the committed `docs/benchmarks/*.md` |
| `-Dbench.k.values` | `128,256,512,1024,2048,4096` | bucket sizes to compare |
| `-Dbench.k.shapes` | `uuid,customer,alnum16` | shapes for the K sweep |
| `-Dbench.k.typeCounts` | `1,3,5` | entity-type counts for the K sweep |
| `-Dbench.k.size` | `1000000` | identifiers per type, K sweep |
| `-Dbench.k.measured` | `500000` | timed calls per direction, K sweep |

Output: `benchmarks/build/reports/benchmarks/*` always; the matching `docs/benchmarks/*.md` when
`-Dbench.publish=true` (the comparative report needs both its sweeps to have run).

[`analysis.md`](analysis.md) interprets the committed results — update it when regenerating.

## Identifier shapes

| Shape | Example | Bytes | Stresses |
| --- | --- | ---: | --- |
| `uuid` | `f81d4fae-7dec-11d0-a765-00a0c91e6bf6` | 36 | wide, high-entropy keys |
| `alnum16` | `k3Jd0Pq7XcV1mR8w` | 16 | compact opaque tokens |
| `customer` | `customer-a1b2c3` | 15 | a 9-byte shared prefix → deep prefix-index descent |

## Sweeps

### Comparative (`reference.md`)

**A — per-type scaling** (1 entity type): shape × size × {`hashset`, `packed`}. The primary
latency-and-bytes/id comparison.

**B — entity-type-count scaling**: `typeCount` ∈ {1, 5, 10}, `typeSweepSize` identifiers each, shape
rotated across types. Measures how `DeletionChecker`'s map lookup, `load` time, and heap scale with
type count, and includes a **selective-load** row (`packed (1 of N)`) — loading one type out of N.

### Bucket size K (`bucket-size.md`)

`K` ∈ {128, 256, 512, 1024, 2048, 4096} × shape × `typeCount` ∈ {1, 3, 5}, at a fixed
`bench.k.size` identifiers per type. The identifier set is generated once per (shape, typeCount) and
every `K` is measured against it. Packed-only — cells are compared against each other, and against
the current default (`PrefixIndex.DEFAULT_BUCKET_SIZE = 128`). Larger `K` ⇒ fewer buckets ⇒ a
smaller, more cache-resident separator array (stage 1) but a wider in-bucket binary search (stage 2);
this looks for the knee.

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
  pages are reclaimable under pressure.
- **Build cost** — `packed generate` is the whole `DatasetGenerator.generate` call, which itself
  self-validates by loading the result once; `packed load` is a *second*, separate
  `DeletionChecker.load`.
- Numbers are machine- and run-specific. `reference.md` carries a header block naming the JVM, OS,
  and CPU it was produced on; regenerate and re-commit it when the implementation changes materially.
