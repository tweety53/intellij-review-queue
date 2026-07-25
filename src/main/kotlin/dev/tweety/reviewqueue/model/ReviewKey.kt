package dev.tweety.reviewqueue.model

/** Identity of a file under review: its git root and the path relative to that root. */
data class ReviewKey(val rootPath: String, val relPath: String) {
    /** Key used in persisted state. Stable across sessions. */
    fun storageKey(): String = "$rootPath|$relPath"

    companion object {
        fun fromStorageKey(key: String): ReviewKey? {
            val sep = key.indexOf('|')
            if (sep <= 0 || sep == key.length - 1) return null
            return ReviewKey(key.substring(0, sep), key.substring(sep + 1))
        }
    }
}
