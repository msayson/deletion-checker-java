# Benchmark analysis

Interpretation of [`reference.md`](reference.md). Run on an i7-13620H / Corretto 21 / WSL2, 500k
timed calls per cell. Absolute numbers are machine-specific; the **ratios** are what transfer.

---

## Topline

`HashSet<String>` is ~2–4x faster per lookup (but the packed set's slowest median is still ~1.1 µs,
its p99.9 ~12 µs — ~100x under budget).
`DeletionChecker` costs **hundreds of times less heap** (~0.25 vs ~110 B/id), adds **~zero GC
pressure**, **loads ~30x faster**, and can map a **subset** of entity types. `HashSet` wins the one
axis that rarely binds; the library wins the four that do at fleet scale.

The prefix-index bucket size `K` was swept over {128 … 4096} (§5): **128 is the right default** —
fastest or tied-fastest everywhere, larger `K` only slower, and the memory it would save is
negligible.

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

- millions of IDs, especially across several entity types (10 types × 1M ⇒ ~1 GB `HashSet` heap vs
  ~2 MiB);
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
| **Batch / pre-encoded fast paths** (`filter` bucket-reuse, a `contains(byte[])` overload, an ASCII fast path in `IdentifierCodec`) | The `packed contains()` rows are ~20–30% faster than `isDeleted` — that gap is the per-call UTF-8 encode + validation + `byte[]` alloc, not the search. Amortizing it (batch) or skipping it (byte-holding callers) narrows the positive-lookup gap. |
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
| 1 | 1M | 119 MiB | ~0.3 MiB | 38 MiB |
| 5 | 5M | 524 MiB | ~1.2 MiB | 134 MiB |
| 10 | 10M | **1046 MiB** | **~2.4 MiB** | 267 MiB |

**Two-to-three orders of magnitude less Java heap** at every scale (~110 B/id vs ~0.25 B/id). The
entire 10M-id / 10-type dataset occupies a couple of MiB of heap — less than a single 30k-entry
HashSet.

**Selective load** (`packed (1 of N)`): loading one type out of ten costs ~5 ms and well under a MiB
of heap — the same as loading it standalone, unaffected by the nine unused types in the manifest
(38 MiB mapped for the one file it does open). HashSet has no equivalent.

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
| 1 000 | ~25 | ~95–130 | ~70 |
| 100 000 | 60–120 | ~210–260 | ~200 |
| 1M | ~300 | ~420–585 | ~350 |
| 10M | ~390–480 | ~850–1100 | ~630–780 |

- **HashSet is faster everywhere** — ~4x at 1k (one hash probe in L1 vs ~20 byte-comparisons across
  two tables), narrowing to **~2–3x at 10M** where both are memory-latency-bound and the
  algorithmic difference is swamped by cache misses.
- **Both are trivially fast in absolute terms** — packed's *slowest* median (10M UUID) is ~1 µs,
  ~1000x under the 1 ms p99.9 budget.
- **The `encode` tax**: `packed contains()` (identifier pre-encoded to bytes) is meaningfully faster
  than `isDeleted` (e.g. ~0.35 vs ~0.5 µs at 1M). Part of `isDeleted`'s cost is
  `IdentifierCodec.encode` — UTF-8 encode + surrogate check + one `byte[]` alloc — not the search.
  `filter()` and byte-holding callers avoid repeating it.
- **Negative lookups**: HashSet misses fast (empty slot, no full `equals`) — often quicker than its
  own positive lookups at scale. Packed negatives cost about the same as positives.

**Tails (p99, p99.9):** p99 stays under ~1.7 µs everywhere; p99.9 is well-behaved through 100k
(sub-µs to ~1 µs) then jumps to **~2–12 µs at 1M–10M for *both* implementations** and varies run to
run. These are young-gen GC pauses landing in the 500k-sample window (one pause inflates ~500
samples = the 99.9th percentile), not algorithmic — the p50/p99 carry the real signal, and even the
noisy p99.9 is ~100x under 1 ms.

**Sweep B**: p99.9 of ~4–9 µs across 1/5/10 types for both — the `Map<String, PackedDeletionSet>`
hot-path lookup adds nothing measurable as type count grows.

---

## 4. Verdict

| | `HashSet<String>` | Packed set |
|---|---|---|
| Lookup latency | **wins** (its slowest median is ~1.1 µs, its p99.9 ~12 µs) | — |
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

## 5. Bucket size K — keep the default (128)

From [`bucket-size.md`](bucket-size.md): `K` ∈ {128 … 4096} × {uuid, customer, alnum16} ×
{1, 3, 5} entity types, 1M ids/type, packed set only.

**Latency.** The spread across the whole `K` range is small — p50 moves ~0.05–0.15 µs, p99 ~0.1–0.6 µs
— but the trend is consistent and points down, not up:

- No `K` is meaningfully faster than 128. In the 9 (shape × typeCount) scenarios the fastest `K` is
  128 in 5 and 256 in 4, and every 256 win is ≤13% and within run-to-run noise of 128.
- `K ≥ 1024` is consistently **slower**, by ~10–30% at p50/p99, worst with multiple entity types and
  with the shared-prefix `customer` shape (both stress the widening in-bucket binary search: more
  entries per bucket ⇒ more scattered offset-table + data reads per probe, and for `customer` more
  wasted `customer-` byte comparisons).
- DESIGN §5.4 predicted a tension between separator-array cache residency (favours large `K`) and
  in-bucket footprint (favours small `K`). Only the second effect shows up here: even at `K = 128` /
  1M ids the separator array is ~200–280 KB and fits L2 comfortably, so shrinking it buys nothing,
  while the wider stage-2 search costs real cache misses.

**Memory.** Larger `K` shrinks the prefix index — heap ~0.19–0.34 B/id at 128 down to ~0.01 B/id at
4096, and mapped bytes/id by the same ~0.3 B/id — but these are rounding error next to the ~20–40
B/id of identifier data. Load time is flat across `K` (CRC verification dominates, not index
lifting).

**Conclusion.** Keep `PrefixIndex.DEFAULT_BUCKET_SIZE = 128`. It is the fastest or statistically
tied-fastest in every scenario, and the memory a larger `K` would save is negligible. If the knee is
anywhere it is at or below 128; `K = 64` was not tested and would roughly double the (already tiny)
index overhead for at best a marginal latency gain, so there is no reason to chase it. This closes
the DESIGN §12 "benchmark `K`" item.

---

## Caveats

- GC-delta memory is approximate (±~10%, more at small heaps — the sub-MiB sweep-B and selective-load
  figures wander run to run); the per-id ratios are stable and are what matter.
- Single JVM, single machine, `System.nanoTime()` bracketing single calls (~20–40 ns timer overhead
  — so HashSet's true small-set lookup is even faster than shown).
- Not JMH-rigorous; adequate for a relative comparison, not for publishing absolute latency figures.
- p99.9 tails at ≥1M are GC-jitter-dominated, not algorithmic.
- Regenerate `reference.md` and revisit this analysis when the implementation changes materially.
