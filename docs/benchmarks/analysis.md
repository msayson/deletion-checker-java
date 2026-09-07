# Benchmark analysis

Interpretation of [`reference.md`](reference.md). Run on an i7-13620H / Corretto 21 / WSL2, 500k
timed calls per cell. Absolute numbers are machine-specific; the **ratios** are what transfer.

---

## Topline

`HashSet<String>` is ~2–4x faster per lookup (but its *loser* is still ≤1 µs median, ≤13 µs p99.9).
`DeletionChecker` costs **~200x less heap**, adds **~zero GC pressure**, **loads ~30x faster**, and
can map a **subset** of entity types. `HashSet` wins the one axis that rarely binds; the library
wins the four that do at fleet scale.

**Use a plain `HashSet` when** *all* of:

- one entity type, or you always load every type;
- the set is small — up to a few hundred thousand, maybe low millions of IDs, where `N × ~110 B` of
  heap and its GC-scan cost are comfortably within budget;
- the deleted-ID list is already in your process (loaded from your own store), so the library's
  parse / validate / dedup / checksum / manifest machinery is work you'd skip, not reinvent;
- you don't need versioned immutable artifacts, `datasetVersion()` / `loadedAt()` freshness signals,
  or subset loading;
- the sub-microsecond lookup edge is genuinely in your critical path.

**Use `DeletionChecker` when** *any* of:

- millions of IDs, especially across several entity types (10 types × 10M ⇒ 1 GB `HashSet` heap vs
  4.8 MiB);
- tight container memory limits or a GC-pause SLA (no 10M+ extra live objects to walk each cycle —
  the identifiers are off-heap, file-backed, reclaimable);
- frequent restarts / autoscaling (~30 ms `mmap` + CRC vs ~1 s to repopulate a 10M `HashSet`);
- different services need different subsets of entity types (selective load ≈ 1/N cost);
- you need a distributable, checksummed, versioned artifact and its operational hooks;
- you're shipping this to many services and want one vetted implementation, not per-team hand-rolls.

**Borderline** (either works): a single entity type, ~100k–1M IDs, generous heap, no GC SLA — then
it's about whether you value the artifact / versioning story.

### Improvements that would shift this

The library loses *only* on latency, *only* for raw lookups and for negatives doing a full search —
both addressable, all already in DESIGN §12:

| Improvement | Effect |
|---|---|
| **Bloom-filter frontend** (planned) | Biggest lever. Most queries are for non-deleted entities; a pre-check answers "definitely not deleted" in ~1 cache-line touch — likely faster than a `String` hash + `equals`. Flips the negative-lookup column and calms the p99.9 tail. |
| **Batch / pre-encoded fast paths** (`filter` bucket-reuse, a `contains(byte[])` overload, an ASCII fast path in `IdentifierCodec`) | The `packed contains()` rows show 30–40% of `isDeleted`'s cost is the per-call UTF-8 encode + validation + `byte[]` alloc, not the search. Amortizing it closes much of the positive-lookup gap. |
| **"Fat" offset table** (inline a short ID prefix beside each offset) | Turns ~2 cache misses per binary-search probe into ~1 at scale. Speculative; real format work. |
| SIMD compare, `Map`→array | Won't matter — the compare is memory-latency-bound, and 1→10 types shows no degradation. |

None touch heap / GC / startup / selective loading, where the library already wins decisively. With
a Bloom frontend and batch fast paths, `DeletionChecker` becomes a reasonable default even at a few
hundred thousand IDs, leaving `HashSet` preferable only for the trivial "one small set already in
memory" case — where it's also simpler.

---

## 1. Memory — the packed set's reason to exist

**Per-identifier heap (Sweep A):**

| ID shape | `HashSet<String>` | packed (heap) | packed (mapped file) |
|---|---:|---:|---:|
| `alnum16` (16 B) | ~100 B/id | **0.2 B/id** | 20 B/id |
| `customer` (15 B) | ~100 B/id | **0.2 B/id** | 19 B/id |
| `uuid` (36 B) | ~123 B/id | **0.3 B/id** | 40 B/id |

The HashSet cost is `String` header + its `byte[]` header + `HashMap.Node` + table slot ≈ 80 B
fixed, plus the ID bytes — matching DESIGN §4.1's "~80 B/id". The packed set's on-heap cost is just
the lifted prefix-index arrays amortized over N; its identifiers live in the mmap'd file
(`id_length + 4`), which is **off-heap, reclaimable page cache** — only touched pages go resident,
no GC scan.

**Scaling with entity-type count (Sweep B), 1M ids/type:**

| types | total ids | HashSet heap | packed heap | packed mapped |
|---:|---:|---:|---:|---:|
| 1 | 1M | 120 MiB | 1.0 MiB | 38 MiB |
| 5 | 5M | 519 MiB | 2.5 MiB | 134 MiB |
| 10 | 10M | **1038 MiB** | **4.8 MiB** | 267 MiB |

**~220x less Java heap** at every scale. The entire 10M-id / 10-type dataset occupies less heap
(4.8 MiB) than a single 40k-entry HashSet.

**Selective load** (`packed (1 of N)`): loading one type out of ten costs 5 ms and 1.7 MiB heap —
the same as loading it standalone, unaffected by the nine unused types in the manifest. HashSet has
no equivalent.

---

## 2. Startup — packed loads ~30x faster

| ids | HashSet build | packed load | packed *generate* (build-time only) |
|---:|---:|---:|---:|
| 1M | 60–76 ms | 2–5 ms | 0.5 s |
| 10M | 656–1040 ms | **19–36 ms** | 7–9 s |

Populating a 10M-entry HashSet is ~1 s of object allocation. Loading the packed file is `mmap` + one
hardware-accelerated whole-file CRC32C + lifting the small prefix index → tens of ms. The 7–9 s
generation cost is paid **once** by the dataset generator, never by a consuming service.

---

## 3. Lookup latency — HashSet wins raw speed, by a margin that shrinks with scale and never matters

**Positive lookup, p50 (ns):**

| ids | HashSet | packed `isDeleted` | packed `contains(byte[])` |
|---:|---:|---:|---:|
| 1 000 | 24 | 100–128 | ~70 |
| 100 000 | 60–118 | 235–331 | ~200 |
| 1M | 306–325 | 461–580 | ~350 |
| 10M | 422–479 | 783–1098 | ~600 |

- **HashSet is faster everywhere** — ~4x at 1k (one hash probe in L1 vs ~20 byte-comparisons across
  two tables), narrowing to **~1.8–2.3x at 10M** where both are memory-latency-bound and the
  algorithmic difference is swamped by cache misses.
- **Both are trivially fast in absolute terms** — packed's *slowest* median (10M UUID) is ~1 µs,
  ~1000x under the 1 ms p99.9 budget.
- **The `encode` tax**: `packed contains()` (identifier pre-encoded to bytes) is 30–40% faster than
  `isDeleted`. A meaningful slice of `isDeleted`'s cost is `IdentifierCodec.encode` — UTF-8 encode +
  surrogate check + one `byte[]` alloc — not the search. `filter()` and byte-holding callers avoid
  repeating it.
- **Negative lookups**: HashSet misses fast (empty slot, no full `equals`) — often quicker than its
  own positive lookups at scale. Packed negatives cost about the same as positives.

**Tails (p99, p99.9):** sub-µs to ~1.3 µs through 100k; **4–12 µs at 1M–10M for *both*
implementations**. These are young-gen GC pauses landing in the 500k-sample window (one pause
inflates ~500 samples = the 99.9th percentile), not algorithmic — the p50/p99 carry the real
signal. The one 15 µs outlier (uuid 100k packed) is a single GC hit; its p99 was 1.3 µs.

**Sweep B**: p99.9 of 4–9 µs across 1/5/10 types for both — the `Map<String, PackedDeletionSet>`
hot-path lookup adds nothing measurable as type count grows.

---

## 4. Verdict

| | `HashSet<String>` | Packed set |
|---|---|---|
| Lookup latency | **wins** (still ≤1 µs median, ≤13 µs p99.9 for the loser) | — |
| Code simplicity | **wins** | — |
| Heap / id | ~100–125 B | **~0.3 B** (200–400x) |
| GC pressure | 10M+ live objects | **~zero** |
| Startup (10M) | ~1 s | **~30 ms** |
| Selective loading | none | **~1/N cost** |
| Immutable versioned artifact | no | **yes** |

`HashSet` is the right tool for one small entity type already in memory where latency is the only
axis. The library targets the opposite regime — hundreds of services, up to 10M ids, several entity
types, container memory limits — where sub-millisecond-but-not-fastest lookups are a fine price for
eliminating a gigabyte of heap, the GC scan over it, and a second of startup. The results confirm
every DESIGN §2 goal: exact, sub-ms p99.9 (with ~1000x margin), near-zero GC overhead, smooth to
10M, selective loading pays.

---

## Caveats

- GC-delta memory is ±~10% (see the uuid-1k 144 B/id blip).
- Single JVM, single machine, `System.nanoTime()` bracketing single calls (~20–40 ns timer overhead
  — so HashSet's true small-set lookup is even faster than shown).
- Not JMH-rigorous; adequate for a relative comparison, not for publishing absolute latency figures.
- p99.9 tails at ≥1M are GC-jitter-dominated, not algorithmic.
- Regenerate `reference.md` and revisit this analysis when the implementation changes materially.
