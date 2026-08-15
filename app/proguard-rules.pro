# Keep data classes used with Bundle serialization
-keep class com.drindexer.search.FileItem { *; }
-keep class com.drindexer.search.SearchFilter { *; }
-keep class com.drindexer.search.SearchResult { *; }
-keep class com.drindexer.search.ScanSummary { *; }
-keep class com.drindexer.search.SortField { *; }
-keep class com.drindexer.search.SortOrder { *; }
-keep class com.drindexer.search.FileCategory { *; }
-keep class com.drindexer.search.DatabaseHelper$BrowseEntry { *; }
-keep class com.drindexer.search.DatabaseHelper$ImportPreparation { *; }

# Keep enum values
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
