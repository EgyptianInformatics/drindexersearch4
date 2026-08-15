package android.os

class Bundle {
    private val data = HashMap<String, Any?>()
    fun putBoolean(k: String, v: Boolean) { data[k] = v }
    fun putString(k: String, v: String?) { data[k] = v }
    fun putFloat(k: String, v: Float) { data[k] = v }
    fun putInt(k: String, v: Int) { data[k] = v }
    fun putIntArray(k: String, v: IntArray?) { data[k] = v }
    fun getBoolean(k: String, d: Boolean = false): Boolean = data[k] as? Boolean ?: d
    fun getString(k: String, d: String? = null): String? = data[k] as? String ?: d
    fun getFloat(k: String, d: Float = 0f): Float = data[k] as? Float ?: d
    fun getInt(k: String, d: Int = 0): Int = data[k] as? Int ?: d
    fun getIntArray(k: String): IntArray? = data[k] as? IntArray
}
