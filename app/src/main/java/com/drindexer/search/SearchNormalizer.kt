package com.drindexer.search

import java.text.Normalizer
import java.util.Locale

/**
 * Shared query normalization for Dr Indexer mobile v4.
 *
 * Ordinary (unquoted) search is recall-oriented:
 *  - common punctuation/separators become spaces
 *  - all tokens use AND semantics
 *  - Arabic Alef variants collapse to ا
 *  - tatweel + combining marks/tashkeel are ignored
 *  - Arabic/Persian Yeh and Kaf variants are unified
 *  - Arabic-Indic / Eastern Arabic-Indic digits map to ASCII
 *
 * A query wrapped in double quotes opts into literal substring semantics.
 * Exact mode intentionally does NOT apply the tolerant Arabic/punctuation
 * mappings; it only compares case-insensitively so users have an escape hatch.
 */
object SearchNormalizer {

    data class QuerySpec(
        val raw: String,
        val exact: Boolean,
        val exactText: String,
        val normalized: String,
        val tokens: List<String>
    ) {
        val isUsable: Boolean
            get() = if (exact) exactText.length >= 2 else normalized.replace(" ", "").length >= 2
    }

    private val alefVariants = setOf('أ', 'إ', 'آ', 'ٱ')
    private val yehVariants = setOf('ى', 'ی', 'ۍ', 'ێ')

    fun parse(rawQuery: String): QuerySpec {
        val trimmed = rawQuery.trim()
        val exact = trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"'
        val exactText = if (exact) trimmed.substring(1, trimmed.length - 1) else ""
        val normalized = if (exact) "" else normalize(trimmed)
        val tokens = if (normalized.isEmpty()) emptyList() else normalized.split(' ')
        return QuerySpec(trimmed, exact, exactText, normalized, tokens)
    }

    fun normalize(text: String): String {
        if (text.isBlank()) return ""
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        var pendingSpace = false

        for (raw in decomposed) {
            val type = Character.getType(raw)
            if (type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt() ||
                raw == '\u0640' // tatweel
            ) {
                continue
            }

            val ch = when {
                raw in alefVariants -> 'ا'
                raw in yehVariants -> 'ي'
                raw == 'ک' -> 'ك'
                raw in '٠'..'٩' -> ('0'.code + (raw.code - '٠'.code)).toChar()
                raw in '۰'..'۹' -> ('0'.code + (raw.code - '۰'.code)).toChar()
                else -> raw.lowercaseChar()
            }

            if (ch.isLetterOrDigit()) {
                if (pendingSpace && out.isNotEmpty()) out.append(' ')
                out.append(ch)
                pendingSpace = false
            } else {
                // All punctuation/separators are word boundaries for tolerant search.
                pendingSpace = out.isNotEmpty()
            }
        }
        return out.toString().trim()
    }

    fun matches(value: String?, spec: QuerySpec): Boolean {
        if (value.isNullOrEmpty() || !spec.isUsable) return false
        if (spec.exact) return value.contains(spec.exactText, ignoreCase = true)
        val normalizedValue = normalize(value)
        return spec.tokens.all { normalizedValue.contains(it) }
    }

    /**
     * Relevance bucket: smaller is better. Used when SortField.RELEVANCE is active.
     */
    fun relevance(value: String?, spec: QuerySpec): Int {
        if (value.isNullOrEmpty()) return 1000
        if (spec.exact) {
            if (value.equals(spec.exactText, ignoreCase = true)) return 0
            if (value.startsWith(spec.exactText, ignoreCase = true)) return 1
            return if (value.contains(spec.exactText, ignoreCase = true)) 2 else 1000
        }
        val normalizedValue = normalize(value)
        if (normalizedValue == spec.normalized) return 0
        if (normalizedValue.startsWith(spec.normalized)) return 1
        if (normalizedValue.contains(spec.normalized)) return 2
        if (spec.tokens.all { normalizedValue.contains(it) }) return 3
        return 1000
    }

    /** Normalize only for stable case-insensitive sort labels. */
    fun sortKey(text: String?): String = text?.lowercase(Locale.ROOT) ?: ""
}
