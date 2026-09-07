# Benchmark reference results

`DeletionChecker` (packed set + mmap + two-level search) vs a plain `HashSet<String>` baseline. Regenerate with `./gradlew :benchmarks:benchmark -Dbench.publish=true`; see [README.md](README.md).

| | |
| --- | --- |
| Generated | 2026-09-07T19:22:36.683793072Z |
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
| alnum16 | 10,000,000 | 98.16 | 0.40 | 20.19 | 20.59 | 0.21x |
| customer | 1,000 | 100.31 | 0.76 | 19.29 | 20.05 | 0.20x |
| customer | 10,000 | 98.57 | 0.24 | 19.19 | 19.43 | 0.20x |
| customer | 100,000 | 102.49 | 0.19 | 19.18 | 19.37 | 0.19x |
| customer | 1,000,000 | 100.39 | 0.18 | 19.18 | 19.36 | 0.19x |
| customer | 10,000,000 | 98.22 | 0.45 | 19.18 | 19.63 | 0.20x |
| uuid | 1,000 | 124.31 | 0.93 | 40.46 | 41.38 | 0.33x |
| uuid | 10,000 | 122.57 | 0.40 | 40.36 | 40.76 | 0.33x |
| uuid | 100,000 | 126.49 | 0.35 | 40.35 | 40.69 | 0.32x |
| uuid | 1,000,000 | 124.39 | 0.34 | 40.34 | 40.69 | 0.33x |
| uuid | 10,000,000 | 121.92 | 0.74 | 40.34 | 41.09 | 0.34x |

### Lookup latency (microseconds)

| shape | ids | impl | pos p50 | pos p99 | pos p99.9 | neg p50 | neg p99 | neg p99.9 |
| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| alnum16 | 1,000 | hashset | 0.03 | 0.04 | 0.06 | 0.03 | 0.13 | 0.25 |
| alnum16 | 1,000 | packed | 0.10 | 0.13 | 0.19 | 0.10 | 0.25 | 0.36 |
| alnum16 | 1,000 | packed contains() | 0.09 | 0.14 | 0.27 | 0.09 | 0.14 | 0.31 |
| alnum16 | 10,000 | hashset | 0.04 | 0.14 | 0.33 | 0.04 | 0.13 | 0.25 |
| alnum16 | 10,000 | packed | 0.13 | 0.19 | 0.35 | 0.15 | 0.29 | 0.45 |
| alnum16 | 10,000 | packed contains() | 0.13 | 0.20 | 0.35 | 0.14 | 0.20 | 0.26 |
| alnum16 | 100,000 | hashset | 0.06 | 0.20 | 0.31 | 0.05 | 0.21 | 0.31 |
| alnum16 | 100,000 | packed | 0.21 | 0.38 | 0.70 | 0.20 | 0.38 | 0.74 |
| alnum16 | 100,000 | packed contains() | 0.18 | 0.26 | 0.41 | 0.19 | 0.30 | 0.54 |
| alnum16 | 1,000,000 | hashset | 0.27 | 0.49 | 0.84 | 0.13 | 0.41 | 0.58 |
| alnum16 | 1,000,000 | packed | 0.42 | 0.73 | 4.34 | 0.34 | 0.64 | 1.22 |
| alnum16 | 1,000,000 | packed contains() | 0.30 | 0.61 | 1.75 | 0.31 | 0.63 | 3.83 |
| alnum16 | 10,000,000 | hashset | 0.38 | 0.71 | 1.06 | 0.23 | 0.60 | 0.90 |
| alnum16 | 10,000,000 | packed | 0.85 | 1.28 | 9.60 | 0.67 | 1.08 | 7.75 |
| alnum16 | 10,000,000 | packed contains() | 0.63 | 1.11 | 9.06 | 0.63 | 1.05 | 8.70 |
| customer | 1,000 | hashset | 0.03 | 0.04 | 0.15 | 0.03 | 0.13 | 0.25 |
| customer | 1,000 | packed | 0.11 | 0.15 | 0.23 | 0.13 | 0.28 | 0.40 |
| customer | 1,000 | packed contains() | 0.12 | 0.20 | 0.30 | 0.13 | 0.19 | 0.31 |
| customer | 10,000 | hashset | 0.03 | 0.12 | 0.22 | 0.04 | 0.17 | 0.27 |
| customer | 10,000 | packed | 0.16 | 0.22 | 0.35 | 0.18 | 0.34 | 0.47 |
| customer | 10,000 | packed contains() | 0.16 | 0.24 | 0.42 | 0.18 | 0.25 | 0.40 |
| customer | 100,000 | hashset | 0.06 | 0.22 | 0.33 | 0.04 | 0.19 | 0.30 |
| customer | 100,000 | packed | 0.23 | 0.38 | 0.60 | 0.25 | 0.46 | 0.68 |
| customer | 100,000 | packed contains() | 0.21 | 0.37 | 0.67 | 0.23 | 0.34 | 0.60 |
| customer | 1,000,000 | hashset | 0.30 | 0.52 | 4.12 | 0.12 | 0.39 | 0.57 |
| customer | 1,000,000 | packed | 0.45 | 0.71 | 4.39 | 0.37 | 0.67 | 4.07 |
| customer | 1,000,000 | packed contains() | 0.32 | 0.60 | 1.44 | 0.34 | 0.61 | 1.04 |
| customer | 10,000,000 | hashset | 0.40 | 0.68 | 4.41 | 0.21 | 0.54 | 0.75 |
| customer | 10,000,000 | packed | 0.92 | 1.44 | 10.71 | 0.73 | 1.21 | 8.49 |
| customer | 10,000,000 | packed contains() | 0.63 | 1.12 | 9.88 | 0.60 | 1.05 | 7.43 |
| uuid | 1,000 | hashset | 0.03 | 0.04 | 0.06 | 0.03 | 0.20 | 0.29 |
| uuid | 1,000 | packed | 0.13 | 0.19 | 0.28 | 0.13 | 0.33 | 0.46 |
| uuid | 1,000 | packed contains() | 0.11 | 0.15 | 0.22 | 0.10 | 0.16 | 0.29 |
| uuid | 10,000 | hashset | 0.03 | 0.11 | 0.23 | 0.04 | 0.26 | 0.37 |
| uuid | 10,000 | packed | 0.19 | 0.29 | 0.44 | 0.20 | 0.40 | 0.70 |
| uuid | 10,000 | packed contains() | 0.15 | 0.23 | 0.36 | 0.14 | 0.22 | 0.40 |
| uuid | 100,000 | hashset | 0.07 | 0.29 | 0.43 | 0.05 | 0.26 | 0.36 |
| uuid | 100,000 | packed | 0.26 | 0.42 | 0.80 | 0.25 | 0.46 | 0.70 |
| uuid | 100,000 | packed contains() | 0.23 | 0.37 | 0.52 | 0.21 | 0.34 | 0.59 |
| uuid | 1,000,000 | hashset | 0.30 | 0.53 | 4.12 | 0.12 | 0.40 | 0.55 |
| uuid | 1,000,000 | packed | 0.58 | 1.02 | 8.71 | 0.44 | 0.81 | 2.16 |
| uuid | 1,000,000 | packed contains() | 0.39 | 0.70 | 1.61 | 0.38 | 0.69 | 1.66 |
| uuid | 10,000,000 | hashset | 0.39 | 0.69 | 4.41 | 0.22 | 0.54 | 0.74 |
| uuid | 10,000,000 | packed | 1.09 | 1.67 | 11.65 | 0.85 | 1.35 | 10.03 |
| uuid | 10,000,000 | packed contains() | 0.78 | 1.27 | 10.06 | 0.77 | 1.26 | 9.18 |

### Build cost (milliseconds)

| shape | ids | HashSet build | packed generate (self-validates) | packed load |
| --- | ---: | ---: | ---: | ---: |
| alnum16 | 1,000 | 0 | 1 | 1 |
| alnum16 | 10,000 | 0 | 3 | 1 |
| alnum16 | 100,000 | 4 | 26 | 1 |
| alnum16 | 1,000,000 | 55 | 504 | 4 |
| alnum16 | 10,000,000 | 749 | 7043 | 19 |
| customer | 1,000 | 0 | 1 | 3 |
| customer | 10,000 | 0 | 3 | 2 |
| customer | 100,000 | 2 | 25 | 2 |
| customer | 1,000,000 | 47 | 495 | 2 |
| customer | 10,000,000 | 701 | 7107 | 18 |
| uuid | 1,000 | 0 | 1 | 0 |
| uuid | 10,000 | 0 | 28 | 0 |
| uuid | 100,000 | 11 | 104 | 3 |
| uuid | 1,000,000 | 47 | 536 | 3 |
| uuid | 10,000,000 | 636 | 8033 | 34 |

## Sweep B — entity-type-count scaling

`sizePerType` identifiers each, shape rotated across types.

| types | total ids | impl | build/gen ms | load ms | heap MiB | pos p99.9 us | neg p99.9 us |
| ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1,000,000 | hashset | 37 | — | 118.63 | 4.42 | 0.61 |
| 1 | 1,000,000 | packed | 610 | 8 | 0.33 | 4.66 | 4.62 |
| 5 | 5,000,000 | hashset | 154 | — | 524.47 | 4.50 | 3.77 |
| 5 | 5,000,000 | packed | 2524 | 21 | 1.19 | 8.69 | 9.22 |
| 5 | 1,000,000 | packed(1 of N) | — | 9 | 0.33 | 5.38 | 5.36 |
| 10 | 10,000,000 | hashset | 320 | — | 1045.55 | 6.29 | 4.14 |
| 10 | 10,000,000 | packed | 4738 | 29 | 2.37 | 8.87 | 9.10 |
| 10 | 1,000,000 | packed(1 of N) | — | 5 | 0.33 | 9.41 | 4.87 |
