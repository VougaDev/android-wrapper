package com.vougadev.shell.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The refresh token on the disk: sealed, replaced at each rotation, dropped when it no longer opens. */
class TokenStoreTest {

    private val values = MapValues()
    private val store = TokenStore(values, FakeSealer())

    @Test
    fun theTokenIsWrittenSealedAndReadBackOpened() {
        store.save("rt-1")
        assertEquals("rt-1", store.refreshToken())
        assertTrue(store.signedIn)
        values.map.values.forEach { assertFalse("the plain token is on disk", it.contains("rt-1")) }
    }

    @Test
    fun eachRotationReplacesTheSpentToken() {
        store.save("rt-1")
        store.save("rt-2")
        assertEquals("rt-2", store.refreshToken())
        assertEquals(1, values.map.size)
    }

    @Test
    fun anAnswerWithoutARefreshTokenKeepsTheCurrentOne() {
        store.save("rt-1")
        store.save(null)
        store.save(" ")
        assertEquals("rt-1", store.refreshToken())
    }

    @Test
    fun aValueThatNoLongerOpensIsDroppedNotGuessed() {
        values.map["refresh_token"] = "sealed with a key this phone no longer has"
        assertNull(store.refreshToken())
        assertTrue(values.map.isEmpty())
        assertFalse(store.signedIn)
    }

    @Test
    fun clearForgetsIt() {
        store.save("rt-1")
        store.clear()
        assertNull(store.refreshToken())
        assertTrue(values.map.isEmpty())
    }
}
