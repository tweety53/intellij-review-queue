package dev.tweety.reviewqueue.model

/** What the review queue is built from. */
sealed interface ReviewScope {
    /** The git index against HEAD — myflow Gate B. */
    data object Staged : ReviewScope

    /** The working branch against a base ref, using git's three-dot (merge-base) semantics. */
    data class BranchVsBase(val explicitBase: String? = null) : ReviewScope

    /** An explicit two-ref range. */
    data class CommitRange(val from: String, val to: String) : ReviewScope
}

fun ReviewScope.displayName(): String = when (this) {
    is ReviewScope.Staged -> "Staged"
    is ReviewScope.BranchVsBase -> "Branch vs base"
    is ReviewScope.CommitRange -> "Commit range"
}

/** Validates commit-range input at entry time rather than at resolution time. */
object CommitRangeValidator {
    private val FORBIDDEN = charArrayOf(';', '&', '|', '`', '$', '\n', '\r', ' ')

    fun validate(from: String, to: String): String? {
        if (from.isBlank()) return "Enter a starting ref"
        if (to.isBlank()) return "Enter an ending ref"
        if (from.any { it in FORBIDDEN } || to.any { it in FORBIDDEN }) {
            return "Refs must not contain whitespace or shell metacharacters"
        }
        return null
    }
}
