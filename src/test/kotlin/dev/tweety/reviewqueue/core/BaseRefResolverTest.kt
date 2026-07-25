package dev.tweety.reviewqueue.core

import dev.tweety.reviewqueue.model.CommitRangeValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
