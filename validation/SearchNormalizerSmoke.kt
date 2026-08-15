import com.drindexer.search.SearchNormalizer

fun checkCase(name: String, ok: Boolean) {
    if (!ok) error("FAILED: $name")
    println("PASS: $name")
}

fun main() {
    checkCase("mixed separators", SearchNormalizer.matches("word1.word2-word3_ext.mkv", SearchNormalizer.parse("word1 word2 word3 ext")))
    checkCase("dot query to spaces", SearchNormalizer.matches("word1 word2 word3 ext", SearchNormalizer.parse("word1.word2.word3.ext")))
    checkCase("Arabic Alef variants", SearchNormalizer.matches("أرشيف_إسلامي_آثار.pdf", SearchNormalizer.parse("ارشيف اسلامي اثار")))
    checkCase("tashkeel/tatweel", SearchNormalizer.matches("مُحَاضَــــرَة.mp4", SearchNormalizer.parse("محاضرة")))
    checkCase("Persian yeh/kaf", SearchNormalizer.matches("کتاب_علی.pdf", SearchNormalizer.parse("كتاب علي")))
    checkCase("Arabic digits", SearchNormalizer.matches("course_٢٠٢٦_part_01.mp4", SearchNormalizer.parse("course 2026 01")))
    checkCase("yeh maqsura", SearchNormalizer.matches("على الطريق.pdf", SearchNormalizer.parse("علي الطريق")))
    checkCase("AND semantics reject missing", !SearchNormalizer.matches("word1 word2.txt", SearchNormalizer.parse("word1 word2 word3")))
    checkCase("quoted literal hits exact separators", SearchNormalizer.matches("my exact phrase.txt", SearchNormalizer.parse("\"exact phrase\"")))
    checkCase("quoted literal does not tolerate dots", !SearchNormalizer.matches("my.exact.phrase.txt", SearchNormalizer.parse("\"exact phrase\"")))
    checkCase("one-char ordinary query rejected", !SearchNormalizer.parse("a").isUsable)
    checkCase("two-char ordinary query accepted", SearchNormalizer.parse("ab").isUsable)
}
