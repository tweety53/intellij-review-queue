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

/**
 * Validates every user-supplied ref before it can reach a git argument list.
 *
 * **The leading-dash rule is the load-bearing one, and it guards a repository write.** git4idea builds
 * `git rev-list --timestamp --max-count=1 <ref>` with the ref as a positional argument and **no `--`
 * separator**, so a ref beginning with `-` is parsed as an option. git's parse-options opens
 * `--output=<file>` for writing *before* it rejects the missing commit argument, so a base ref of
 * `--output=.git/index` truncates the index to zero bytes and then exits 129 — which this plugin sees
 * only as a `VcsException` and reports as a failed root. Verified by running it against real git in a
 * scratch repository, not inferred from the source.
 *
 * That is a repository mutation from a plugin whose single invariant is that every git command is a
 * query, so it is rejected here rather than assumed unreachable.
 *
 * [FORBIDDEN] is defence in depth only: nothing on this path goes through a shell (git4idea uses
 * `GeneralCommandLine`), so `$(…)`, backticks, `;` and `|` are inert as characters. They stay rejected
 * because a ref containing them is a mistake either way.
 */
object CommitRangeValidator {
    private val FORBIDDEN = charArrayOf(';', '&', '|', '`', '$', '\n', '\r', ' ', '\t')

    /**
     * Rejects one ref, or returns null when it is acceptable. Shared by every scope that takes a ref —
     * `BranchVsBase` had no validation at all before this, which made it the easier of the two paths to
     * the write above.
     */
    fun validateRef(ref: String, label: String): String? {
        if (ref.isBlank()) return "Enter $label"
        // Checked before FORBIDDEN so the message names the real reason rather than "metacharacters".
        if (ref.startsWith("-")) {
            return "$label must not start with \"-\": git would read it as an option, not a ref"
        }
        if (ref.any { it in FORBIDDEN }) {
            return "$label must not contain whitespace or shell metacharacters"
        }
        return null
    }

    fun validate(from: String, to: String): String? =
        validateRef(from, "a starting ref") ?: validateRef(to, "an ending ref")
}
