# Scale Limits & Memory Cost

What the current implementation actually caps out at, and how much heap/off-heap memory a dataset
costs, for `DeletionChecker` versus a plain `HashSet<String>`. Two different questions, both
answered here:

- **Hard limits** — where the library fails outright.
- **Memory cost** — how much heap and off-heap memory a given dataset consumes, and how that
  compares to `HashSet<String>`.

Related reading: [`DESIGN.md`](DESIGN.md) §9.2 (scale limits, prose form),
[`benchmarks/analysis.md`](benchmarks/analysis.md) §1 (the *measured* memory numbers this doc's
formulas are validated against), and [`plans/1b-identifiers.md`](plans/1b-identifiers.md) (the plan
to remove the hard limit below).

---

## 1. Actual memory limits

Three different things — only one is a hard wall:

### Off-heap (mapped file) — the one real hard limit

**Scope: the total on-disk size of one packed entity-type file** (e.g.
`deleted-ids-user-2026-09-06.dat`). `PackedDeletionSet.open` maps it with a single
`channel.map(READ_ONLY, 0, channel.size())` call; both that `size` argument and a
`MappedByteBuffer`'s capacity are `int`-typed, so it throws `IllegalArgumentException` once the
file exceeds **2,147,483,647 bytes (2 GiB minus one byte)** — a hard failure at
`DeletionChecker.load` time, no degraded mode. Even without this JDK-level check, the format's
4-byte Identifier Offset Table (DESIGN §5.2) couldn't address past the same point anyway; the
generator has a matching `int`-sized ceiling on the write side (DESIGN §9.2, second failure mode).

**Does not apply to** — each governed by a separate, unrelated constraint:

| Thing | Actual governing constraint |
|---|---|
| Total dataset size, entity-type count, or total identifiers across the dataset | None — each file is mapped independently; N files can each be just under the cap |
| `manifest.json` | Read as a `String` via `Files.readString`, not mmap'd — a different, far higher ceiling |
| `filter()`'s input list | `List`'s own element-count limit (`Integer.MAX_VALUE` elements) — unrelated to file mapping |

### On-heap — no cap, but not literally zero at scale

`PackedDeletionSet.open` lifts three small prefix-index arrays (`startIndex`, `separatorOffset`,
`separatorData`) onto the Java heap; everything else (identifier data, offset table, Bloom filter)
stays in the mapping. This grows linearly and unboundedly with identifier count — slowly, but real
numbers appear in §3 below once you reach hundreds of millions of identifiers.

### Aggregate RSS — container memory is the real ceiling

Bounded only by what the container lets the process fault in and keep resident (DESIGN §9.1) —
reclaimable, not OOM-triggering by itself, but real.

### For comparison: `HashSet<String>`

No equivalent hard wall — bound purely by available heap — with one structural exception worth
knowing: `java.util.HashMap`'s backing array caps at `1 << 30` (1,073,741,824) buckets. Past
roughly 750M–1B entries (default 0.75 load factor) it stops resizing and lookup degrades toward
its treeified-bin worst case. Both structures hit a wall in the same neighborhood, for unrelated
reasons.

---

## 2. Per-identifier memory cost — formulas

Deterministic given the code, and validated against the three measured data points in
[`benchmarks/analysis.md`](benchmarks/analysis.md) §1 (matches within rounding).

| Structure | Bytes/id | Basis |
|---|---|---|
| `HashSet<String>` heap | `85 + L` | measured fixed overhead (`String` + `byte[]` header + `HashMap.Node` + table slot, Corretto 21, compressed oops) + identifier length `L` |
| `DeletionChecker` heap | `(8 + L) / 128` | `startIndex` + `separatorOffset` (8 B/bucket) + one separator (≈ `L` B) per `K = 128`-entry bucket — the generator's fixed default, no CLI override exists |
| `DeletionChecker` mapped (off-heap) | `L + 4 + 1.2 + (8 + L) / 128` | identifier bytes + 4 B offset-table entry + ~1.2 B/id Bloom filter (default 1% FPR) + prefix-index share |

Assumptions: `K = 128` (`PrefixIndex.DEFAULT_BUCKET_SIZE`, the only value the generator produces),
1% Bloom false-positive rate (`PackedFileWriter.DEFAULT_BLOOM_FPR`, the generator CLI default).

---

## 3. The table

"Fits one packed file" checks against the single-file cap explained in §1 — not total dataset
size, which has no cap.

| ID len | Count | `HashSet` heap | `DeletionChecker` heap | `DeletionChecker` mapped (off-heap) | Fits one packed file today? |
|---:|---:|---:|---:|---:|:---|
| 16 B | 100K | 9.6 MiB | 18.3 KiB | 2.0 MiB | yes |
| 16 B | 1M | 96.3 MiB | 183.1 KiB | 20.4 MiB | yes |
| 16 B | 10M | 963.2 MiB | 1.8 MiB | 204.0 MiB | yes |
| 16 B | 100M | 9.41 GiB | 17.9 MiB | 1.99 GiB | yes (barely) |
| 16 B | 1B | 94.06 GiB | **178.8 MiB** | 19.92 GiB | **No — 10.0× the cap** |
| 36 B (UUID) | 100K | 11.5 MiB | 33.6 KiB | 4.0 MiB | yes |
| 36 B | 1M | 115.4 MiB | 335.7 KiB | 39.6 MiB | yes |
| 36 B | 10M | 1.13 GiB | 3.3 MiB | 396.2 MiB | yes |
| 36 B | 100M | 11.27 GiB | 32.8 MiB | 3.87 GiB | **No — 1.9× the cap** |
| 36 B | 1B | 112.69 GiB | **327.8 MiB** | 38.69 GiB | **No — 19.3× the cap** |
| 64 B (max) | 100K | 14.2 MiB | 54.9 KiB | 6.7 MiB | yes |
| 64 B | 1M | 142.1 MiB | 549.3 KiB | 66.5 MiB | yes |
| 64 B | 10M | 1.39 GiB | 5.4 MiB | 665.3 MiB | yes |
| 64 B | 100M | 13.88 GiB | 53.6 MiB | 6.50 GiB | **No — 3.2× the cap** |
| 64 B | 1B | 138.77 GiB | **536.4 MiB** | 64.97 GiB | **No — 32.5× the cap** |

---

## 4. What this tells you, beyond the raw numbers

- **The heap ratio holds at every scale** — roughly 250–500× less heap than `HashSet`, consistent
  with the measured ~85 B vs ~0.25 B/id figures in `analysis.md`. That headline doesn't change
  with scale.
- **Heap is not literally negligible at the 1B tier.** 179–536 MiB of heap for the prefix index
  alone (depending on identifier length) is a real number, not "≈0." This is precisely the concern
  behind Open Question 5 in [`plans/1b-identifiers.md`](plans/1b-identifiers.md) ("keep mapped vs.
  size-conditional lift") — this table gives it concrete magnitude.
- **The 2 GiB single-file wall bites well before 1B** — already at 100M for anything longer than
  ~16-byte identifiers (table, §1). This is why sharding isn't optional for the 1B target.

---

## 5. Caveats

- Rows at 100M/1B are **computed, not benchmarked** — `DESIGN.md` §9.2 already flags this range as
  unbenchmarked, and it still is; only ≤ 10M has been directly measured (the three points these
  formulas were validated against).
- The `85 B` fixed `HashSet` overhead is JVM/flag-dependent (compressed oops, default load
  factor) — a reasonable, measured ballpark, not a portability guarantee.
- The "fits one file" column is about the *current*, unsharded implementation only (§1).
- The prefix-index share (`(8 + L) / 128`) uses average identifier length `L` as a proxy for
  actual per-bucket separator length. Exact for a fixed-length identifier shape (e.g. all UUIDs);
  an approximation, accurate in aggregate, for a mixed-length population.
