# Dr Indexer Search Android v4.0 — implementation report

Date: 2026-08-15

## Scope completed

This implementation starts from the supplied DrIndexerSearch v3.3 Android
project and the supplied Dr. Indexer v18.14.8 desktop Mobile Export baseline.
It implements the approved product/architecture direction without changing the
main Dr. Indexer desktop archive schema.

## 1. Database import safety

The Android import path is now staged and rollback-safe:

1. copy/decompress into a private temporary file;
2. keep a storage reserve while streaming;
3. reject unsupported future mobile schemas;
4. validate required SQLite objects;
5. run `PRAGMA quick_check` while the current DB remains untouched;
6. move the known-good current DB to `.previous`;
7. publish the validated candidate;
8. reopen/build caches;
9. remove `.previous` only at the commit point.

A `.previous` file left by process death is treated as an uncommitted import and
restored automatically on the next open. Explicit Remove Local Copy cleans live,
temporary and rollback files.

## 2. Mobile Export v3

The desktop integration adds a focused Mobile Export schema v3:

- scans
- scan_roots with archived volume identity
- folders with scan/root/parent hierarchy
- entries_data with exact integer size_bytes
- a compatibility files VIEW
- mobile/source/version metadata
- lean scan/folder/parent indexes

Only valid/non-corrupt desktop scans are exported. The desktop application's main
SQLite schema remains unchanged.

Android keeps backward readers for older mobile exports and raw compatible
desktop databases.

## 3. Unified search semantics

Ordinary search is now tolerant AND-token search. Separator differences between
space, dot, underscore, dash and common punctuation do not cause false misses.
Safe Arabic normalization covers Alef variants, tashkeel/tatweel, Yeh/Kaf glyph
variants and Arabic/Persian/ASCII digit equivalence.

A fully quoted query is literal substring intent. The Disk SQL path uses literal
`INSTR` for quoted filename/path matching, so SQL `%` and `_` cannot silently act
as wildcards.

Filename and archived-path hits are unioned into one deduplicated result set in
Fast and Disk modes. Relevance is the default sort.

## 4. Search scope and result types

- zero selected scans = All valid scans;
- one or more selected scans = exactly those scans;
- scan scope is visible and multi-select;
- a Folder result type is first-class;
- default ordinary search returns Files + Folders;
- raw desktop compatibility filters hidden/incomplete scans from All-scan search.

The old one-shot Browse→Search scan scope extra is consumed after use, so rotation
cannot reapply a scope the user already cleared.

## 5. Fast search engine hardening

- two-pass final blob construction removes the per-filename ByteArray object fanout;
- pass 1 measures actual original and normalized UTF-8 sizes;
- a second exact-size heap guard runs before final allocation;
- compact root/folder/date/exact-byte metadata stays with the index;
- search results therefore display archived location without an extra details query;
- Date Modified sorts by real epoch, not ID;
- Scan sorts by real scan name, not numeric scan ID.

Automatic mode is based on the Android application heap (`Runtime.maxMemory()` +
`memoryClass`) rather than total physical device RAM.

## 6. Disk search engine

The private mobile copy prefers FTS5 trigram for tolerant substring search.
Derived v3.x raw-filename FTS is detected and rebuilt to the normalized v4 shape.

If trigram is unavailable:

- safe Latin/digit token queries use inexpensive raw/LOWER token LIKE matching;
- Arabic/variant-sensitive cases retain the correctness-first normalized SQL path;
- the UI explicitly labels this as Disk compatibility mode rather than implying
  identical performance.

No normalized duplicate filename column is transferred in Mobile Export v3; a
synthetic benchmark showed that strategy increased the base DB by ~55.9% even
before FTS.

## 7. Browse model

The primary navigation is Search / Browse / Database.

For Mobile Export v3 Browse is:

`Scan → Root/Drive → Folder`

Folder children use an indexed `(scan_id, root_id, parent_id)` lookup instead of
rebuilding a scan's folder set from file rows at every tap. Recursive Search
inside this location is available. Generation tokens reject stale folder/search
workers after navigation changes.

Legacy v1/v2 browse remains as a compatibility path.

## 8. Database screen and lifecycle

Database now centralizes:

- current DB/schema/export/source information;
- Automatic/Fast/Disk engine selection;
- Verify;
- Rebuild search engine;
- safe Import/Replace;
- Remove Local Copy.

Import/Verify/Rebuild are owned by `DatabaseViewModel` on an IO scope so Activity
rotation/recreation does not own or duplicate the operation.

## 9. Validation summary

Passed in the available environment:

- Android source invariant audit;
- all 36 Android XML resources parse;
- SearchNormalizer JVM smoke: 12/12;
- production Fast SearchIndex JVM smoke: 17 functional checks;
- Mobile Export v3 SQLite hierarchy/root/exact-byte/integrity integration;
- focused desktop candidate Python syntax;
- supplied desktop regression suite: 302/302;
- Kotlin source structural parse: no syntax/redeclaration diagnostics;
- browse/search/storage synthetic benchmarks.

Representative query-level benchmark results:

- old per-tap folder derivation: 51.765 ms median;
- v3 indexed child lookup: 0.116 ms median (~448× faster at SQL level);
- ordinary ASCII token LIKE: 7.2 ms median on 120k rows;
- full normalized SQL fallback: 970.8 ms median;
- FTS5 trigram: 0.046 ms median.

## 10. Validation limitation

The environment does not contain Android SDK 34 or a cached Gradle 8.6 wrapper
distribution and cannot access `services.gradle.org`. Therefore no APK build or
physical-device execution is claimed here.

Before calling v4 a final release, use the included build scripts/GitHub Actions
and complete the device acceptance list in `VALIDATION_v4.md`.
