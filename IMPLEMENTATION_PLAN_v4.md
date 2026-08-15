# Dr Indexer Search Android v4 — implementation plan

Baseline: DrIndexerSearch v3.3. Desktop Mobile Export baseline: Dr. Indexer v18.14.8.

## Product rules

1. Search is the home workflow. With no scan selected, search means all scans; selecting one or more scans narrows both search and browsing context.
2. Search meaning must not change between Fast (RAM) and Disk modes.
3. A failed import must never destroy the last known-good mobile database.
4. Mobile Export v3 adds root/folder hierarchy without changing the desktop archive schema. Android remains able to read v1/v2 exports and raw desktop databases.
5. Low-memory behavior is chosen from the real Android heap budget, not total device RAM.

## Phase 1 — data safety and version negotiation — IMPLEMENTED

- Stage import into a sibling temporary database.
- Detect gzip by magic bytes; stream decompression with free-space reserve checks.
- Reject mobile schemas newer than the app supports.
- Run required-table validation and `PRAGMA quick_check` before replacing the live database.
- Preserve/restore the previous database around the final file swap; `.previous` is also a process-crash recovery marker until the import reaches its commit point.
- Add Database screen controls for Verify, Import/Replace, Remove local copy and index rebuilding.
- Keep Database operations in a ViewModel so Activity recreation/rotation does not own their lifetime.

## Phase 2 — Mobile Export schema v3 — IMPLEMENTED

- Export `scan_roots` with archived volume identity.
- Export actual folder nodes as `folders(scan_id, root_id, parent_id, name, path)`.
- Keep exact `size_bytes INTEGER`.
- Expose a compatibility `files` view so v4 code can remain schema-aware.
- Add lean parent/folder indexes for direct browsing.
- Add `meta`: schema version, minimum app version, source desktop version and export timestamp.
- Keep v1/v2 readers as fallback.

## Phase 3 — unified tolerant search — IMPLEMENTED

- One `SearchNormalizer` for ordinary search.
- Common separators become token boundaries.
- Arabic Alef variants, tatweel/tashkeel, Arabic/Persian Yeh/Kaf and digit variants are normalized.
- Ordinary multi-token queries use AND semantics.
- A fully quoted query keeps literal substring semantics.
- Search includes filename and archived folder path with true deduped result counts.
- Add Relevance as default sort.
- Fast Date sort uses actual modified epoch; Fast Scan sort uses actual scan name.
- Disk Scan sort joins the scans table and sorts by the real name.

## Phase 4 — memory and browsing performance — IMPLEMENTED

- Replace temporary `ArrayList<ByteArray>` index construction with a two-pass final-blob build.
- Store folder/root/date/exact-byte metadata in the Fast index so results show useful locations immediately.
- Auto mode uses `Runtime.maxMemory()` plus Android `memoryClass` and keeps a conservative build reserve; Fast index construction re-checks the budget after measuring the actual UTF-8/normalized blob sizes.
- Mobile v3 folder browsing uses indexed `(scan_id, root_id, parent_id)` lookup instead of re-deriving all folders from files on every tap.
- Add recursive `Search inside this location` for v3 hierarchy; v1/v2 receive immediate-level tolerant filtering as fallback.

## Phase 5 — UX restructuring — IMPLEMENTED

- Primary destinations: Search / Browse / Database.
- Search screen has explicit multi-scan scope; zero selection = All scans.
- Recent searches have a visible button.
- `Folders` is a first-class quick result filter; default is Files + Folders.
- Browse opens Scan -> Root -> Folder directly; long-pressing a scan sends it to Search as scope.
- Multi-root scans use exported roots instead of guessed common paths when using v3.
- Database screen centralizes import, integrity, search engine selection and rebuild/removal actions.

## Validation gates

- Pure JVM search-normalization smoke tests.
- Production Fast `SearchIndex` JVM smoke using deterministic Android/SQLite interface stubs.
- Android source invariant audit including import recovery/search-scope/source-set rules.
- Mobile Export v3 synthetic SQLite integration and `quick_check`.
- XML parse of all Android resources.
- Python syntax validation plus the supplied 302/302 v18.14.8 desktop regression suite on the focused integration candidate.
- Synthetic folder-browse benchmark and search-strategy benchmark.
- Android Gradle/JUnit build when Gradle 8.6 + Android SDK are available.
- Device acceptance still required: import rollback, 500k+/1M search latency, rotation during tasks, large multi-root browse, and low-memory trim behavior.
