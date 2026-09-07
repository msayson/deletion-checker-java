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
| D13 | Per-file checksum is JDK `java.util.zip.CRC32C`, not hand-rolled xxHash64 | Zero hand-rolled hash code to own; hardware-accelerated; purpose-built for corruption/truncation detection. 32-bit is enough for a non-adversarial threat model (DESIGN §5.5). Manifest keeps SHA-256. Header `checksum` field is 4 bytes |

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
      Crc32c.java                 one-shot CRC32C over byte[] / ByteBuffer (wraps java.util.zip.CRC32C)
      Sha256.java                 MessageDigest wrapper
      Checksums.java              CRC32C-with-field-zeroed helper (DESIGN §5.5)

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

### [x] B2 — Checksums

- `Crc32c` — one-shot `of(byte[])` / `of(ByteBuffer)` over `java.util.zip.CRC32C`;
  buffer overload leaves position/limit untouched. Also the single place that
  documents CRC32C as the per-file algorithm.
- `Sha256` — `MessageDigest` wrapper, `of(byte[])`, for the manifest.

**Tests:** CRC32C against the standard check value (`"123456789"` → `0xE3069283`)
and empty → `0`; `of(ByteBuffer)` == `of(byte[])`; partial buffer; position/limit
restored. SHA-256 against the FIPS 180-2 `"abc"` and empty vectors.
**Risk:** was the top correctness risk when the hash was hand-rolled; now
JDK-backed, so the tests validate our usage (unsigned value, buffer handling), not
the algorithm.

### [x] B3 — PrefixIndex

- Build from sorted, deduped `List<byte[]>`: `bucketCount = max(1, ceil(n / K))`,
  `K` a parameter (`DEFAULT_BUCKET_SIZE = 128`, the generator's launch default;
  runtime reads actual `K` from the header). Produce the on-disk representation
  directly: `startIndex[bucketCount+1]` (last `= n`), `separatorOffset[bucketCount+1]`,
  `separatorData` (packed first-identifier bytes).
- `selectBucket(byte[] q) -> int` — floor/predecessor search (DESIGN §5.4: greatest
  `i` with `separator[i] ≤ q`, else 0).
- `n == 0` → one bucket with an empty separator (`startIndex`/`separatorOffset`
  both `[0, 0]`, `separatorData` empty), so build/serialize/search stay uniform and
  the §5.4 zero-identifier short-circuit is only an optimization.
- Entry range for a query is `bucketStart(bucket)` / `bucketEnd(bucket)` (reading
  the `startIndex` table, a separate block from separators per §5.2) — no composite
  `rangeFor`. `startIndex()` / `separatorOffset()` / `separatorData()` expose
  defensive copies of the tables for B5.

**Tests:** §5.4 example `[A,B,C][D,E,F][G,H,I]` — exact on-disk shape, and `q` at /
between / before / after separators; `n` = 0, 1, ≤K, K, K+1; single-arg default;
buckets ≈ K; unsigned byte-wise comparison with supplementary chars; non-positive
`K` rejected; accessors copy. (All-identical ids is a generator/dedup concern, not
reachable here post-dedup.)

### [x] B4 — Binary format: header + checksum helper

- `PackedFileFormat` (package-private) — magic (`0x89 'D' 'C' 'S'`),
  `FORMAT_VERSION = 1`, little-endian constant, and the byte offset of every fixed
  header field. Fixed 92-byte header: magic(4) · formatVersion(4) ·
  entityTypeLength(4) · entityType(64, ASCII zero-padded) · identifierCount(4) ·
  bucketSize(4) · bucketCount(4) · checksum(4).
- `Header` record (package-private) — `formatVersion`, `entityType` (1..64 ASCII
  bytes), `identifierCount`, `bucketSize`, `bucketCount`, `checksum` (CRC32C,
  unsigned in a `long`). Absolute-indexed little-endian `writeTo` / `readFrom`;
  neither touches the buffer's position/limit.
- `CorruptDatasetException` / `UnsupportedFormatVersionException` (public,
  unchecked) — the §10 error taxonomy split; the latter carries `found` /
  `supported`.
- `Checksums.crc32cWithFieldZeroed(buffer, fieldOffset, fieldLen)` — the §5.5
  three-segment stream (before / `fieldLen` zero bytes / after), no modified copy;
  treats the buffer's `[0, limit)` as the file.

**Tests:** round-trip incl. exactly-64 entityType and unsigned checksum;
byte-exact little-endian layout; `writeTo` leaves position untouched; constructor
rejects null / empty / >64 / non-ASCII entityType; `readFrom` maps bad magic →
corrupt, unknown `formatVersion` → version mismatch (with `found`/`supported`),
out-of-range / non-ASCII entityType field → corrupt. `Checksums`: field-zeroed
CRC32C == hashing a modified copy (field at start / middle / end); honours
`limit` as end-of-file; buffer position/limit unchanged.

### [x] B5 — PackedFileWriter

- `PackedFileWriter.write(entityType, List<byte[]>, [bucketSize])` (public — the
  generator and fixtures call it; never on the lookup path) → `byte[]`.
- Input must be strictly ascending by unsigned bytes: `write` **verifies** this
  (one O(n) pass) and throws `IllegalArgumentException` on a mis-sort or a
  duplicate — a hard check, not `assert`, since a bad file would otherwise fail
  only at query time. `entityType` / `bucketSize` validation delegates to `Header`
  / `PrefixIndex`.
- Emits, in order: header · `startIndex` · `separatorOffset` · `separatorData` ·
  identifier offset table (`N+1`) · identifier data. All three offset tables are
  int32 LE; identifier offsets are block-relative. Then `crc32cWithFieldZeroed`
  over the whole buffer, patched into the header.
- `OffsetTableBuilder.cumulativeOffsets` (package-private) lands here.

**Tests:** write → parse the long way (not via the not-yet-existing reader),
assert every section against `PrefixIndex` + the raw bytes; `n` = 0 / 1 /
multi-bucket; exact file length (no trailing bytes); checksum self-verifies;
identifier offsets strictly monotonic and each slice reconstructs its identifier;
`separatorData` sits after both int tables, not interleaved; default-`K` overload
matches explicit; mis-sort / duplicate / bad entityType / bad `K` rejected.

### [x] B6 — PackedDeletionSet + BinarySearch

- `PackedDeletionSet.open(Path, String expectedEntityType)` — `FileChannel.map` →
  `MappedByteBuffer` (channel closed straight after; mapping survives). Validation
  order: `Header.readFrom` (magic → corrupt, `formatVersion` → version mismatch)
  → whole-file CRC32C → `entityType` == expected (→ corrupt) → structural parse.
  A file shorter than the header → corrupt. `IOException` propagates for a
  missing / unreadable file.
- The small prefix-index tables (`startIndex`, `separatorOffset`, `separatorData`)
  are lifted onto the heap and handed to `PrefixIndex.fromParts` (new factory) —
  they are hot and reused every query; the large identifier offset table and
  identifier data stay in the mapping.
- `contains(byte[] id) -> boolean` — `identifierCount == 0` short-circuits to
  `false`, else `PrefixIndex.selectBucket` → `BinarySearch` over the bucket's
  range. All buffer access is absolute-index, no mutable state → safe for
  concurrent callers. Also exposes `entityType()` / `identifierCount()`.
- `BinarySearch.contains` (package-private) — floor-free exact match, unsigned
  byte compare via `UnsignedBytes` against the mmap'd data, zero allocation.

**Tests:** `BinarySearchTest` over a hand-built buffer (every position, misses
before/between/after, prefix vs full match, empty / single range).
`PackedDeletionSetTest` (fixtures via B5): 500-id / many-bucket hits+misses;
empty → `false`; single id; supplementary-char ids incl. a near-miss; 3000-id
`TreeSet` oracle for random membership; 8-thread concurrent `contains`; flipped
byte / truncation (head and tail) → corrupt; bad magic → corrupt; patched
`formatVersion` → `UnsupportedFormatVersionException` (before CRC); entityType
mismatch → corrupt; missing file → `IOException`. (All-identical ids is a
generator/dedup concern, not reachable here.)

### [x] B7 — DatasetManifest

- `ManifestJson` (package-private) — strict recursive-descent JSON reader →
  `Map` / `List` / `String` / `Long` tree. Rejects escape sequences, floats,
  `true` / `false` / `null`, duplicate keys, control chars in strings, leading
  zeros, and trailing content — every malformed input throws
  `InvalidManifestException`, never a raw runtime exception.
- `DatasetManifest` — a `record` (`formatVersion`, `datasetVersion`,
  `generatorVersion`, `entityTypes`) with `SUPPORTED_FORMAT_VERSION = 1`.
  `parse` maps the tree strictly: required keys, exact types, **no unknown keys**
  (top level and per entry), no duplicate `entityType`, `identifierCount >= 0`.
  Order: parse → **version check first** (unknown → `UnsupportedManifestVersionException`
  with `found`/`supported`, before the checksum, since a v2 schema would fail
  canonicalization anyway) → `manifestChecksum` verify → build. `read(Path)`
  wraps `parse` over `Files.readString` (UTF-8); `IOException` propagates.
  `entry(String) -> Optional`.
- `ManifestCanonicalizer.canonicalize(DatasetManifest)` — keys ascending, no
  whitespace, no `manifestChecksum`. String values must not contain a quote,
  backslash, or control char (`IllegalArgumentException`) — keeps the canonical
  form escape-free.
- `ManifestWriter.write(DatasetManifest)` — canonical form + a trailing
  `manifestChecksum` (`"sha256:" + hex(SHA-256(canonical))`).
- `EntityTypeEntry` — public record, validates non-null fields + non-negative
  count.

**Tests:** `ManifestJsonTest` — the §5.1 sample shape, every-kind whitespace, and
~25 crafted malformed inputs so **every parser `throw` is covered** (100%
line + branch, no exclusions). `DatasetManifestTest` — write→read round-trip;
`read` from a file; missing file → `IOException`; whitespace-insensitive
verification (pretty-printed file still checks out); checksum mismatch;
unknown `formatVersion` (found/supported); every missing / wrong-typed / unknown
key (top level and per entry); non-object root; duplicate entityType; negative
count; empty `entityTypes`. Plus `ManifestCanonicalizerTest` (exact bytes,
ordering, determinism, escape rejection) and `ManifestWriterTest`
(checksum-over-canonical, round-trip).

### [x] B8 — DeletionChecker.load + isDeleted + accessors

- `DatasetManifest.FILE_NAME = "manifest.json"` — the manifest's fixed name in a
  dataset dir (DESIGN §8.3's `manifest-<date>.dat` example is stale; the manifest
  is JSON per §5.1 and B10's writer will emit this name).
- `PackedDeletionSet.checksum()` — exposes the header CRC32C that `open` already
  verified against the file bytes, so `load` can chain it to the manifest.
- `load(Path datasetDirectory, Set<String> entityTypes)`:
  1. `DatasetManifest.read(dir/manifest.json)` — version-then-checksum verify
     (B7); `IOException` propagates for a missing / unreadable manifest.
  2. Resolve **all** requested types against the manifest before any file I/O;
     one absent → `IllegalArgumentException` naming it.
  3. Per resolved type: open `dir/entry.fileName()` (bare name, B7-validated) with
     `PackedDeletionSet.open` (magic / `formatVersion` / whole-file CRC / header
     `entityType` vs the manifest spelling), then check the file CRC equals the
     manifest entry's `crc32c:<hex>`. Mismatch → `CorruptDatasetException`;
     `open`'s own failures (`CorruptDatasetException` /
     `UnsupportedFormatVersionException`) propagate with distinct types + messages
     (§10). Any failure fails the load; partial state is dropped.
  4. `Map.copyOf` the `entityType -> PackedDeletionSet` map; capture
     `manifest.datasetVersion()` and `loadedAt = Instant.now()`.
  5. Unrequested types are never opened (proven by a test with a garbage file for
     an unrequested type).
- Class becomes `final` with a private constructor + the `load` factory; the
  old placeholder public constructor is gone; `DeletionCheckerTest` rebuilt on
  writer-produced fixtures.
- `isDeleted(entityType, id)` — `sets.get` (miss → IAE "not requested"; after a
  fail-fast `load` the map key set **is** the requested set, so no separate Set is
  kept) → `IdentifierCodec.encode` (bad id → IAE) → `PackedDeletionSet.contains`.
- `filter` still throws `UnsupportedOperationException` — implemented in B9.
- `datasetVersion() -> String`, `loadedAt() -> Instant`.

**Tests:** `DeletionCheckerTest` — multi-type membership hits/misses; unrequested
type's file unreadable → `load` still succeeds; unknown type → IAE; missing
manifest → `IOException`; corrupt requested file → `CorruptDatasetException`;
patched `formatVersion` → `UnsupportedFormatVersionException`; file CRC ≠ manifest
CRC → `CorruptDatasetException`; not-requested type at `isDeleted` → IAE; null /
empty / over-long / unpaired-surrogate id → IAE; empty requested set opens
nothing; `datasetVersion` / `loadedAt` correct; `filter` → UOE. 100% line + branch.

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
