# Deletion Library (Packed Set Architecture) Design

## **1. Overview**
This project provides a **local, exact deletion‑state lookup library** for services that need to determine whether an entity ID has been deleted. It avoids runtime network calls by distributing a **packed, immutable binary dataset** containing deleted IDs, split into one file per entity type.

The library exposes two primary query APIs:

```java
boolean isDeleted(String entityType, String id);
<T> List<T> filter(String entityType, List<T> items, Function<T, String> idExtractor);
```

Consumers select which entity types to load at construction time, so entity types they don't use incur no memory or startup cost:

```java
DeletionChecker checker = DeletionChecker.load(datasetDirectory, Set.of("user", "order"));
```

The underlying storage is a **packed set per entity type**: a sorted, binary representation of identifiers with a prefix index for fast lookup, plus a manifest describing the entity types available in a dataset release.

---

## **2. Goals**
- **Exact membership** (no false positives or negatives)
- **Sub‑millisecond p99.9 lookup latency**
- **Low GC overhead on membership-lookup hot path**
- **Immutable, versioned dataset distribution**
- **Scalable to ~10M deleted IDs**
- **Simple API for service owners**
- **Batch filtering support**
- **Selective loading** — consumers pay only for the entity types they request

---

## **3. Architecture Summary**

### **3.1 Components**
- **DeletionChecker**
  Public API used by services; constructed with the set of entity types to load. Holds a `Map<String, PackedDeletionSet>` built once at construction (§6.1) so `isDeleted`/`filter` never re‑resolve or re‑validate an entity type on the hot path — only a map lookup. Exposes `datasetVersion()` and `loadedAt()` for operational visibility into dataset freshness (§7.1).

- **DatasetManifest**
  Reads the manifest file: the release's `formatVersion`/`datasetVersion`/`generatorVersion` (§5.1) plus each entity type's file name, identifier count, and checksum. Used to validate requested entity types before any per‑type file I/O happens.

- **PackedDeletionSet**
  Loads and queries one entity type's packed binary file. One instance per loaded entity type.

- **PrefixIndex**
  Adaptive, equal‑count bucket index built from an entity type's realized sort order at generation time; maps a query key to a narrow entry‑index range within that file.

- **BinarySearch**
  Performs lexicographic byte‑comparison search over mmap'd identifier data.

- **IdentifierCodec**
  Converts identifier Strings to/from ASCII byte form for storage and comparison; validates ASCII‑only content and max length.

- **Dataset Generator**
  Build‑time tool that produces one packed binary file per entity type, plus the manifest.

---

## **4. Packed Set Representation**

### **4.1 Why Packed Set Instead of HashSet**
| Aspect | HashSet | Packed Set |
|-------|---------|------------|
| Memory | ~80 bytes/identifier (object overhead) | ≤36 bytes (UTF‑8) + 4‑byte offset entry, no object overhead |
| GC overhead | High | None (mmap) |
| Startup | Slow (millions of objects) | Fast (map file) |
| Artifact distribution | Poor | Excellent |
| Immutability | No | Yes |
| Scalability | Painful | Smooth to ~10M |

Packed sets are ideal for artifact distribution and local lookup at scale. Because each entity type is its own packed set, a consumer's memory footprint scales with the entity types it actually loads, not the full dataset.

---

## **5. Binary File Format**

### **5.1 Manifest File**
The manifest is a **JSON** file listing every entity type available in a dataset release — chosen for human readability and low implementation effort, since it's parsed once at construction and never on the lookup path:
```json
{
  "formatVersion": 1,
  "datasetVersion": "2026-09-06T17:00:00Z",
  "generatorVersion": "3.2.1",
  "entityTypes": [
    { "entityType": "user",  "fileName": "deleted-ids-user-2026-09-06.dat",  "identifierCount": 4213000, "checksum": "crc32c:9f3a1c7e" },
    { "entityType": "order", "fileName": "deleted-ids-order-2026-09-06.dat", "identifierCount": 812044,  "checksum": "crc32c:2a7cd0e1" }
  ],
  "manifestChecksum": "sha256:3b1e7a9c...d2f0"
}
```
It is read and its checksum verified once at construction, before any entity‑type file is opened. Being JSON, its fields are plain text — no byte‑order concerns apply to the manifest itself (see §5.2 for the binary files' byte order). `manifestChecksum` is a **SHA256** hash (§5.5) — the manifest is small and read once, so the algorithm's slower per‑byte cost doesn't matter, and it is the trust anchor the entity‑type files' weaker CRC32C checks chain from. It's computed over the JSON with the `manifestChecksum` field itself omitted (self‑reference: the field can't hash its own value), using a canonical serialization (stable key order, no incidental whitespace differences) so generation and verification always agree byte‑for‑byte.

The three version fields separate previously‑conflated concerns. `formatVersion` is the manifest's own JSON schema version — bumped only when the schema itself changes, never for routine data updates (a §5.4 `K` change never bumps it). Each entity‑type file carries its own, independent `formatVersion` for its binary layout (§5.2), since the JSON schema and the binary schema can evolve on separate timelines. `datasetVersion` identifies which generation run's deletion data this release is — an ISO8601 timestamp rather than a bare date, precise enough for the minute‑level staleness comparisons `loadedAt()` supports in §7.1; `DeletionChecker.datasetVersion()` returns this value. `generatorVersion` is the semver of the build‑time tool that produced the release, useful for tracing a bad artifact back to a specific generator bug.

### **5.2 Per‑Entity‑Type File Layout**
```
[Header]  (fixed size: 92 bytes)
  magic bytes         (4 bytes: 0x89 'D' 'C' 'S' — the high‑bit byte catches 7‑bit‑stripping transfers)
  formatVersion       (this file's own binary layout version — independent of the manifest's formatVersion and of datasetVersion/generatorVersion, which live only in the manifest, §5.1)
  entityTypeLength    (1..64)
  entityType          (fixed 64‑byte field, ASCII, zero‑padded — the first entityTypeLength bytes are significant; must match the manifest entry)
  identifierCount     (count of unique identifiers, post‑deduplication — §8.2)
  bucketSize          (K: target entries per bucket)
  bucketCount         (= ceil(identifierCount / bucketSize); 1 below the small‑dataset threshold)
  checksum            (CRC32C of the whole file, this field zeroed during hashing — §5.5; 4 bytes, little‑endian)

[Prefix Index]
  bucket[0..bucketCount].startIndex        (bucketCount+1 entries; entry index into the Identifier Offset Table; the last entry is a sentinel equal to identifierCount)
  separatorOffset[0..bucketCount]          (bucketCount+1 entries; byte offset into Separator Data — same offset‑table pattern as the Identifier Offset Table, §5.3)
  separatorData                            (bucket separators' UTF‑8 bytes, ≤36 bytes each, stored back‑to‑back in bucket order — one contiguous block, never interleaved with startIndex)

[Identifier Offset Table]
  offset[0..N]  (N+1 entries; byte offset into Identifier Data)

[Identifier Data]
  identifier0 bytes (UTF-8)
  identifier1 bytes (UTF-8)
  ...
  identifierN-1 bytes (UTF-8)
```
The Prefix Index's three arrays (`startIndex`, `separatorOffset`, `separatorData`) are each stored contiguously and never interleaved — the bucket‑selection search (§5.4) only ever touches `separatorOffset`/`separatorData`, and keeping `startIndex` in its own separate block avoids polluting the cache lines that search touches with data it doesn't need until after a bucket is chosen.

Each file is fully self‑contained and independently valid — there is no shared structure across entity types and no partitioning of a combined array. All multi‑byte integers in the header, Prefix Index, and Identifier Offset Table are **little‑endian**. This is a fixed convention of the format, independent of the host's native order — implementations read and write it explicitly. The handful of integer reads involved in a lookup make byte‑swap cost irrelevant either way.

### **5.3 Sorted Identifiers**
Identifiers are UTF‑8‑encoded and stored back‑to‑back, in lexicographic (byte‑wise) sorted order, in the Identifier Data block. The Identifier Offset Table holds N+1 offsets; identifier `i` spans `[offset[i], offset[i+1])`. This gives O(1) bounds lookup per entry and supports binary search over variable‑length values without per‑entry allocation.

Sorting and comparison operate on the **encoded bytes**, never on Java's native `String` ordering — UTF‑16 code‑unit order (`String.compareTo()`) and UTF‑8 byte order can disagree for supplementary Unicode characters (code points above U+FFFF), even though UTF‑8 byte order always matches true Unicode code‑point order. The generator (§8.2) must sort by encoded bytes to stay consistent with the runtime's byte‑wise binary search; for pure‑ASCII identifiers the two orderings always agree, which is why this distinction was invisible before non‑ASCII was allowed.

### **5.4 Prefix Index (Adaptive / Equal‑Count)**
The prefix index is built from the realized sort order at generation time, not from any assumption about identifier structure — it works identically whether identifiers are sequential integers, random UUIDs, or anything else:
1. After sorting, slice the identifiers into buckets of a fixed target size `K = 128` (launch default): `bucketCount = max(1, ceil(identifierCount / K))`. This formula alone handles small datasets — when `identifierCount ≤ K`, `bucketCount` is already 1, so no separate small‑dataset threshold is needed.
2. For each bucket, record its start entry index and a separator key: the bucket's first identifier.
3. At lookup, search the small separator array (`bucketCount` entries, small enough to substantially improve locality) to find the candidate bucket per the invariant below, then binary search that bucket's entry range in the Identifier Offset Table / Identifier Data as before.

**Bucket‑selection invariant:** for query key `q`, the selected bucket is the greatest index `i` such that `separator[i] ≤ q`, or bucket 0 when `q < separator[0]`. This is a predecessor/floor search, not an exact‑match binary search — `q` will rarely equal a separator exactly, and naively binary‑searching for `q` itself (rather than its floor) risks selecting the wrong bucket and missing an identifier that's actually present. E.g. for buckets `[A,B,C] [D,E,F] [G,H,I]` (separators `A, D, G`): query `F` selects bucket 1, query `G` selects bucket 2, a query before `A` selects bucket 0, and a query after `I` selects bucket 2 — the last bucket, so the second‑stage search still correctly returns "not found" rather than having nowhere to look. When `identifierCount = 0` (no deleted identifiers for this entity type), there is no separator to record; `bucketCount = 1` with a zero‑length separator array, and `isDeleted` short‑circuits to `false` for every query without running either search stage.

This guarantees every bucket holds ~`K` entries regardless of the identifiers' content, eliminating the skew risk of byte‑prefix bucketing. Total comparisons stay the same order as a flat binary search (`log₂(bucketCount) + log₂(K) ≈ log₂(N)`); the benefit is locality — the separator array is small and reused by every query, while a flat binary search's first steps touch scattered, unpredictable pages across the full dataset. This depends on `separatorOffset`/`separatorData` being stored as their own contiguous block (§5.2), not interleaved with `startIndex` — interleaving would double the bytes touched per comparison with data irrelevant to this stage, weakening the cache/TLB locality this index exists to provide. `bucketCount` and separators are recomputed on every regeneration, so the index self‑adjusts automatically if an entity type's ID mix or volume changes between releases — no tuning per entity type, no configuration exposed to consumers. `K` is a generator‑side default stored per file in the header (§5.2), not a hardcoded runtime constant, so it can be revised later without a runtime change or format version bump — see §12 for planned benchmarking to refine it.

### **5.5 Checksum**
Each entity‑type file's checksum is the **CRC32C** (Castagnoli, 32‑bit) of the complete file with the checksum field's own 4 bytes replaced by zero before hashing. This resolves the self‑reference problem (a checksum can't include its own final value) while covering every other byte in the file — including header fields like `entityType`, `identifierCount`, `bucketSize`, and `bucketCount`, not just the Prefix Index / Offset Table / Identifier Data — so header corruption is caught too, not only payload corruption. Verification re‑derives the value the same way and compares. It is computed by streaming three logical segments (bytes before the checksum field, four zero bytes in its place, then bytes after) without ever materializing a modified copy of the file.

CRC32C is chosen because it is in the JDK (`java.util.zip.CRC32C`), keeping the runtime dependency‑free (§3.1), is hardware‑accelerated on modern CPUs via the SSE4.2 CRC instruction, and is purpose‑built for detecting accidental corruption and truncation (bit flips, burst errors). It is not cryptographically secure — an accepted tradeoff given the threat model: this guards against corruption/truncation, not against a malicious actor deliberately crafting a colliding file. The 32‑bit width means a missed random corruption has probability ~2⁻³²; truncation is additionally caught because `identifierCount` and the offset tables imply the exact file length.

The manifest's own checksum is **SHA256** instead (§5.1) — it's small and read once, so the slower per‑byte cost is irrelevant, and it's the trust anchor pinning every entity‑type file's expected CRC32C, so it's worth the stronger guarantee. The whole requested set can be validated for consistency (manifest checksum, then each loaded file's checksum) without merging any identifier data.

CRC32C and SHA‑256 provide corruption detection, not artifact authenticity.

### **5.6 Identifier Constraints & Exact Comparison**
- Identifiers are restricted to **UTF‑8, max 36 bytes when encoded** (accommodates canonical hyphenated UUID strings while remaining generic). The cap is on encoded byte length, not `String` character count — those diverge for non‑ASCII text, so specifying bytes directly avoids the ambiguity rather than relying on ASCII to make them coincide.
- Identifiers are stored and compared as their **raw bytes** — never hashed. A hash‑based comparison key (fixed‑width, cache‑aligned, generalizes to any input) was evaluated and rejected: any hash width carries a nonzero collision probability, which conflicts with the hard requirement that `isDeleted` never return `true` for a value that was not actually deleted.
- Identifiers containing unpaired UTF‑16 surrogates are rejected rather than silently accepted — Java's default UTF‑8 encoder substitutes a replacement character for these, which could make two different caller‑intended identifiers collide into the same stored bytes.
- The library performs **no Unicode normalization**. Comparison is exact‑byte, so an identifier encoded in a different normalization form (e.g. NFC vs. NFD) than how it was originally deleted would not match — a real risk for human‑typed text, negligible for opaque IDs (UUIDs, database keys, tokens). Callers are responsible for supplying identifiers exactly as issued by the authoritative source; consistent with not imposing any ID‑generation scheme, the library does not normalize on their behalf.
- Zero‑padding a short identifier to a fixed width is only safe when the content can't contain a literal `0x00` byte (guaranteed for ASCII text, not for arbitrary UTF‑8). This only matters if a future fixed‑width layout (§12) is ever adopted — the current variable‑length offset‑table format never relies on it.

---

## **6. Runtime Lookup Flow**

### **6.1 Construction / Selective Loading**
1. Read and validate the manifest (checksum) and verify its `formatVersion` is recognized, then look up each requested entity type by name.
2. If a requested entity type is not listed in the manifest, fail at construction — see §10 for the exact exception.
3. For each requested entity type only: open its file, verify its `formatVersion` is recognized, verify the header's `entityType` matches the manifest entry, verify the file's checksum, and mmap its Prefix Index / Identifier Offset Table / Identifier Data. Any failure here also fails construction (§10).
4. Entity types not requested are never opened, read, or mapped.

### **6.2 isDeleted(entityType, id)**
1. Look up `entityType` in the `Map<String, PackedDeletionSet>` built at construction (§3.1, §6.1) — an O(1) map lookup, not a re‑validation against the manifest or any file I/O. If not found (never requested, or unknown entirely), fail — see §10.
2. Validate `id`'s UTF‑8 encoding is ≤ max length and contains no unpaired surrogates; if not, fail — see §10.
3. Convert `id` (String) → UTF‑8 byte array.
4. Find the candidate bucket via the Prefix Index's separator array, using the floor/predecessor search defined in §5.4 — not exact‑match. (Short‑circuits to `false` if the entity type has zero identifiers, §5.4.)
5. Binary search within that bucket's entry range, comparing raw bytes against that entity type's Identifier Data via its Identifier Offset Table.
6. Return true/false.

### **6.3 filter(entityType, items, idExtractor)**
1. Iterate items.
2. Extract identifier.
3. Call `isDeleted`.
4. Collect non‑deleted items.
5. Return filtered list.

Batch optimizations may reuse prefix bucket lookups.

---

## **7. Dataset Update Model**
- The packed dataset is immutable for the lifetime of a process; there is no runtime hot‑reload.
- New deletions or a changed entity‑type selection are picked up only via restart (against an updated manifest/dataset or new requested set) — an accepted cost of this design; see §7.1 for propagation delay and fleet consistency.

### **7.1 Propagation Delay & Fleet Consistency**
A deletion isn't atomically effective fleet‑wide the moment it's recorded — it passes through deletion source → dataset generation → artifact publication → service rollout → process restart → enforced by that instance, so during a rolling restart part of the fleet can run an older version than the rest (e.g. some instances still reporting ID X as not deleted while others already do). The library guarantees per‑instance exactness once a version is loaded, not fleet‑wide atomicity.

For an access‑control use case, the property that actually matters is: **a deletion becomes effective across the whole fleet within some maximum delay.** The library can't bound or enforce this — it has no visibility into any consumer's deployment pipeline — but it's a real system‑level SLA each consuming service must explicitly define for itself, computed from its own generation cadence + publication + rollout time. User‑facing docs should walk consumers through computing their own number — there's no single correct value across every deployment.

To make this debuggable, `DeletionChecker` exposes:
- `datasetVersion()` — the manifest's `datasetVersion` (§5.1), an ISO8601 timestamp identifying this instance's currently loaded release.
- `loadedAt()` — the `Instant` construction completed, i.e. when this instance's dataset became active.

These let operators correlate which instances are stale during an incident, and support alerting (e.g. flag any instance whose `loadedAt()` predates the newest published version by more than the declared freshness SLA).

---

## **8. Dataset Generator Design**

### **8.1 Input**
List of `(entityType, identifier)` pairs from authoritative deletion source.

### **8.2 Steps**
1. Group input pairs by `entityType`.
2. Per entity type: reject over‑length (>36 bytes UTF‑8‑encoded) or malformed (unpaired‑surrogate) identifiers.
3. Encode identifiers as UTF‑8 bytes; sort lexicographically by the **encoded bytes** (§5.3) — never by `String.compareTo()`.
4. Deduplicate: collapse consecutive equal identifiers to one. The packed set is a true set, not a multiset — `identifierCount` (§5.1, §5.2) is the count of unique identifiers remaining after this step, not the count of input records.
5. Build that entity type's identifier offset table and prefix index (§5.4).
6. Compute the file's checksum (§5.5) and write it, header included.
7. After all entity‑type files are written, write the JSON manifest (§5.1) — `formatVersion`, `datasetVersion`, `generatorVersion`, each entity type's file name/identifier count/checksum — plus the manifest's own checksum (§5.5).
8. Validate by loading with runtime code, requesting all entity types (including manifest and per‑file checksum verification).

### **8.3 Output**
One versioned file per entity type, plus a manifest, e.g.:

```
manifest-2026-09-06.dat
deleted-ids-user-2026-09-06.dat
deleted-ids-order-2026-09-06.dat
deleted-ids-device-2026-09-06.dat
```

---

## **9. Threading & Performance Considerations**

- PackedDeletionSet is **thread‑safe** after initialization.
- Memory‑mapped file avoids **heap** usage and GC churn — but is not free of physical memory cost; see §9.1.
- Binary search is extremely fast due to small search ranges.
- Prefix index (§5.4) confines each lookup's uncached search to ~K entries via a small, cache/page‑resident separator array.
- Entity type resolution happens exactly once, at construction (§6.1) — the hot‑path `entityType` lookup in `isDeleted`/`filter` is a single map lookup into an already‑resolved, already‑mmap'd `PackedDeletionSet`; no re‑validation, re‑parsing, or file I/O ever occurs after construction.

### **9.1 mmap Memory Accounting**
"Off‑heap" does not mean "free." A memory‑mapped file consumes process virtual address space immediately, and — as pages are actually touched during lookups — page cache/resident memory, page table entries, and process RSS. A service that maps a 400MB dataset does not see a 400MB Java heap increase, but it can accumulate hundreds of MB of resident memory once enough of the dataset has been accessed.

This matters for **container memory limits**: RSS from mmap'd, file‑backed pages counts toward a pod's memory usage the same as any other resident memory, even though it never appears in JVM heap metrics. Dataset memory should be documented for consumers explicitly as **off‑heap, file‑backed memory that may contribute to process RSS and container memory accounting** — mapping a file does not imply zero physical memory consumption. Given the library is distributed to hundreds of services, this needs to be stated plainly in user‑facing docs, not left implicit in "avoids heap usage."

One mitigating factor, not a reason to under‑budget: unlike heap/anonymous memory, clean file‑backed pages are typically reclaimable by the kernel under memory pressure without directly triggering an OOM kill. Consuming services should still size container memory limits assuming the full working set of their requested entity types can become resident.

---

## **10. Error Handling**
- Manifest missing, unreadable, checksum mismatch, or unrecognized `formatVersion` → fail fast during construction.
- Requested entity type not present in the manifest → throw `IllegalArgumentException` at construction, naming the unsupported entity type.
- A requested entity type's file missing, unreadable, header `entityType` mismatch, unrecognized `formatVersion`, or checksum mismatch → fail fast during construction; dataset is treated as corrupted (distinct from "unsupported entity type" — this is a valid entity type whose data can't be trusted). An unrecognized `formatVersion` specifically means this runtime is too old (or too new) for the artifact, not that it's corrupted — worth a distinct message even though the fail‑fast behavior is the same.
- `isDeleted`/`filter` called with an entity type that was not requested at construction → throw `IllegalArgumentException`, even if that entity type exists in the manifest.
- Over‑length (>36 bytes UTF‑8‑encoded) or malformed (unpaired‑surrogate) identifier → throw `IllegalArgumentException`.
- Corrupted prefix index → validation step (including checksum) should prevent this.

---

## **11. Testing Strategy**

### **Unit Tests**
- Identifier encoding/decoding (UTF‑8)
- Input validation: over‑length or malformed (unpaired‑surrogate) identifier → `IllegalArgumentException`
- Sort/comparison order matches UTF‑8 byte order for supplementary Unicode characters, not Java `String.compareTo()` order (§5.3)
- Manifest parsing and checksum validation
- Constructing with a subset of entity types loads only those files (others never opened/mapped)
- Constructing with an entity type absent from the manifest → `IllegalArgumentException` at construction
- Calling `isDeleted`/`filter` with a valid‑but‑not‑requested entity type → `IllegalArgumentException`
- Prefix index construction
- Generator deduplication: duplicate input identifiers collapse to one; `identifierCount` reflects unique count only
- Bucket‑selection invariant (§5.4): query equal to a separator, between separators, before the first separator, and after the last separator all select the correct bucket
- Binary search correctness
- Per‑file checksum validation
- `datasetVersion()`/`loadedAt()` reflect the loaded dataset and construction time
- Unrecognized `formatVersion` (manifest or per‑file) fails construction with a version‑mismatch message, distinct from a checksum‑failure message
- isDeleted correctness
- filter correctness

### **Integration Tests**
- Load a real multi‑entity‑type dataset with a partial entity‑type selection
- Boundary‑case datasets: empty (zero identifiers), single identifier, all identifiers identical, many duplicates
- Validate random membership per loaded entity type
- Validate performance constraints

---

## **12. Future Extensions**
- **Bloom filter frontend** — planned as the immediate next enhancement after the adaptive prefix index ships; v1 launches without it. A per‑entity‑type bit array, built from the same identifier list at generation time, checked before the two‑level binary search. It only ever answers "definitely not present" or "maybe present," so it accelerates the common negative‑lookup case (most queries are for non‑deleted entities) without weakening the exact‑match guarantee — a "maybe" still falls through to the real search. It does **not** help true‑positive latency or fix bucket skew — the adaptive prefix index (§5.4) is required regardless. Costs ~1–2 bytes/identifier of extra memory depending on target false‑positive rate, plus a new binary‑format section (version bump) and its own generation/validation step. Defer until production profiling shows negative‑lookup latency is a real bottleneck.
- Benchmark prefix‑index bucket size `K` across representative dataset sizes and identifier‑length distributions to validate or refine the launch default (`K = 128`, §5.4); non‑blocking for launch. Candidate set should span a wide range (e.g. `{64, 128, 256, 512, 1024, 2000, 4000}`) — the tradeoff is between separator‑array cache residency (favors larger K) and the final bucket‑range footprint touched per query (favors smaller K), and pure analysis can't settle where the real knee is on actual hardware.
- Snapshot + delta support
- Sharding a single entity type across multiple files if it exceeds practical single‑file size
- SIMD‑accelerated search
- Loading or unloading an entity type after construction without a full restart

---

## **13. Open Questions**
None currently.
