# Design decisions

Invariants and rationale for `deletion-checker-java` that [`DESIGN.md`](DESIGN.md) (architecture,
binary layout) doesn't cover — the "why we chose X" record. The batch-by-batch build plan that
originally lived in this file is in git history (up to commit `f6398d9`, "Implement bloom filter
frontend").

---

## 1. Invariants

- **`lib` has zero runtime dependencies** — only `testImplementation`. Enforced by
  a build check asserting an empty `runtimeClasspath`. This is the concrete
  guarantee behind "minimal footprint for hundreds of consumers."
- **One format owner.** All binary/manifest layout — constants, field offsets,
  endianness, and both read *and* write of every structure — lives in `lib`
  (`format/`, `manifest/`). A format change bumps the header version in one place.
- **`dataset-generator` is orchestration only** — input sources, the
  group/validate/sort/dedup pipeline, the CLI. It `dependsOn(lib)` and calls
  `lib`'s writers. It may take its own dependencies; none reach `lib`.
- **Representations are final on first introduction** — e.g. `PrefixIndex` is built
  in its on-disk shape, so serialization is a byte copy, never a reshape.
- **Buffer access is absolute-indexed** (`buf.get(i)`), so mmap'd structures are
  thread-safe with no per-call `duplicate()`.
- **Every change builds green and holds ≥90% line *and* branch coverage**, per
  module, hard-fail (`./gradlew build`).

---

## 2. Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | Hand-rolled zero-dep JSON parser for the manifest | Lowest overhead; no dependency-conflict risk across consumers; fixed shallow schema; SHA-256 catches corruption regardless |
| D2 | Writers (`PackedFileWriter`, `OffsetTableBuilder`, `ManifestWriter`) live in `lib`, not the generator | Keeps "one format owner" literal and lets `lib`'s own tests build fixtures with no circular module dependency. The writer classes are loaded only by the generator — ~150 LOC, ~10 KB jar, zero runtime memory/startup cost |
| D3 | `datasetVersion()` returns the raw ISO8601 `String` | Matches DESIGN §5.1; no parsing on the read path |
| D4 | 4-byte little-endian offsets in the Identifier Offset Table | ~2 GB / ~33M max-length (64-byte) ids per file, more for shorter ids — far beyond the 10M target. Sharding (DESIGN §12) covers anything larger. Fixed now: widening later is a format-version bump |
| D5 | `DeletionChecker` maps for process lifetime; **not** `AutoCloseable` | Dataset is immutable and lives as long as the process. `MappedByteBuffer` has no explicit unmap; documented as off-heap RSS for consumers (DESIGN §9.1). Windows keeps the file locked until GC — acceptable for a process-lifetime artifact |
| D6 | JSONL generator input: flat `{"entityType": "...", "id": "..."}`, one per line | Simple, streamable, trivially produced from any deletion source |
| D7 | Reject null and empty identifiers (`IllegalArgumentException`) | No meaningful empty ID |
| D8 | CLI arg parsing: picocli (generator only) | Collapses `main`, frees help/usage/validation; one jar, zero transitives; `lib` untouched |
| D9 | Checkstyle 14.x, shared config, wired into `check` | Enforces existing conventions; ruleset tightens as code lands |
| D10 | Perf tests `@Tag("perf")` + `perfTest` task, manual for now | CI gating is a tracked follow-up |
| D11 | `data/` kept, contents gitignored | Generated `.dat` files reach ~400 MB — release artifacts, not source |
| D12 | `lib` jar sets `Automatic-Module-Name: com.marksayson.deletionchecker` | Table stakes for a widely-consumed library |
| D13 | Per-file checksum is JDK `java.util.zip.CRC32C`, not hand-rolled xxHash64 | Zero hand-rolled hash code to own; hardware-accelerated; purpose-built for corruption/truncation detection. 32-bit is enough for a non-adversarial threat model (DESIGN §5.5). Manifest keeps SHA-256. Header `checksum` field is 4 bytes |
| D14 | Bloom-filter frontend is a hand-rolled **blocked** filter in `lib` (mmap'd), **not** Guava `BloomFilter` | Deliberately re-evaluated the zero-runtime-dep invariant for this feature. Every mature Bloom library (Guava, commons-collections) is **on-heap**: a ~1.5 B/id `long[]` — ~15 MB for a 10M-id type, ~150 MB across ten types — reintroduces the GC-scan and container-memory cost the packed format exists to eliminate (what the benchmarks in `docs/benchmarks/` confirm), and can't be memory-mapped from the dataset file. Guava on the runtime classpath of hundreds of services is exactly the diamond-dependency hazard the zero-dependency invariant exists to prevent. A blocked Bloom filter is ~150 LOC of textbook algorithm sitting next to `PrefixIndex` / `BinarySearch`, and being cache-blocked (1 cache line/probe) it is *faster* than Guava's non-blocked `mightContain` (k scattered lines) — which is the point of the feature. The one thing a library saves (m/k math + a vetted hash) is a day of work and one test file. **Invariant holds.** |
| D15 | Identifier length cap is 64 UTF-8 bytes (`IdentifierCodec.MAX_IDENTIFIER_BYTES`), raised from 36 | Policy, not structural — verified nothing in the format or lookup path references the value; the offset table is variable-length and byte-agnostic. 64 fits a hyphenated UUID (36 B) plus namespaced/composite keys and matches the entity-type cap. Cost: ~2× prefix-index separator heap (still ~5 MB per 10M-id type). Backward compatible — strictly more permissive; no format-version bump. Revisable the same way |
| D16 | SpotBugs (effort MAX), shared config, wired into `check` | Static-analysis complement to Checkstyle's style gate — catches correctness classes (null-deref, ignored return values, resource leaks) linting doesn't. Zero findings tolerated; confirmed false positives go in `config/spotbugs/exclude.xml`, scoped narrowly, not silenced in source |

---

## 3. Change checklist

Every change to `lib` or `dataset-generator`:

1. `./gradlew build` green.
2. Coverage ≥ 90% line **and** branch, per module.
3. `lib` `runtimeClasspath` empty (`checkNoRuntimeDependencies`).
4. New/changed behaviour has tests in the matching `src/test/java`.
5. No format-layout logic duplicated between modules.
6. Binary-format changes bump `formatVersion` and keep the runtime reading the prior version.
