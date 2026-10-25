package com.pulsedrop.app.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InFlightUploadBudgetTest {

    @Test
    fun `admitted lengths add up and only a release makes room`() {
        val budget = InFlightUploadBudget()
        assertTrue(budget.reserve(20, 32))
        assertTrue(budget.reserve(12, 32))
        assertFalse("the sum, not each body's size, is what must fit", budget.reserve(1, 32))
        assertEquals(32L, budget.reservedBytes())

        budget.release(12)
        assertTrue("a released reservation is room again", budget.reserve(1, 32))
        assertEquals(21L, budget.reservedBytes())
        budget.release(21)
        assertEquals(0L, budget.reservedBytes())
    }

    @Test
    fun `an unmeasurable or zero declaration cannot go negative`() {
        val budget = InFlightUploadBudget()
        assertTrue("a request with no declared body spends nothing", budget.reserve(0, 0))
        assertFalse("a budget smaller than nothing is not a reservation", budget.reserve(1, 0))
        budget.release(-5)
        assertEquals("release must not create space that was never reserved", 0L, budget.reservedBytes())
    }
}
