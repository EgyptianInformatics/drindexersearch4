# Dr Indexer Search Android v4.0 — validation record

Date: 2026-08-15  
Baseline: DrIndexerSearch v3.3  
Desktop Mobile Export baseline: Dr. Indexer v18.14.8

## Passed in this environment

### Android source/resource audit

`validation/validate_android_source_v4.py` passes all implementation invariants, including:

- **PASS** — versionName 4.0 / advanced versionCode.
- **PASS** — Search / Browse / Database structure and DatabaseActivity registration.
- **PASS** — zero selected scans means All scans; default results are Files + Folders.
- **PASS** — Folder quick filter and Relevance default sort are wired.
- **PASS** — one-shot scan scope is consumed so rotation cannot re-apply an old scope.
- **PASS** — Fast Date sort uses modified epoch and Scan sort uses real scan names.
- **PASS** — Fast results carry archived folder paths.
- **PASS** — two-pass index build has a second measured-size heap guard before final allocation.
- **PASS** — no per-filename `ArrayList<ByteArray>` allocation remains.
- **PASS** — staged import + `quick_check` + previous-DB rollback path are present.
- **PASS** — interrupted import crash recovery restores `.previous` before startup open.
- **PASS** — future mobile schemas are rejected; schema v3 is supported.
- **PASS** — direct `(scan_id, root_id, parent_id)` hierarchy browsing is wired.
- **PASS** — stale browse/search generations cannot overwrite a newer folder location.
- **PASS** — Database Import/Verify/Rebuild run through a ViewModel IO scope.
- **PASS** — old v3.x derived FTS shape is detected and rebuilt.
- **PASS** — FTS5 trigram is preferred and compatibility mode is disclosed truthfully.
- **PASS** — quoted Disk search uses literal `INSTR`, so `%` and `_` are not accidental wildcards.
- **PASS** — ordinary ASCII token fallback avoids the expensive full Unicode SQL normalizer when safe.
- **PASS** — direct raw-desktop all-scan search excludes hidden/incomplete (`corrupted`) scans.
- **PASS** — no normalized shadow filename column is stored in the exported data model.
- **PASS** — archived v3.3 references are outside Android source/resource sets.
- **PASS** — all **36** XML files under `app/src/main` parse successfully.

### Search normalization — pure JVM

`SearchNormalizer.kt` was compiled directly with `kotlinc` together with the
smoke harness. **12/12 checks passed**:

1. mixed separator equivalence
2. dot-separated query vs spaced name
3. Arabic Alef variants
4. tashkeel/tatweel removal
5. Persian/Arabic Yeh and Kaf variants
6. Arabic-Indic digit equivalence
7. Yeh/Alef-Maqsura equivalence
8. AND semantics reject a missing token
9. quoted literal phrase matches exact separators
10. quoted literal does not become separator-tolerant
11. one-character ordinary query rejected
12. two-character ordinary query accepted

A JUnit equivalent is included at
`app/src/test/java/com/drindexer/search/SearchNormalizerTest.kt`.

### Actual Fast SearchIndex core — JVM with minimal Android/SQLite stubs

The **production `SearchIndex.kt` itself** was compiled and executed together
with production SearchNormalizer/FileCategory/FileItem/SearchFilter. The harness
uses deterministic Cursor/SQLiteDatabase stubs only for the Android interfaces.

**17 functional checks passed**, covering:

- index load and entry count
- mixed filename separators
- Arabic tolerant search
- path-only matches included in the same result set
- true deduplicated filename/path result count
- Folder-only filter
- selected-scan scope
- Date Modified ordering by real epoch
- Scan ordering by real scan name
- Relevance exact-name priority
- archived path present in Fast results
- quoted exact separator behavior
- quoted alternate-separator rejection
- multi-scan Bundle roundtrip
- legacy single-scan Bundle compatibility
- exact-byte size formatting
- completion of the production core smoke run

Harness: `validation/SearchIndexSmoke.kt`.

### Mobile Export v3 desktop integration

- **PASS** — `python -m py_compile Dr_Indexer_18_14_8_mobile_export_v3.py`.
- **PASS** — synthetic v18.14-shaped source → Mobile Export v3 integration.
- **PASS** — exported SQLite `PRAGMA quick_check`.
- **PASS** — corrupt/hidden scan omitted.
- **PASS** — scan root identity retained.
- **PASS** — folder parent IDs retained.
- **PASS** — root-level file uses null folder correctly.
- **PASS** — exact integer `size_bytes` retained.
- **PASS** — direct-browse indexes present.

Validation script: `validation/validate_mobile_export_v3.py`.

### Desktop regression preservation

The Mobile Export v3 candidate was copied to an isolated test directory under
the authoritative module name `Dr_Indexer_18_14_8.py`, alongside the supplied
v18.14.8 regression/build metadata files.

- **PASS — 302/302 desktop regression tests**.

Log: `validation/desktop_regression_v3.log`.

This confirms the focused Mobile Export v3 addition did not break the supplied
v18.14.8 regression suite. It is **not** a claim that unrelated v18.15 desktop
work is complete.

## Benchmarks

These are SQLite/query-level synthetic benchmarks, not end-to-end Android UI
measurements.

### Folder navigation shape

Dataset: 300,000 files / 10,000 folders; repeated measured runs.

- v3.3-style `SELECT DISTINCT folder_id ...`: **51.765 ms median**
- v3 direct indexed child lookup: **0.116 ms median**
- query-level speedup: **~448.0×**

This strongly supports exporting the actual parent hierarchy rather than
re-deriving a scan's folders on every tap.

### Tolerant-search SQLite strategy

Dataset: 120,000 synthetic rows.

- ordinary ASCII token `LIKE` path: **7.2 ms median**
- deeply nested Unicode-tolerant `REPLACE()` SQL fallback: **970.8 ms median**
- FTS5 trigram: **0.046 ms median**

The v4 implementation therefore:

1. uses FTS5 trigram for low-memory tolerant substring search when available;
2. uses the inexpensive raw/LOWER token path for safe Latin/digit queries;
3. retains the heavier normalization fallback for Arabic/variant-sensitive
   compatibility cases and labels compatibility mode as potentially slower.

### Storage trade-off benchmark

Synthetic database:

- base: **8.23 MB**
- + normalized shadow column: **12.84 MB** (**+55.9%**)
- + normalized shadow + FTS: **24.30 MB**

Decision: do **not** inflate the transferred Mobile Export with a duplicate
normalized filename column. Normalize in application memory and build the
disposable FTS index locally on the phone.

Scripts:

- `validation/benchmark_browse_schema.py`
- `validation/benchmark_search_sqlite.py`
- `validation/benchmark_search_storage.py`

## Kotlin structural parse without Android SDK

The environment has a standalone Kotlin compiler but no Android/AndroidX SDK
classpath. Running `kotlinc` over all production `.kt` sources therefore
correctly emits unresolved Android/AndroidX symbols and cannot serve as an APK
compile.

After source-set cleanup and the final edits:

- **PASS** — no `expecting`, `unexpected tokens`, `unclosed comment`,
  `redeclaration` or `conflicting overloads` parser/source-set diagnostics.

Log: `validation/kotlinc_no_android_sdk.log`.

This is a syntax/structure gate only.

## Gradle / APK build limitation

A final `./gradlew testDebugUnitTest --offline --no-daemon` attempt reached the
wrapper, which then required the uncached official Gradle 8.6 distribution.
The execution environment cannot resolve `services.gradle.org`, and an Android
SDK is not installed.

Therefore:

- **NOT RUN** — Android Gradle `testDebugUnitTest`.
- **NOT RUN** — `assembleDebug`.
- **NOT RUN** — `assembleRelease`.
- **NOT RUN** — emulator/physical-device launch.

Log: `validation/gradle_attempt.log`.

The package contains supported build paths for a normal development machine:
`BUILD.bat`, `CLEAN_BUILD.bat`, standard `gradlew`, and GitHub Actions.

## Required device acceptance before calling v4 final

1. Build with JDK 17 + Android SDK 34 and run JVM/Android Gradle tests.
2. Import a good v2 export, then a deliberately truncated/bad export; confirm the
   known-good database survives.
3. Force-kill the app during the import swap and confirm `.previous` recovery.
4. Rotate the Database screen during Import and Rebuild; confirm state remains
   visible and the operation executes only once.
5. Import a v3 multi-root export and browse Scan → Root → deep folders.
6. Search identical Latin/Arabic queries in Fast and Disk modes and compare
   result identities/counts.
7. Test a 500k–1M+ entry database for Fast-index build heap/GC behavior.
8. Force/choose Disk mode and record whether the device reports FTS5 trigram or
   compatibility mode and measure representative query latency.
9. Exercise Android memory trim/background/resume behavior.
10. Verify Date Modified and Scan Name sorting against known rows.
11. Verify folder-only results expose folder-safe actions only.
12. Verify search-inside-folder on a deep v3 subtree.
