package dev.tweety.reviewqueue.ui

import dev.tweety.reviewqueue.model.ReviewScope
import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewProgressBannerTest {

    @Test
    fun `reports the reviewed count against the scope total`() {
        assertEquals(
            "5 / 12 files reviewed  •  Staged",
            ReviewProgressBanner.text(5, 12, ReviewScope.Staged),
        )
    }

    @Test
    fun `reports zero reviewed without special-casing it`() {
        assertEquals(
            "0 / 10 files reviewed  •  Staged",
            ReviewProgressBanner.text(0, 10, ReviewScope.Staged),
        )
    }

    /** An empty scope must not read `0 / 0` as an error or divide by zero in the bar fraction. */
    @Test
    fun `an empty scope reads as nothing to review`() {
        assertEquals(
            "0 / 0 files reviewed  •  Staged",
            ReviewProgressBanner.text(0, 0, ReviewScope.Staged),
        )
        assertEquals(0.0, ReviewProgressBanner.fraction(0, 0), 0.0)
    }

    @Test
    fun `a fully reviewed scope is a full bar`() {
        assertEquals(1.0, ReviewProgressBanner.fraction(12, 12), 0.0)
    }
}
