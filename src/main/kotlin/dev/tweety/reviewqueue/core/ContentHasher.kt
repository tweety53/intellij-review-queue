package dev.tweety.reviewqueue.core

import java.security.MessageDigest
import java.util.UUID

/**
 * Hashes the content a reviewed mark is stored against, so a file whose content later changes no
 * longer matches its stored mark and returns to the queue.
 *
 * Raw bytes are hashed, never a decoded string: decoding normalises line separators, which would
 * let a CRLF/LF-only rewrite silently keep its mark.
 */
object ContentHasher {

    fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Marker used when a revision's bytes cannot be read.
     *
     * Deliberately unique per call rather than a stable constant. A constant (the old behaviour —
     * a revision number, which for the staged scope is the literal string "HEAD") compares equal to
     * whatever was stored when the file was marked, so an unreadable file would read "reviewed"
     * forever. A unique value makes it read unreviewed, which is the safe default.
     */
    fun unresolved(): String = "unresolved:${UUID.randomUUID()}"
}
