from pathlib import Path
import re, xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'app/src/main/java/com/drindexer/search'

def text(name): return (SRC/name).read_text(encoding='utf-8')

def check(name, cond):
    if not cond: raise SystemExit(f'FAIL: {name}')
    print(f'PASS: {name}')

build=(ROOT/'app/build.gradle.kts').read_text()
manifest=ET.parse(ROOT/'app/src/main/AndroidManifest.xml').getroot()
ns='{http://schemas.android.com/apk/res/android}'
activities={a.attrib.get(ns+'name') for a in manifest.find('application').findall('activity')}

check('versionName 4.0', 'versionName = "4.0"' in build)
check('versionCode advanced', re.search(r'versionCode\s*=\s*(\d+)', build) and int(re.search(r'versionCode\s*=\s*(\d+)', build).group(1)) >= 12)
check('DatabaseActivity registered', '.DatabaseActivity' in activities)
check('DatabaseViewModel source present', (SRC/'DatabaseViewModel.kt').exists())
check('SearchNormalizer source present', (SRC/'SearchNormalizer.kt').exists())

main=text('MainActivity.kt')
flt=text('SearchFilter.kt')
idx=text('SearchIndex.kt')
db=text('DatabaseHelper.kt')
browse=text('FolderBrowserActivity.kt')
vm=text('DatabaseViewModel.kt')

check('zero selected scans means all', 'scanIds: Set<Int> = emptySet()' in flt)
check('default Files + Folders', 'includeFiles: Boolean = true' in flt and 'includeFolders: Boolean = true' in flt)
check('Folder quick filter wired', 'applyQuickCategory("folder")' in main)
check('one-shot scan scope consumed', 'removeExtra(EXTRA_SCOPE_SCAN_ID)' in main)
check('Relevance default sort', 'sortField: SortField = SortField.RELEVANCE' in flt)
check('Fast Date sort uses modified epoch', 'SortField.DATE -> Comparator { a, b -> modifiedEpoch[a].compareTo(modifiedEpoch[b]) }' in idx)
check('Fast Scan sort uses scan names', 'scanNameCache[scanIds[a]]' in idx and 'SortField.SCAN' in idx)
check('Fast results carry folder path', 'filePath = folderId?.let(folderDisplayPaths::get)' in idx)
check('two-pass index build', 'Pass 1: exact blob sizes only' in idx and 'ByteArray(originalTotal.toInt())' in idx)
check('post-pass exact heap guard', 'projectedFinalBytes' in idx and 'availableAfterPass1' in idx)
check('raw desktop Fast search excludes incomplete scans', 'COALESCE(corrupted,0)=0' in idx)
check('no per-filename ArrayList<ByteArray> allocation', not re.search(r'ArrayList\s*<\s*ByteArray\s*>\s*\(', idx))
check('safe temp import', 'copyImportToTemp(uri, temp)' in db and 'quickCheck(candidate)' in db)
check('interrupted import crash recovery', 'recoverInterruptedImportIfNeeded' in db and 'previous import never committed' in db)
check('previous DB restore path', 'previous.renameTo(target)' in db and 'known-good target immediately usable again' in db)
check('future schema rejected', 'candidateSchema > SUPPORTED_MOBILE_SCHEMA' in db)
check('schema v3 supported', 'SUPPORTED_MOBILE_SCHEMA = 3' in db)
check('direct hierarchy browse query', 'folders(scan_id,root_id,parent_id)' in db and 'getFolderChildrenDirect' in db)
check('stale browse generation protection', 'contentGeneration' in browse and 'generation != contentGeneration' in browse)
check('Database operations use ViewModel scope', 'viewModelScope.launch(Dispatchers.IO)' in vm)
check('old v3 FTS shape upgraded', 'hasNormalizedFtsSchema' in db and 'DROP TABLE IF EXISTS files_fts' in db)
check('trigram preferred', "tokenize='trigram'" in db)
check('compatibility mode disclosed', 'tolerant fallback may be slower' in db)
check('quoted SQL search is literal, not LIKE wildcard', 'INSTR(LOWER(f.filename), LOWER(?)) > 0' in db)
check('Latin fallback avoids full SQL normalization', 'asciiSafeTokens' in db and 'matchFilenameExpr' in db)
check('raw desktop searches exclude incomplete scans', 'hasCorruptedScanColumn' in db and 'COALESCE(corrupted,0)=0' in db)
check('desktop-style normalized shadow not stored in export app code', 'filename_norm' not in text('FileItem.kt'))

# AAPT/Gradle source-set hygiene: no archived duplicate source/resource files.
leaks=[p for p in (ROOT/'app/src/main').rglob('*') if p.is_file() and ('original' in p.name or p.name.endswith('.v3_3') or p.suffix=='.bak')]
check('no reference backups inside Android source set', not leaks)

# Every resource XML remains well-formed.
xmls=list((ROOT/'app/src/main').rglob('*.xml'))
for p in xmls: ET.parse(p)
check(f'all {len(xmls)} Android XML files parse', True)
