# Implementation Plan

Iterative build-up of `deletion-checker-java` from `DESIGN.md`. Each batch is one
reviewable PR that builds green, holds ≥90% line **and** branch coverage
(per module, hard-fail), and adds no code a later batch must remove.

---

## 1. Non-negotiables

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

---

## 2. Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | Hand-rolled zero-dep JSON parser for the manifest | Lowest overhead; no dependency-conflict risk across consumers; fixed shallow schema; SHA-256 catches corruption regardless |
| D2 | Writers (`PackedFileWriter`, `OffsetTableBuilder`, `ManifestWriter`) live in `lib`, not the generator | Keeps "one format owner" literal and lets `lib`'s own tests build fixtures with no circular module dependency. The writer classes are loaded only by the generator — ~150 LOC, ~10 KB jar, zero runtime memory/startup cost |
| D3 | `datasetVersion()` returns the raw ISO8601 `String` | Matches DESIGN §5.1; no parsing on the read path |
| D4 | 4-byte little-endian offsets in the Identifier Offset Table | ~2 GB / ~59M max-length ids per file — far beyond the 10M target. Sharding (DESIGN §12) covers anything larger. Fixed now: widening later is a format-version bump |
| D5 | `DeletionChecker` maps for process lifetime; **not** `AutoCloseable` | Dataset is immutable and lives as long as the process. `MappedByteBuffer` has no explicit unmap; documented as off-heap RSS for consumers (DESIGN §9.1). Windows keeps the file locked until GC — acceptable for a process-lifetime artifact |
| D6 | JSONL generator input: flat `{"entityType": "...", "id": "..."}`, one per line | Simple, streamable, trivially produced from any deletion source |
| D7 | Reject null and empty identifiers (`IllegalArgumentException`) | No meaningful empty ID |
| D8 | CLI arg parsing: picocli (generator only) | Collapses `main`, frees help/usage/validation; one jar, zero transitives; `lib` untouched |
| D9 | Checkstyle 14.x, shared config, wired into `check` | Already onboarded. Enforces existing conventions; ruleset tightens as code lands |
| D10 | Perf tests `@Tag("perf")` + `perfTest` task, manual for now | CI gating is a tracked follow-up |
| D11 | `data/` kept, contents gitignored | Generated `.dat` files reach ~400 MB — release artifacts, not source |
| D12 | `lib` jar sets `Automatic-Module-Name: com.marksayson.deletionchecker` | Table stakes for a widely-consumed library |

---

## 3. Module layout (target)

```
lib/                             runtime checker — ZERO runtime deps
  src/main/java/com/marksayson/deletionchecker/
    DeletionChecker.java          public API: load, isDeleted, filter, datasetVersion, loadedAt
    IdentifierCodec.java          String -> UTF-8 bytes, validation
    UnsignedBytes.java            lexicographic byte comparison
    format/
      PackedFileFormat.java       magic, FORMAT_VERSION, little-endian + field-offset constants
      Header.java                 record + readFrom / writeTo
      PrefixIndex.java            on-disk-shape index: build + floor/predecessor search
      OffsetTableBuilder.java     cumulative byte offsets                (generator-only)
      PackedFileWriter.java       serialize header + index + table + data, patch checksum  (generator-only)
      BinarySearch.java           lexicographic byte search over offset table + data
      PackedDeletionSet.java      mmap one entity-type file, verify, contains(byte[])
    manifest/
      ManifestJson.java           scoped strict JSON reader
      ManifestCanonicalizer.java  stable key order / whitespace
      ManifestWriter.java         canonical JSON + SHA-256                (generator-only)
      DatasetManifest.java        parse + verify + entity-type lookup
    checksum/
      XxHash64.java               streaming + one-shot
      Sha256.java                 MessageDigest wrapper
      Checksums.java              hash-with-field-zeroed helper (DESIGN §5.5)

dataset-generator/               build-time tool — dependsOn(lib), may take deps (picocli)
  src/main/java/com/marksayson/deletionchecker/generator/
    DeletionSource.java           input abstraction
    JsonlDeletionSource.java      read {entityType, id} per line
    DatasetGenerator.java         pipeline: group -> validate -> sort -> dedup -> write -> self-validate
    GeneratorCli.java             picocli entry point

data/                            generator output — CONTENTS GITIGNORED
docs/                            DESIGN.md, IMPLEMENTATION_PLAN.md
```

Test fixtures are built programmatically via `lib`'s own `PackedFileWriter` /
`ManifestWriter`; no binary files are checked in.

---

## 4. Batches

`[x]` = merged and green · `[ ]` = not started.

### [x] B0 — Build scaffolding

- JaCoCo on `lib`; `jacocoTestCoverageVerification` (90% LINE + BRANCH, hard-fail)
  bound into `check`.
- Build check: `lib` `runtimeClasspath` resolves empty.
- `lib` jar manifest: `Automatic-Module-Name`.
- `isDeleted` / `filter` stubs throw `UnsupportedOperationException`; keep the
  documented signatures.
- Checkstyle already wired — keep green each batch.

**DoD:** `./gradlew build` green; coverage + zero-dep checks active.
**Tests:** stubs throw.

### [x] B1 — Identifier primitives

- `IdentifierCodec.encode(String) -> byte[]` — UTF-8; reject null, empty, `>36`
  encoded bytes, unpaired surrogates (char scan before encode). Rejections are
  `IllegalArgumentException` naming the violation.
- `MAX_IDENTIFIER_BYTES = 36`.
- `UnsignedBytes.lexicographicalCompare(...)` over `byte[]` / `ByteBuffer` ranges.

No `decode` — nothing on the read path or in the generator reconstructs a `String`;
add it only when a caller needs it.

**Tests:** ASCII; multi-byte; supplementary (surrogate pair); 36 vs 37 bytes;
null; empty; unpaired high / low surrogate. Comparator: UTF-8 vs `String.compareTo`
divergence on supplementary chars; prefix vs longer; equal; bytes ≥ 0x80.

### [ ] B2 — Checksums

- `XxHash64` — hand-rolled: streaming `update(ByteBuffer)` + `digest()` (buffers
  the sub-32-byte stripe tail across calls), plus one-shot.
- `Sha256` — `MessageDigest` wrapper.

**Tests:** xxHash64 published vectors (empty, `"abc"`, `>32` bytes for the 4-lane
loop, non-zero seed); streaming == one-shot; chunk-boundary invariance. SHA-256
known vector.
**Risk:** top correctness risk — hence its own batch, tested against published
vectors.

### [ ] B3 — PrefixIndex

- Build from sorted, deduped `List<byte[]>`: `bucketCount = max(1, ceil(n / K))`,
  `K = 128` (parameter). Produce the on-disk representation directly:
  `startIndex[bucketCount+1]` (last `= n`), `separatorOffset[bucketCount+1]`,
  `separatorData` (packed first-identifier bytes).
- `selectBucket(byte[] q) -> int` — floor/predecessor search (DESIGN §5.4: greatest
  `i` with `separator[i] ≤ q`, else 0).
- `n == 0` → `bucketCount = 1`, empty separator array.
- `rangeFor(q) -> [start, end)` — reads `startIndex` lazily (separate block from
  separators per §5.2).

**Tests:** §5.4 example `[A,B,C][D,E,F][G,H,I]` — `q` at / between / before / after
separators; `n` = 0, 1, ≤K, K, K+1; all-identical ids; buckets ≈ K; byte-wise
comparison with supplementary chars.

### [ ] B4 — Binary format: header + checksum helper

- `PackedFileFormat` — magic, `FORMAT_VERSION = 1`, little-endian + field-offset
  constants.
- `Header` record — `formatVersion`, `entityType` (≤64 ASCII bytes),
  `identifierCount`, `bucketSize`, `bucketCount`, `checksum` — explicit
  little-endian `writeTo` / `readFrom`.
- `Checksums.hashWithFieldZeroed(buffer, fieldOffset, fieldLen)` — the §5.5
  three-segment stream, no modified copy.

**Tests:** round-trip; byte-exact endianness; `entityType` `>64` bytes rejected;
bad magic; unrecognized `formatVersion`; zeroed-field hash == manual reference.

### [ ] B5 — PackedFileWriter

- Input: `entityType` + an **already sorted and deduped** `List<byte[]>` + `K`
  (precondition asserted).
- Emit, in order: header, prefix index (B3 struct), identifier offset table
  (`N + 1` LE offsets), identifier data. Compute xxHash64 with the checksum field
  zeroed, patch it into the header.
- `OffsetTableBuilder` lands here.

**Tests:** write → parse back, assert every section; `n` = 0, 1, multi-bucket;
checksum self-verifies; offsets strictly monotonic; `separatorData` contiguous,
not interleaved with `startIndex`. Structural only — membership is B6's job via
the real reader.

### [ ] B6 — PackedDeletionSet + BinarySearch

- `PackedDeletionSet.open(Path)` — `FileChannel.map` → `MappedByteBuffer`; validate
  magic / recognized `formatVersion` / header `entityType`; verify the file
  checksum; expose read-only region views. The `FileChannel` may be closed after
  mapping.
- `contains(byte[] id) -> boolean` — `PrefixIndex.selectBucket` over the mmap'd
  separators → `BinarySearch` in the bucket's entry range. `identifierCount == 0`
  short-circuits to `false`.
- `BinarySearch` — lexicographic byte compare, zero allocation, absolute indexing.
- Error taxonomy (§10): unrecognized `formatVersion` → distinct version-mismatch
  message; checksum / `entityType` mismatch → "corrupted".

**Tests** (fixtures via B5): hits and misses across multiple buckets; boundary
queries; empty → `false`; single id; all-identical; supplementary-char ids;
flipped byte → checksum failure; truncated file; bad magic; unrecognized
`formatVersion` → distinct message; header `entityType` ≠ expected; concurrent
`contains` from multiple threads.

### [ ] B7 — DatasetManifest

- `ManifestJson` — scoped strict recursive-descent reader for the fixed schema
  (object of string/int fields + one array of flat objects); rejects unexpected
  structure.
- `ManifestCanonicalizer` — stable key order, no incidental whitespace.
- `ManifestWriter` — canonical JSON + `manifestChecksum` (SHA-256 over the
  canonical form with that field omitted).
- `DatasetManifest.read(Path)` — parse; verify `manifestChecksum`; check
  `formatVersion` recognized. Model: three version fields + entries
  `(entityType, fileName, identifierCount, checksum)`. `entry(String) -> Optional`.

**Tests:** parse the §5.1 sample; write → read round-trip; checksum match and
mismatch; unknown `formatVersion` → version message; missing-type lookup empty;
malformed JSON; missing required field; canonicalizer determinism.
**Risk:** parser error branches against the 90% branch gate — structure every
`throw` to be reachable from a malformed-input test rather than leaning on coverage
exclusions.

### [ ] B8 — DeletionChecker.load + isDeleted + accessors

- `load(Path datasetDir, Set<String> entityTypes)`:
  1. Read + verify the manifest; verify `formatVersion`.
  2. Requested type absent from the manifest → `IllegalArgumentException` naming it.
  3. Per present requested type: resolve `fileName` under `datasetDir`, open,
     verify `formatVersion` + header `entityType` vs manifest + file checksum, mmap.
  4. Build `Map<String, PackedDeletionSet>`; capture `datasetVersion` and
     `loadedAt = Instant.now()` on full success.
  5. Any file failure → fail fast; "corrupted" vs "version mismatch" → distinct
     messages (§10).
  6. Unrequested types are never opened.
- Replace the placeholder no-arg constructor with a private constructor + the
  `load` factory; migrate `DeletionCheckerTest`.
- `isDeleted(entityType, id)` — map lookup (miss → IAE, using the requested `Set`
  to distinguish "not requested" from "unknown") → `IdentifierCodec.encode` (bad
  id → IAE) → `contains`.
- `datasetVersion() -> String`, `loadedAt() -> Instant`.

**Tests:** corrupt file for a non-requested type → `load` still succeeds (proves
selective loading); unknown type at construction → IAE; not-requested type at
`isDeleted` → IAE; bad id → IAE; corrupted file → fail fast; version mismatch →
distinct message; `datasetVersion` / `loadedAt` correct; end-to-end membership on
a writer-built multi-type dataset.

### [ ] B9 — filter

- `<T> List<T> filter(String entityType, List<T> items, Function<T, String> idExtractor)`
  — resolve `entityType` once, then iterate: extract id → `isDeleted` → keep
  non-deleted, preserving input order.
- Prefix-bucket-reuse batching (§6.3) is a later additive optimization.

**Tests:** all / none / mixed deleted; empty list; input order preserved; null or
empty extracted id → IAE; not-requested `entityType` → IAE.

### [ ] B10 — dataset-generator module

- New Gradle module; `dependsOn(lib)`. Shared JaCoCo + Checkstyle + test config
  via a `build-logic` convention plugin (the Checkstyle config file is already
  shared).
- `info.picocli:picocli` (pinned) — generator only.
- `DeletionSource` + `JsonlDeletionSource` — `{"entityType": "...", "id": "..."}`
  per line.
- `DatasetGenerator` (DESIGN §8.2): group by type → validate (over-length /
  unpaired-surrogate / null / empty) → encode + sort by encoded bytes → dedup
  consecutive equal → `lib` `PackedFileWriter` per type → `lib` `ManifestWriter`
  → self-validate by calling `DeletionChecker.load(dir, allTypes)`.
- `GeneratorCli` — `--input` (JSONL), `--output` (dir), `--generator-version`,
  `--dataset-version` (optional, default `Instant.now()`), `--bucket-size`
  (optional, default 128); free `--help` / `--version`.

**Tests:** full pipeline on sample JSONL; dedup collapses duplicates and
`identifierCount` = unique count; sort order = UTF-8 byte order incl. supplementary
chars; each bad-identifier class rejected; self-validation catches a corrupted
write; generate → `load` → random-membership round-trip.

### [ ] B11 — Integration & performance suite + docs

- Integration tests (DESIGN §11): real multi-entity-type dataset with a partial
  selection; boundary datasets (empty, single identifier, all-identical, many
  duplicates); random membership validation per loaded type.
- Performance tests — `@Tag("perf")` via `perfTest`: p99.9 sub-millisecond lookup;
  negative-lookup latency baseline. Manual; CI gating tracked separately.
- Docs: README usage + the §9.1 note that dataset memory is off-heap, file-backed,
  and counts toward process RSS / container limits; resolve DESIGN §13 if anything
  changed.

---

## 5. Review order

**B0 → B1 → B2 → B3 → B4 → B5 → B6 → B7 → B8 → B9 → B10 → B11** (12 PRs).

Dependencies: B1 feeds B3; B2 feeds B4; B3 + B4 feed B5; B5 feeds B6; B2 feeds B7;
B6 + B7 feed B8; B8 feeds B9; B5 + B7 + B8 feed B10; everything feeds B11. B2 and
B7 can run in parallel with the B3–B6 line.

---

## 6. Per-batch definition of done

1. `./gradlew build` green.
2. Coverage ≥ 90% line **and** branch, per module.
3. `lib` `runtimeClasspath` empty.
4. New/changed behaviour has tests in the matching `src/test/java`.
5. No format-layout logic duplicated between modules.
6. No code introduced that a later batch removes.

---

## 7. Unresolved questions

None.
