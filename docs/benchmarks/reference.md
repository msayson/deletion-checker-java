# Benchmark reference results

`DeletionChecker` (packed set + mmap + two-level search) vs a plain `HashSet<String>` baseline. Regenerate with `./gradlew :benchmarks:benchmark -Dbench.publish=true`; see [README.md](README.md).

| | |
| --- | --- |
| Generated | 2026-09-07T18:43:00.328985797Z |
| JVM | OpenJDK 64-Bit Server VM 21.0.11 |
| OS | Linux 6.6.87.2-microsoft-standard-WSL2 / amd64 |
| CPU | 13th Gen Intel(R) Core(TM) i7-13620H (16 cores visible) |
| Max heap | 6372.00 MiB |

## Sweep A — per-type scaling (1 entity type)

### Memory

| shape | ids | HashSet heap B/id | packed heap B/id | packed mapped B/id | packed total B/id | total ratio |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| alnum16 | 1,000 | 100.31 | 0.77 | 20.30 | 21.06 | 0.21x |
| alnum16 | 10,000 | 98.57 | 0.25 | 20.20 | 20.45 | 0.21x |
| alnum16 | 100,000 | 102.49 | 0.19 | 20.19 | 20.38 | 0.20x |
| alnum16 | 1,000,000 | 100.39 | 0.19 | 20.19 | 20.38 | 0.20x |
| alnum16 | 10,000,000 | 99.85 | 0.19 | 20.19 | 20.38 | 0.20x |
| customer | 1,000 | 100.54 | 0.85 | 19.29 | 20.14 | 0.20x |
| customer | 10,000 | 98.57 | 0.24 | 19.19 | 19.43 | 0.20x |
| customer | 100,000 | 102.49 | 0.19 | 19.18 | 19.37 | 0.19x |
| customer | 1,000,000 | 100.39 | 0.18 | 19.18 | 19.36 | 0.19x |
| customer | 10,000,000 | 99.07 | 0.48 | 19.18 | 19.66 | 0.20x |
| uuid | 1,000 | 144.24 | 0.93 | 40.46 | 41.38 | 0.29x |
| uuid | 10,000 | 122.57 | 0.40 | 40.36 | 40.76 | 0.33x |
| uuid | 100,000 | 126.49 | 0.35 | 40.35 | 40.70 | 0.32x |
| uuid | 1,000,000 | 124.37 | 0.34 | 40.34 | 40.69 | 0.33x |
| uuid | 10,000,000 | 123.00 | 0.34 | 40.34 | 40.69 | 0.33x |

### Lookup latency (microseconds)

| shape | ids | impl | pos p50 | pos p99 | pos p99.9 | neg p50 | neg p99 | neg p99.9 |
| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| alnum16 | 1,000 | hashset | 0.02 | 0.04 | 0.09 | 0.03 | 0.18 | 0.29 |
| alnum16 | 1,000 | packed | 0.10 | 0.15 | 0.22 | 0.12 | 0.27 | 0.39 |
| alnum16 | 1,000 | packed contains() | 0.07 | 0.11 | 0.14 | 0.10 | 0.14 | 0.19 |
| alnum16 | 10,000 | hashset | 0.03 | 0.06 | 0.16 | 0.04 | 0.20 | 0.29 |
| alnum16 | 10,000 | packed | 0.15 | 0.25 | 0.39 | 0.16 | 0.32 | 0.44 |
| alnum16 | 10,000 | packed contains() | 0.13 | 0.19 | 0.35 | 0.14 | 0.19 | 0.26 |
| alnum16 | 100,000 | hashset | 0.06 | 0.20 | 0.32 | 0.05 | 0.26 | 0.39 |
| alnum16 | 100,000 | packed | 0.24 | 0.46 | 0.67 | 0.22 | 0.42 | 0.93 |
| alnum16 | 100,000 | packed contains() | 0.19 | 0.27 | 0.57 | 0.19 | 0.27 | 0.65 |
| alnum16 | 1,000,000 | hashset | 0.33 | 0.77 | 4.24 | 0.18 | 0.62 | 1.04 |
| alnum16 | 1,000,000 | packed | 0.47 | 0.80 | 4.98 | 0.41 | 0.77 | 4.61 |
| alnum16 | 1,000,000 | packed contains() | 0.30 | 0.59 | 1.37 | 0.32 | 0.60 | 0.95 |
| alnum16 | 10,000,000 | hashset | 0.42 | 0.73 | 4.46 | 0.22 | 0.49 | 0.77 |
| alnum16 | 10,000,000 | packed | 0.78 | 1.15 | 8.08 | 0.66 | 1.03 | 7.98 |
| alnum16 | 10,000,000 | packed contains() | 0.56 | 0.88 | 5.15 | 0.57 | 0.88 | 5.01 |
| customer | 1,000 | hashset | 0.02 | 0.04 | 0.05 | 0.03 | 0.21 | 0.29 |
| customer | 1,000 | packed | 0.12 | 0.18 | 0.25 | 0.15 | 0.33 | 0.43 |
| customer | 1,000 | packed contains() | 0.11 | 0.15 | 0.20 | 0.13 | 0.17 | 0.23 |
| customer | 10,000 | hashset | 0.03 | 0.11 | 0.20 | 0.04 | 0.21 | 0.30 |
| customer | 10,000 | packed | 0.20 | 0.31 | 0.45 | 0.20 | 0.39 | 0.69 |
| customer | 10,000 | packed contains() | 0.17 | 0.23 | 0.34 | 0.19 | 0.25 | 0.34 |
| customer | 100,000 | hashset | 0.06 | 0.17 | 0.29 | 0.05 | 0.24 | 0.36 |
| customer | 100,000 | packed | 0.26 | 0.44 | 1.35 | 0.28 | 0.49 | 0.79 |
| customer | 100,000 | packed contains() | 0.21 | 0.32 | 0.85 | 0.23 | 0.35 | 0.56 |
| customer | 1,000,000 | hashset | 0.31 | 0.52 | 0.99 | 0.15 | 0.46 | 0.64 |
| customer | 1,000,000 | packed | 0.52 | 0.87 | 4.95 | 0.44 | 0.78 | 4.28 |
| customer | 1,000,000 | packed contains() | 0.34 | 0.62 | 0.84 | 0.35 | 0.63 | 2.67 |
| customer | 10,000,000 | hashset | 0.48 | 0.90 | 4.63 | 0.24 | 0.76 | 1.46 |
| customer | 10,000,000 | packed | 0.80 | 1.21 | 8.70 | 0.68 | 1.05 | 8.87 |
| customer | 10,000,000 | packed contains() | 0.59 | 1.10 | 4.99 | 0.60 | 1.03 | 4.88 |
| uuid | 1,000 | hashset | 0.02 | 0.07 | 0.15 | 0.04 | 0.24 | 0.31 |
| uuid | 1,000 | packed | 0.13 | 0.22 | 0.33 | 0.14 | 0.34 | 0.47 |
| uuid | 1,000 | packed contains() | 0.08 | 0.15 | 0.23 | 0.09 | 0.17 | 0.29 |
| uuid | 10,000 | hashset | 0.03 | 0.10 | 0.17 | 0.04 | 0.22 | 0.30 |
| uuid | 10,000 | packed | 0.19 | 0.30 | 1.34 | 0.20 | 0.41 | 0.57 |
| uuid | 10,000 | packed contains() | 0.14 | 0.21 | 0.32 | 0.15 | 0.26 | 0.41 |
| uuid | 100,000 | hashset | 0.12 | 0.33 | 0.47 | 0.05 | 0.28 | 0.40 |
| uuid | 100,000 | packed | 0.33 | 0.54 | 3.99 | 0.31 | 1.27 | 15.21 |
| uuid | 100,000 | packed contains() | 0.22 | 0.35 | 0.57 | 0.22 | 0.33 | 0.50 |
| uuid | 1,000,000 | hashset | 0.32 | 0.60 | 4.23 | 0.15 | 0.45 | 0.66 |
| uuid | 1,000,000 | packed | 0.58 | 1.10 | 7.06 | 0.50 | 0.95 | 5.43 |
| uuid | 1,000,000 | packed contains() | 0.41 | 0.78 | 4.61 | 0.41 | 0.76 | 4.50 |
| uuid | 10,000,000 | hashset | 0.47 | 0.77 | 4.54 | 0.23 | 0.61 | 1.02 |
| uuid | 10,000,000 | packed | 1.10 | 2.22 | 12.51 | 0.77 | 1.28 | 8.57 |
| uuid | 10,000,000 | packed contains() | 0.65 | 1.04 | 7.60 | 0.72 | 1.24 | 8.46 |

### Build cost (milliseconds)

| shape | ids | HashSet build | packed generate (self-validates) | packed load |
| --- | ---: | ---: | ---: | ---: |
| alnum16 | 1,000 | 0 | 1 | 1 |
| alnum16 | 10,000 | 0 | 5 | 1 |
| alnum16 | 100,000 | 3 | 31 | 1 |
| alnum16 | 1,000,000 | 59 | 515 | 2 |
| alnum16 | 10,000,000 | 656 | 7228 | 34 |
| customer | 1,000 | 0 | 1 | 1 |
| customer | 10,000 | 0 | 3 | 1 |
| customer | 100,000 | 3 | 29 | 1 |
| customer | 1,000,000 | 59 | 499 | 2 |
| customer | 10,000,000 | 1040 | 7275 | 19 |
| uuid | 1,000 | 0 | 2 | 0 |
| uuid | 10,000 | 0 | 9 | 0 |
| uuid | 100,000 | 3 | 64 | 1 |
| uuid | 1,000,000 | 76 | 628 | 5 |
| uuid | 10,000,000 | 890 | 8720 | 36 |

## Sweep B — entity-type-count scaling

`sizePerType` identifiers each, shape rotated across types.

| types | total ids | impl | build/gen ms | load ms | heap MiB | pos p99.9 us | neg p99.9 us |
| ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1,000,000 | hashset | 38 | — | 119.55 | 4.35 | 0.55 |
| 1 | 1,000,000 | packed | 698 | 4 | 1.05 | 4.42 | 4.49 |
| 5 | 5,000,000 | hashset | 166 | — | 519.31 | 4.77 | 0.75 |
| 5 | 5,000,000 | packed | 2692 | 21 | 2.53 | 5.47 | 8.18 |
| 5 | 1,000,000 | packed(1 of N) | — | 5 | 1.08 | 6.18 | 7.79 |
| 10 | 10,000,000 | hashset | 373 | — | 1037.78 | 4.72 | 0.90 |
| 10 | 10,000,000 | packed | 5192 | 30 | 4.78 | 8.83 | 8.89 |
| 10 | 1,000,000 | packed(1 of N) | — | 5 | 1.68 | 7.83 | 4.89 |
