package dev.tweety.reviewqueue.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StagedFilterTest {
    @Test
    fun `modified, added, deleted, renamed and copied index states are staged`() {
        listOf('M', 'A', 'D', 'R', 'C').forEach {
            assertTrue("index '$it' should count as staged", StagedFilter.isStaged(it))
        }
    }

    @Test
    fun `unmodified index is not staged`() {
        assertFalse(StagedFilter.isStaged(' '))
    }

    @Test
    fun `untracked and ignored are not staged`() {
        assertFalse(StagedFilter.isStaged('?'))
        assertFalse(StagedFilter.isStaged('!'))
    }

    @Test
    fun `unmerged index state is not staged`() {
        assertFalse(StagedFilter.isStaged('U'))
    }
}
