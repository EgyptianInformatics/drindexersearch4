# Dr Indexer Search v4.0 — changelog

## Database safety
- Imports are staged, validated and SQLite-checked before publication.
- The current local database is preserved until the candidate is proven readable; failed publication attempts restore the previous copy. A leftover `.previous` file after process death is treated as an uncommitted import and recovered automatically.
- Unsupported future mobile schemas are rejected explicitly.
- Streaming decompression checks remaining local storage instead of blindly filling app storage.
- Database Import/Verify/Rebuild operations now live in a configuration-change-safe ViewModel.

## Search
- Added separator-tolerant AND-token search.
- Added Arabic-tolerant normalization for safe Alef/Yeh/Kaf/tashkeel/tatweel/digit variants.
- Quoted searches remain literal, including `%` and `_` in Disk mode (no accidental SQL wildcard semantics).
- Added Relevance default sort.
- Fixed Fast Date Modified and Scan Name sorting.
- Fast results now retain folder/root/date/exact-byte metadata.
- Filename and archived-path matching use the same user-visible semantics in Fast and Disk modes.
- Default scope is Files + Folders; added a visible Folders-only quick filter.
- Multi-scan scope: no selected scans means all scans.

## Performance
- Fast index construction is two-pass, avoids retaining one temporary ByteArray per filename, and performs a measured-size heap guard before final allocations.
- Automatic Fast/Disk selection uses the Android app heap limit.
- Mobile v3 direct folder hierarchy replaces per-tap DISTINCT folder reconstruction.
- Low-memory mode prefers on-device FTS5 trigram when the device SQLite supports it; safe Latin/digit token queries use a lightweight fallback and variant-sensitive Arabic queries retain the correctness-first SQL normalizer when FTS trigram is unavailable.
- Legacy v3.x raw-filename FTS data is detected and rebuilt into the normalized v4 local index.

## Browse / UX
- Primary navigation is Search / Browse / Database.
- Scan tap browses immediately; long-press scopes Search to that scan.
- v3 browsing is Scan -> Root -> Folder and supports multi-root scans.
- Added `Search inside this location` with stale-generation protection so older folder/search workers cannot overwrite a newer location.
- Recursive browse search shows path context.
- Added visible Recent Searches control.
- Added central Database manager with Verify, Import/Replace, index mode/rebuild, and Remove Local Copy.

## Desktop mobile export integration
- Proposed Mobile Export schema is now v3: roots, real folder hierarchy, exact bytes and metadata/version negotiation.
- This is delivered as a focused patch/candidate against v18.14.8 so it can be merged into the wider Dr. Indexer v18.15 desktop work without silently replacing unrelated v18.15 changes.

## Final hardening in this candidate
- Raw desktop compatibility excludes hidden/incomplete (`corrupted`) scans from All-scan search/browse lists.
- One-shot scan scope intents are consumed after application so rotation cannot silently reapply an old scope.
- Full source invariant audit, production Fast SearchIndex JVM smoke, Mobile Export v3 integration, and 302/302 supplied desktop regressions pass.
