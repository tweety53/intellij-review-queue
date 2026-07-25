package dev.tweety.reviewqueue.model

/** Identity of a file under review: its git root and the path relative to that root. */
data class ReviewKey(val rootPath: String, val relPath: String) {
    /** Key used in persisted state. Stable across sessions. */
    fun storageKey(): String = "$rootPath|$relPath"
}
