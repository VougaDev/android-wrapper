package com.vougadev.shell.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixPolicyTest {

    private val second = 1_000_000_000L

    @Test
    fun `a fix is fresh for two minutes`() {
        val now = 1_000 * second
        assertTrue(FixPolicy.isFresh(now, now))
        assertTrue(FixPolicy.isFresh(now - 120 * second, now))
        assertFalse(FixPolicy.isFresh(now - 121 * second, now))
        // A fix stamped in the future is a clock problem, not a position.
        assertFalse(FixPolicy.isFresh(now + second, now))
    }

    @Test
    fun `a newer fix replaces an older one`() {
        assertTrue(FixPolicy.isBetter(10 * second, 20f, null, null))
        assertTrue(FixPolicy.isBetter(50 * second, 500f, 10 * second, 5f))
        assertFalse(FixPolicy.isBetter(10 * second, 5f, 10 * second, 50f))
        assertFalse(FixPolicy.isBetter(9 * second, 5f, 10 * second, 50f))
    }

    @Test
    fun `a vaguer fix seconds later does not replace a precise one`() {
        assertFalse(FixPolicy.isBetter(15 * second, 1200f, 10 * second, 8f))
        assertTrue(FixPolicy.isBetter(15 * second, 6f, 10 * second, 8f))
        assertFalse(FixPolicy.isBetter(15 * second, null, 10 * second, 8f))
    }
}
