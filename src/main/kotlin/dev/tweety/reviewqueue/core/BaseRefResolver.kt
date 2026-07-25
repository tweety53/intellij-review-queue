package dev.tweety.reviewqueue.core

/**
 * Chooses the base ref for a branch-vs-base review. The actual merge-base handling is left to
 * git's three-dot diff; this only decides which ref to compare against.
 */
object BaseRefResolver {
    fun resolve(explicitBase: String?, trackedBranch: String?, fallbackRef: String?): String? =
        explicitBase?.takeIf { it.isNotBlank() }
            ?: trackedBranch?.takeIf { it.isNotBlank() }
            ?: fallbackRef?.takeIf { it.isNotBlank() }
}
