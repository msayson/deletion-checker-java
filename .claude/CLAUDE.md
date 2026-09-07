# deletion-checker-java

Local, exact deletion-state lookup over a packed immutable binary dataset. See `docs/DESIGN.md` for architecture and binary layout.

## Build & test
<!-- TODO: fill in once the build exists -->
- Build: `TBD`
- All tests: `TBD`
- Single test: `TBD`
- Java version: `TBD`

## Module layout
- `core/` — runtime deletion library. No generator code.
- `dataset-generator/` — build-time dataset generator. No runtime code.
- `data/` — generated `.dat` datasets.
- `docs/` — design docs.

## Invariants
- Public API is `isDeleted` and `filter`. Signatures in `docs/DESIGN.md` §1; don't change them.
- Dataset is immutable at runtime — no mutation paths.
- Binary format changes require explicit instruction; a change means bumping the header version.
- No new external dependencies without approval.

## Tests
New/changed functionality needs tests in the matching `src/test/java`.
