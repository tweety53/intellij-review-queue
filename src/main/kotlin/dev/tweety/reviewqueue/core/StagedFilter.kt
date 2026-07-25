package dev.tweety.reviewqueue.core

/**
 * Decides whether a git porcelain index status means "staged". Operates on the index (first)
 * column of `git status --porcelain`, which is what [git4idea.index.GitFileStatus.index] carries.
 */
object StagedFilter {
    private val STAGED_STATES = setOf('M', 'A', 'D', 'R', 'C', 'T')

    fun isStaged(indexStatus: Char): Boolean = indexStatus in STAGED_STATES
}
