# Benchmark reference results

`DeletionChecker` (packed set + mmap + two-level search) vs a plain `HashSet<String>` baseline. Regenerate with `./gradlew :benchmarks:benchmark -Dbench.publish=true`; see [README.md](README.md).

| | |
| --- | --- |
| Generated | 2026-09-07T21:12:20.608774236Z |
| JVM | OpenJDK 64-Bit Server VM 21.0.11 |
| OS | Linux 6.6.87.2-microsoft-standard-WSL2 / amd64 |
| CPU | 13th Gen Intel(R) Core(TM) i7-13620H (16 cores visible) |
| Max heap | 7282.00 MiB |

## Sweep A — per-type scaling (1 entity type)

### Memory

| shape | ids | HashSet heap B/id | packed heap B/id | packed mapped B/id | packed total B/id | total ratio |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| alnum16 | 1,000 | 100.31 | 0.79 | 21.52 | 22.31 | 0.22x |
| alnum16 | 10,000 | 98.57 | 0.25 | 21.41 | 21.66 | 0.22x |
| alnum16 | 100,000 | 102.49 | 0.19 | 21.40 | 21.59 | 0.21x |
| alnum16 | 1,000,000 | 100.39 | 0.19 | 21.40 | 21.59 | 0.22x |
| alnum16 | 10,000,000 | 99.85 | 0.48 | 21.40 | 21.88 | 0.22x |
| customer | 1,000 | 635.20 | 0.87 | 20.51 | 21.38 | 0.03x |
| customer | 10,000 | 98.57 | 0.24 | 20.41 | 20.65 | 0.21x |
| customer | 100,000 | 102.49 | 0.19 | 20.39 | 20.58 | 0.20x |
| customer | 1,000,000 | 100.39 | 0.18 | 20.39 | 20.57 | 0.20x |
| customer | 10,000,000 | 97.68 | 0.39 | 20.39 | 20.78 | 0.21x |
| uuid | 1,000 | 144.24 | 0.95 | 41.68 | 42.63 | 0.30x |
| uuid | 10,000 | 122.57 | 0.41 | 41.57 | 41.98 | 0.34x |
| uuid | 100,000 | 126.49 | 0.35 | 41.56 | 41.91 | 0.33x |
| uuid | 1,000,000 | 124.39 | 0.34 | 41.55 | 41.90 | 0.34x |
| uuid | 10,000,000 | 123.74 | 0.34 | 41.55 | 41.90 | 0.34x |

### Lookup latency (microseconds)

| shape | ids | impl | pos p50 | pos p99 | pos p99.9 | neg p50 | neg p99 | neg p99.9 |
| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| alnum16 | 1,000 | hashset | 0.03 | 0.04 | 0.06 | 0.03 | 0.20 | 0.28 |
| alnum16 | 1,000 | packed | 0.11 | 0.21 | 0.35 | 0.07 | 0.25 | 0.38 |
| alnum16 | 1,000 | packed contains() | 0.11 | 0.17 | 0.33 | 0.05 | 0.14 | 0.24 |
| alnum16 | 10,000 | hashset | 0.03 | 0.06 | 0.13 | 0.04 | 0.21 | 0.30 |
| alnum16 | 10,000 | packed | 0.16 | 0.24 | 0.36 | 0.06 | 0.25 | 0.36 |
| alnum16 | 10,000 | packed contains() | 0.15 | 0.21 | 0.35 | 0.05 | 0.18 | 0.25 |
| alnum16 | 100,000 | hashset | 0.06 | 0.20 | 0.31 | 0.05 | 0.25 | 0.35 |
| alnum16 | 100,000 | packed | 0.24 | 0.42 | 0.79 | 0.07 | 0.34 | 0.59 |
| alnum16 | 100,000 | packed contains() | 0.20 | 0.33 | 0.67 | 0.05 | 0.26 | 0.48 |
| alnum16 | 1,000,000 | hashset | 0.31 | 0.52 | 4.17 | 0.14 | 0.42 | 0.59 |
| alnum16 | 1,000,000 | packed | 0.49 | 0.94 | 7.14 | 0.08 | 0.57 | 0.91 |
| alnum16 | 1,000,000 | packed contains() | 0.38 | 0.81 | 4.22 | 0.06 | 0.53 | 0.84 |
| alnum16 | 10,000,000 | hashset | 0.41 | 0.78 | 4.88 | 0.23 | 0.74 | 4.49 |
| alnum16 | 10,000,000 | packed | 1.01 | 1.56 | 13.63 | 0.16 | 1.18 | 1.79 |
| alnum16 | 10,000,000 | packed contains() | 0.76 | 1.47 | 8.80 | 0.08 | 0.82 | 1.35 |
| customer | 1,000 | hashset | 0.03 | 0.04 | 0.10 | 0.03 | 0.18 | 0.28 |
| customer | 1,000 | packed | 0.14 | 0.19 | 0.33 | 0.06 | 0.27 | 0.38 |
| customer | 1,000 | packed contains() | 0.13 | 0.20 | 0.34 | 0.05 | 0.15 | 0.22 |
| customer | 10,000 | hashset | 0.03 | 0.11 | 0.23 | 0.04 | 0.19 | 0.28 |
| customer | 10,000 | packed | 0.19 | 0.28 | 0.49 | 0.07 | 0.28 | 0.41 |
| customer | 10,000 | packed contains() | 0.18 | 0.26 | 0.44 | 0.05 | 0.21 | 0.28 |
| customer | 100,000 | hashset | 0.06 | 0.27 | 0.38 | 0.04 | 0.22 | 0.31 |
| customer | 100,000 | packed | 0.28 | 0.66 | 1.22 | 0.07 | 0.35 | 0.63 |
| customer | 100,000 | packed contains() | 0.23 | 0.35 | 0.80 | 0.05 | 0.27 | 0.41 |
| customer | 1,000,000 | hashset | 0.30 | 0.52 | 4.28 | 0.13 | 0.41 | 0.57 |
| customer | 1,000,000 | packed | 0.51 | 0.90 | 7.89 | 0.08 | 0.58 | 0.91 |
| customer | 1,000,000 | packed contains() | 0.36 | 0.67 | 4.79 | 0.06 | 0.49 | 0.77 |
| customer | 10,000,000 | hashset | 0.41 | 0.76 | 4.75 | 0.22 | 0.64 | 1.01 |
| customer | 10,000,000 | packed | 0.98 | 1.46 | 11.78 | 0.14 | 0.98 | 1.58 |
| customer | 10,000,000 | packed contains() | 0.74 | 1.18 | 9.18 | 0.07 | 0.91 | 1.40 |
| uuid | 1,000 | hashset | 0.03 | 0.04 | 0.12 | 0.03 | 0.23 | 0.30 |
| uuid | 1,000 | packed | 0.14 | 0.21 | 0.37 | 0.09 | 0.44 | 3.12 |
| uuid | 1,000 | packed contains() | 0.13 | 0.23 | 0.37 | 0.06 | 0.17 | 0.28 |
| uuid | 10,000 | hashset | 0.03 | 0.07 | 0.18 | 0.04 | 0.22 | 0.30 |
| uuid | 10,000 | packed | 0.20 | 0.32 | 0.67 | 0.09 | 0.34 | 0.59 |
| uuid | 10,000 | packed contains() | 0.18 | 0.28 | 0.75 | 0.07 | 0.23 | 0.35 |
| uuid | 100,000 | hashset | 0.12 | 0.33 | 0.44 | 0.06 | 0.28 | 0.42 |
| uuid | 100,000 | packed | 0.32 | 1.24 | 13.64 | 0.08 | 0.37 | 0.64 |
| uuid | 100,000 | packed contains() | 0.23 | 0.38 | 2.36 | 0.06 | 0.28 | 0.50 |
| uuid | 1,000,000 | hashset | 0.33 | 0.56 | 4.21 | 0.20 | 0.63 | 1.11 |
| uuid | 1,000,000 | packed | 0.69 | 1.16 | 10.94 | 0.10 | 0.80 | 1.38 |
| uuid | 1,000,000 | packed contains() | 0.54 | 1.02 | 9.58 | 0.07 | 0.58 | 0.96 |
| uuid | 10,000,000 | hashset | 0.70 | 1.34 | 10.05 | 0.31 | 0.86 | 1.49 |
| uuid | 10,000,000 | packed | 1.28 | 2.01 | 16.29 | 0.18 | 1.27 | 2.29 |
| uuid | 10,000,000 | packed contains() | 0.91 | 1.45 | 11.87 | 0.09 | 0.99 | 1.50 |

### Build cost (milliseconds)

| shape | ids | HashSet build | packed generate (self-validates) | packed load |
| --- | ---: | ---: | ---: | ---: |
| alnum16 | 1,000 | 0 | 1 | 1 |
| alnum16 | 10,000 | 0 | 4 | 0 |
| alnum16 | 100,000 | 2 | 35 | 1 |
| alnum16 | 1,000,000 | 52 | 700 | 3 |
| alnum16 | 10,000,000 | 1011 | 9915 | 17 |
| customer | 1,000 | 0 | 1 | 0 |
| customer | 10,000 | 0 | 4 | 0 |
| customer | 100,000 | 2 | 32 | 2 |
| customer | 1,000,000 | 51 | 667 | 5 |
| customer | 10,000,000 | 776 | 9546 | 19 |
| uuid | 1,000 | 0 | 2 | 1 |
| uuid | 10,000 | 0 | 12 | 1 |
| uuid | 100,000 | 6 | 80 | 2 |
| uuid | 1,000,000 | 57 | 784 | 7 |
| uuid | 10,000,000 | 770 | 12695 | 40 |

## Sweep B — entity-type-count scaling

`sizePerType` identifiers each, shape rotated across types.

| types | total ids | impl | build/gen ms | load ms | heap MiB | pos p99.9 us | neg p99.9 us |
| ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1,000,000 | hashset | 37 | — | 119.34 | 3.90 | 0.65 |
| 1 | 1,000,000 | packed | 917 | 7 | 0.33 | 5.68 | 1.11 |
| 5 | 5,000,000 | hashset | 171 | — | 521.62 | 6.33 | 0.82 |
| 5 | 5,000,000 | packed | 3568 | 16 | 1.68 | 10.08 | 1.42 |
| 5 | 1,000,000 | packed(1 of N) | — | 8 | 0.33 | 8.06 | 1.66 |
| 10 | 10,000,000 | hashset | 326 | — | 1040.00 | 6.85 | 4.20 |
| 10 | 10,000,000 | packed | 6563 | 38 | 3.98 | 11.36 | 1.53 |
| 10 | 1,000,000 | packed(1 of N) | — | 3 | 17.08 | 13.92 | 1.26 |
