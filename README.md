# Dr Indexer Search (Android) — v4.0 implementation candidate

Android companion for **Dr. Indexer**. It imports a mobile export (`.db.gz` or
`.db`) and lets the phone search and browse archived external-drive contents
while the drives themselves are offline.

**Baseline:** DrIndexerSearch v3.3.  
**Android:** API 24+ (Android 7+), compile/target SDK 34, JDK 17.  
**Mobile export:** reads legacy v1/v2 plus the new v3 hierarchy; raw compatible
Dr. Indexer desktop databases retain a legacy fallback reader.

## v4 product model

The app now has three primary destinations:

- **Search** — the home workflow. No selected scan means **all scans**. Selecting
  one or more scans narrows search to exactly those scans.
- **Browse** — Scan → Root/Drive → Folder. Mobile Export v3 supplies real roots
  and parent/child folder identities instead of forcing the phone to infer them.
- **Database** — safe Import/Replace, integrity check, automatic/Fast/Disk search
  mode, index rebuild and local-copy removal.

## Safer import

v3.3 replaced the live private database before fully proving the new file was
usable. v4 uses a staged transaction-like flow:

1. Copy or stream-decompress into a sibling temporary file.
2. Check free-storage reserve while copying.
3. Reject unsupported future mobile schemas.
4. Validate the required SQLite shape and run `PRAGMA quick_check`.
5. Preserve the current known-good database.
6. Publish the validated candidate.
7. Reopen it and build caches; if this post-publication open fails, restore the
   previous database.
8. If Android kills the process mid-swap, the retained `.previous` file is
   restored automatically on the next open; its presence means the import did
   not reach the commit point.

Import/Verify/Rebuild work is controlled by `DatabaseViewModel`, so a screen
rotation/recreation does not tie the operation to an Activity instance.

## Unified tolerant search

Ordinary unquoted search is token-based with AND semantics. Common filename
separators are equivalent, so for example:

- `word1 word2 word3 ext`
- `word1.word2.word3.ext`
- `word1-word2_word3.ext`

can match each other.

Safe Arabic search normalization includes:

- Alef variants: `ا أ إ آ ٱ`
- common tashkeel and tatweel removal
- `ي / ى / ی`
- `ك / ک`
- ASCII, Arabic-Indic and Eastern Arabic/Persian digit equivalence
- Arabic/Latin punctuation as token boundaries

A query wrapped entirely in double quotes keeps literal substring intent and
does **not** receive tolerant separator normalization.

Search combines filename and archived-path matches with one deduplicated result
count in both Fast and Disk modes. **Relevance** is the default sort. The prior
Fast-mode Date and Scan-name sort bugs are corrected.

## Search engines

### Automatic — recommended

Uses `Runtime.maxMemory()` plus Android `memoryClass`, with conservative reserve
for UI/SQLite/GC, instead of assuming the app can use a fraction of total device
RAM.

### Fast RAM index

The index is now built in two passes directly into final byte blobs instead of
holding one temporary `ByteArray` object per filename. After pass 1 measures the
actual original + normalized UTF-8 payload, a second heap-budget guard runs
before allocating the final arrays. The index also retains compact
folder/root/date/exact-size metadata so search results can show useful archived
locations immediately.

### Low-memory Disk/FTS

The app prefers a normalized **FTS5 trigram** index built only in its private
mobile copy. If the device SQLite lacks trigram tokenization, the UI explicitly
reports **Disk compatibility mode**; tolerant substring correctness is retained,
but that fallback can be slower.

A v3.x local FTS table is recognized as derived data and automatically rebuilt
into the v4 normalized shape when needed.

## Mobile Export v3

The desktop integration candidate changes only the **mobile export payload**;
it does not change Dr. Indexer's main desktop database schema.

v3 exports:

- `scans`
- `scan_roots` with archived root/volume identity
- `folders(id, scan_id, root_id, parent_id, name, path)`
- `entries_data` with exact `size_bytes INTEGER`
- compatibility `files` view
- `meta` with mobile schema, minimum app version, source desktop version and
  export timestamp

This allows direct indexed child lookup by `(scan_id, root_id, parent_id)` and
correct multi-root browsing. Android still contains v1/v2 fallback paths.

The desktop file in this package is intentionally a **focused v18.14.8 Mobile
Export v3 integration candidate**, not a claim that the unrelated Dr. Indexer
v18.15 desktop work is complete. See `DESKTOP_MOBILE_EXPORT_V3_INTEGRATION.md`.

## Build

Requirements:

- JDK 17
- Android SDK Platform 34
- Gradle wrapper distribution 8.6 (the wrapper downloads it from the official
  Gradle distribution URL when network access is available)

Windows:

```bat
BUILD.bat
```

or:

```bat
CLEAN_BUILD.bat
```

Both scripts run `testDebugUnitTest` before producing Debug and unsigned Release
APKs. They no longer download `gradle-wrapper.jar` from third-party mirrors.

Cross-platform:

```bash
./gradlew testDebugUnitTest assembleDebug assembleRelease
```

GitHub Actions also runs JVM tests before building the APK.

## Validation status

The implementation package contains deterministic validation scripts and the
full record in `VALIDATION_v4.md`.

Validated in the available environment:

- all Android XML resources parse
- pure JVM search-normalizer smoke suite passes (12/12)
- the production Fast `SearchIndex` core passes a 17-check JVM smoke harness
- Mobile Export v3 synthetic SQLite hierarchy/root/exact-byte/integrity test passes
- the focused desktop candidate passes the supplied **302/302** v18.14.8 regressions
- query-level folder/search/storage benchmarks completed
- Android source invariant audit and all 36 XML resources pass
- Kotlin source has no parser/syntax diagnostics when parsed without the absent Android SDK classpath

**Not validated here:** a real Android Gradle build/APK launch, because this
execution environment has neither a cached Gradle 8.6 distribution nor Android
SDK/network access to obtain the required toolchain. This package therefore does
not claim device acceptance. Run `BUILD.bat`, Android Studio, or the included
GitHub Actions workflow before installing on a phone.
