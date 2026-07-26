package dev.tweety.reviewqueue.core

import dev.tweety.reviewqueue.model.CommitRangeValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseRefResolverTest {
    @Test
    fun `explicit base wins`() {
        assertEquals("develop", BaseRefResolver.resolve("develop", "origin/main", "origin/HEAD"))
    }

    @Test
    fun `tracked branch is used when no explicit base`() {
        assertEquals("origin/main", BaseRefResolver.resolve(null, "origin/main", "origin/HEAD"))
    }

    @Test
    fun `fallback is used when nothing else is known`() {
        assertEquals("origin/HEAD", BaseRefResolver.resolve(null, null, "origin/HEAD"))
    }

    @Test
    fun `blank explicit base is ignored`() {
        assertEquals("origin/main", BaseRefResolver.resolve("   ", "origin/main", "origin/HEAD"))
    }

    @Test
    fun `null when nothing resolves`() {
        assertNull(BaseRefResolver.resolve(null, null, null))
    }

    @Test
    fun `commit range validator rejects blanks`() {
        assertNotNull(CommitRangeValidator.validate("", "HEAD"))
        assertNotNull(CommitRangeValidator.validate("HEAD", "  "))
    }

    @Test
    fun `commit range validator rejects shell metacharacters`() {
        assertNotNull(CommitRangeValidator.validate("HEAD; rm -rf /", "HEAD"))
    }

    @Test
    fun `commit range validator accepts ordinary refs`() {
        assertNull(CommitRangeValidator.validate("origin/develop", "HEAD"))
        assertNull(CommitRangeValidator.validate("a1b2c3d", "HEAD~3"))
    }

    /**
     * The leading-dash rule guards a **repository write**, so it is pinned separately from the
     * metacharacter rule and with the mechanism spelled out.
     *
     * git4idea builds `git rev-list --timestamp --max-count=1 <ref>` with no `--` separator, so a ref
     * beginning with `-` is parsed as an option. git's parse-options opens `--output=<file>` for writing
     * before it rejects the missing commit argument, so this truncates the named file to zero bytes and
     * then exits 129 — reproduced against real git, not inferred. `--output=.git/index` therefore zeroes
     * the index of a plugin whose one invariant is that every git command is a query.
     *
     * Note none of these contain a character in FORBIDDEN, so the metacharacter rule does not catch
     * them: before this rule existed, every one of them reached git.
     */
    @Test
    fun `validator rejects refs that git would read as options`() {
        assertNotNull(CommitRangeValidator.validate("--output=.git/index", "HEAD"))
        assertNotNull(CommitRangeValidator.validate("HEAD", "--output=/tmp/victim"))
        assertNotNull(CommitRangeValidator.validateRef("--output=.git/index", "The base ref"))
        assertNotNull(CommitRangeValidator.validateRef("-n", "The base ref"))
        assertNotNull(CommitRangeValidator.validateRef("--all", "The base ref"))
    }

    @Test
    fun `validator names the option hazard rather than blaming metacharacters`() {
        val message = CommitRangeValidator.validateRef("--output=x", "The base ref")
        assertNotNull(message)
        assertTrue(
            "the message must explain why a dash is refused, not mention metacharacters: got \"$message\"",
            message!!.contains("option"),
        )
    }

    /** A ref may still contain a dash anywhere but the first character — `my-branch` is ordinary. */
    @Test
    fun `validator allows dashes inside a ref`() {
        assertNull(CommitRangeValidator.validateRef("feature/my-branch", "The base ref"))
        assertNull(CommitRangeValidator.validateRef("release-1.2", "The base ref"))
    }

    @Test
    fun `validator rejects tabs as well as spaces`() {
        assertNotNull(CommitRangeValidator.validateRef("HEAD\tHEAD", "The base ref"))
    }
}
