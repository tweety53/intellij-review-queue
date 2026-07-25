package dev.tweety.reviewqueue.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BranchNameParserTest {
    @Test
    fun `extracts the change name from an openspec branch`() {
        assertEquals("add-widgets", BranchNameParser.changeName("openspec/add-widgets"))
    }

    @Test
    fun `keeps nested segments`() {
        assertEquals("add-widgets/fix-1", BranchNameParser.changeName("openspec/add-widgets/fix-1"))
    }

    @Test
    fun `returns null for other branches`() {
        assertNull(BranchNameParser.changeName("develop"))
        assertNull(BranchNameParser.changeName("feature/openspec-ish"))
    }

    @Test
    fun `returns null for null or bare prefix`() {
        assertNull(BranchNameParser.changeName(null))
        assertNull(BranchNameParser.changeName("openspec/"))
    }
}
