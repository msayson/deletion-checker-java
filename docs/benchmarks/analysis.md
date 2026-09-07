# Benchmark analysis

Interpretation of [`reference.md`](reference.md). Run on an i7-13620H / Corretto 21 / WSL2, 500k
timed calls per cell. Absolute numbers are machine-specific; the **ratios** are what transfer.

---

## Topline

`HashSet<String>` is ~2–3x faster on *positive* lookups (but the packed set's slowest median is
still ~1.3 µs, its p99.9 ~16 µs — ~60x under budget). On *negative* lookups the v2 Bloom filter (§6)
makes the packed set a tie-or-better — faster than `HashSet` at ≥1M ids.
`DeletionChecker` costs **hundreds of times less heap** (~0.25 vs ~110 B/id), adds **~zero GC
pressure**, **loads ~30x faster**, and can map a **subset** of entity types.

The prefix-index bucket size `K` was swept over {128 … 4096} (§5): **128 is the right default** —
fastest or tied-fastest everywhere, larger `K` only slower, and the memory it would save is
negligible.

The v2 **Bloom-filter frontend** (§6) now closes the one gap that bound: negative lookups drop
~3–5x to level with `HashSet`, for ~1.2 mapped B/id and zero heap. On by default (`--bloom-fpr`,
`1.0` disables).

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

The library loses *only* on latency, and after v2 *only* for positive raw lookups — the rest is
addressable, all in DESIGN §12:

| Improvement | Effect |
|---|---|
| **Bloom-filter frontend** (shipped, v2, §6) | Done. Most queries are for non-deleted entities; the pre-check answers "definitely not deleted" in ~1 cache-line touch. Measured: negative p50 ~3–5x faster, now level with `HashSet`; p99.9 tail collapsed. |
| **Batch / pre-encoded fast paths** (`filter` bucket-reuse, a `contains(byte[])` overload, an ASCII fast path in `IdentifierCodec`) | The `packed contains()` rows are ~20–30% faster than `isDeleted` — that gap is the per-call UTF-8 encode + validation + `byte[]` alloc, not the search. Amortizing it (batch) or skipping it (byte-holding callers) narrows the positive-lookup gap. |
| **"Fat" offset table** (inline a short ID prefix beside each offset) | Turns ~2 cache misses per binary-search probe into ~1 at scale. Speculative; real format work. |
| SIMD compare, `Map`→array | Won't matter — the compare is memory-latency-bound, and 1→10 types shows no degradation. |

None touch heap / GC / startup / selective loading, where the library already wins decisively. With
the v2 Bloom frontend shipped, `DeletionChecker` is already a reasonable default even at a few
hundred thousand IDs; batch fast paths would further narrow the positive-lookup gap, leaving
`HashSet` preferable only for the trivial "one small set already in memory" case — where it's also
simpler.

---

## 1. Memory — the packed set's reason to exist

**Per-identifier heap (Sweep A):**

| ID shape | `HashSet<String>` | packed (heap) | packed (mapped file) |
|---|---:|---:|---:|
| `alnum16` (16 B) | ~100 B/id | **0.2 B/id** | 21 B/id |
| `customer` (15 B) | ~100 B/id | **0.2 B/id** | 20 B/id |
| `uuid` (36 B) | ~123 B/id | **0.3 B/id** | 42 B/id |

The HashSet cost is `String` header + its `byte[]` header + `HashMap.Node` + table slot ≈ 80 B
fixed, plus the ID bytes — matching DESIGN §4.1's "~80 B/id". The packed set's on-heap cost is just
the lifted prefix-index arrays amortized over N; its identifiers live in the mmap'd file
(`id_length + 4`, plus ~1.2 B/id for the Bloom filter — §6), which is **off-heap, reclaimable page
cache** — only touched pages go resident, no GC scan.

**Scaling with entity-type count (Sweep B), 1M ids/type:**

| types | total ids | HashSet heap | packed heap | packed mapped |
|---:|---:|---:|---:|---:|
| 1 | 1M | 119 MiB | ~0.3 MiB | ~40 MiB |
| 5 | 5M | 522 MiB | ~1.7 MiB | ~141 MiB |
| 10 | 10M | **1040 MiB** | **~2–4 MiB** | ~281 MiB |

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
| 1M | 51–64 ms | 3–7 ms | ~0.7 s |
| 10M | 770–1010 ms | **17–40 ms** | ~9.5–12.7 s |

Populating a 10M-entry HashSet is ~1 s of object allocation. Loading the packed file is `mmap` + one
hardware-accelerated whole-file CRC32C + lifting the small prefix index → tens of ms. The generation
cost is paid **once** by the dataset generator, never by a consuming service; it rose ~35–45% with
the v2 Bloom filter (the filter build plus its no-false-negative self-check are ~2 extra hash passes
over the identifiers — §6).

---

## 3. Lookup latency — HashSet wins positives; the Bloom filter flips negatives

**Positive lookup, p50 (ns):**

| ids | HashSet | packed `isDeleted` | packed `contains(byte[])` |
|---:|---:|---:|---:|
| 1 000 | ~25 | ~110–140 | ~110 |
| 100 000 | 60–120 | ~240–320 | ~200 |
| 1M | ~300 | ~490–690 | ~360–540 |
| 10M | ~400–700 | ~980–1280 | ~740–910 |

- **HashSet is faster on positives everywhere** — ~3–4x at 1k (one hash probe in L1 vs ~20
  byte-comparisons across two tables), narrowing to **~2–3x at 10M** where both are
  memory-latency-bound and the algorithmic difference is swamped by cache misses. The Bloom probe
  adds ~0.05–0.15 µs to `isDeleted` — a positive still runs the full search after it.
- **Both are trivially fast in absolute terms** — packed's *slowest* median (10M UUID) is ~1.3 µs,
  ~750x under the 1 ms p99.9 budget.
- **The `encode` tax**: `packed contains()` (identifier pre-encoded to bytes) is meaningfully faster
  than `isDeleted` (e.g. ~0.36 vs ~0.5 µs at 1M). Part of `isDeleted`'s cost is
  `IdentifierCodec.encode` — UTF-8 encode + surrogate check + one `byte[]` alloc — not the search.
  `filter()` and byte-holding callers avoid repeating it.
- **Negative lookups (Bloom, §6)**: a "definitely absent" answer is one block probe — p50
  ~0.06–0.10 µs through 1M, ~0.14–0.18 µs at 10M, **independent of set size** and well below packed's
  own positive cost. **Faster than `HashSet` at ≥1M ids**; below ~100k `HashSet` still edges it
  (~0.03–0.06 µs) but both are under 0.1 µs. `packed contains()` negatives (no encode) hold
  ~0.05–0.09 µs even at 10M.

**Tails (p99, p99.9):** p99 stays under ~2 µs everywhere; *positive* p99.9 is well-behaved through
100k (sub-µs to ~1.3 µs) then jumps to **~4–16 µs at 1M–10M for *both* implementations** and varies
run to run — young-gen GC pauses landing in the 500k-sample window (one pause inflates ~500 samples
= the 99.9th percentile), not algorithmic. *Negative* p99.9 is where Bloom shows most: packed 10M
dropped from ~8–10 µs to **~1.6–2.3 µs**, now in HashSet's range.

**Sweep B**: positive p99.9 of ~6–11 µs across 1/5/10 types for both — the
`Map<String, PackedDeletionSet>` hot-path lookup adds nothing measurable as type count grows;
negative p99.9 is ~1.1–1.5 µs for the packed set (Bloom), at or below HashSet.

---

## 4. Verdict

| | `HashSet<String>` | Packed set |
|---|---|---|
| Positive lookup latency | **wins** (~2–3x; packed's slowest median ~1.3 µs, p99.9 ~16 µs) | — |
| Negative lookup latency | ties ≤100k | **wins ≥1M** (Bloom, §6) |
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

## 6. Bloom filter (v2) — negative lookups now beat `HashSet` at scale

`reference.md` is generated with the filter **on** (default `--bloom-fpr 0.01`). The blocked
("split-block") filter is one 256-bit block probed before the two-level search (DESIGN §5.7, D14);
`-Dbench.bloomFpr=1.0` disables it. Numbers below are the full Sweep A (3 shapes × 1K–10M, 1 type),
against a pre-Bloom run of the same sweep (git history).

**Negative `isDeleted` p50 (µs)** — the target case (most access-control queries are for
non-deleted entities, §2):

| ids | packed +Bloom (alnum / customer / uuid) | packed pre-Bloom | `HashSet` |
| ---: | --- | --- | --- |
| 1K–100K | 0.06–0.07 / 0.05–0.07 / 0.08–0.09 | 0.10–0.25 | 0.03–0.06 (still ~2x faster) |
| 1M | 0.08 / 0.08 / 0.10 | 0.34 / 0.37 / 0.44 | 0.14 / 0.13 / 0.20 — **packed wins** |
| 10M | 0.16 / 0.14 / 0.18 | 0.67 / 0.73 / 0.85 | 0.23 / 0.22 / 0.31 — **packed wins** |

The negative path is now one hash + one cache-line read, **independent of set size** — it never
touches the prefix index or offset table. Crossover: packed+Bloom overtakes `HashSet` on negatives
at **≥1M ids** (HashSet's table starts missing cache; the Bloom block stays one line). Below ~100k
HashSet still edges it, but everything there is <0.1 µs.

**Negative p99.9** collapses too — packed 10M was 7.8 / 8.5 / 10.0 µs (alnum / customer / uuid) →
**1.8 / 1.6 / 2.3 µs**, now in HashSet's range (0.9–4.5 µs, GC-noisy).

**Positive `isDeleted`** pays the extra probe and still runs the full search — p50 up ~0.05–0.15 µs
(10M: ~0.85→1.01, ~0.92→0.98, ~1.09→1.28 µs). Bloom does not help positives; they stay ~2.5–3x
behind `HashSet`.

**Footprint.** Mapped bytes/id +1.2 (alnum 20.2 → 21.4, customer 19.2 → 20.4, uuid 40.3 → 41.6 —
≈9.6 bits/id at 1% FPR). Heap unchanged (~0.2–0.4 B/id — the filter is mmap'd). Total-vs-HashSet
ratio still 0.22x / 0.21x / 0.34x.

**Build.** Generate time +35–45% at 10M (~9.5–12.7 s vs ~7–8 s) — the filter build plus the
no-false-negative self-check are ~2 extra hash passes. Paid once, offline. `load` is unchanged
(+~5 ms; CRC now covers the extra 1.2 B/id).

**Sweep B** (mixed shapes, 1 / 5 / 10 types): packed negative p99.9 was 4.6 / 9.2 / 9.1 µs →
**1.1 / 1.4 / 1.5 µs**, at or below `HashSet` (0.7 / 0.8 / 4.2 µs).

**Conclusion.** Keep the filter on by default. It turns a ~4–5x negative-lookup deficit at scale
into a tie-or-win, for +1.2 mapped B/id, zero heap, +5 ms load, and offline generate time.
`--bloom-fpr 1.0` opts out for a workload that is almost all positive lookups.

---

## Caveats

- GC-delta memory is approximate (±~10%, more at small heaps — the sub-MiB sweep-B and selective-load
  figures wander run to run); the per-id ratios are stable and are what matter.
- Single JVM, single machine, `System.nanoTime()` bracketing single calls (~20–40 ns timer overhead
  — so HashSet's true small-set lookup is even faster than shown).
- Not JMH-rigorous; adequate for a relative comparison, not for publishing absolute latency figures.
- p99.9 tails at ≥1M are GC-jitter-dominated, not algorithmic.
- `reference.md` is generated with the Bloom filter on (`bench.bloomFpr=0.01`, the default) at
  `-Dbench.xmx=8g`; the pre-Bloom baseline referenced in §6 is in git history.
- Regenerate `reference.md` and revisit this analysis when the implementation changes materially.
