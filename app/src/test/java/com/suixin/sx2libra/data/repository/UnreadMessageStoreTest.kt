package com.suixin.sx2libra.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UnreadMessageStoreTest {
    @Before
    fun resetStore() {
        UnreadMessageStore.clear()
    }

    @Test
    fun markingMessagesReadSuppressesTheSameCountUntilItIncreases() {
        UnreadMessageStore.update(1)
        assertTrue(UnreadMessageStore.state.value.hasUnacknowledgedMessages)

        UnreadMessageStore.markRead()
        assertFalse(UnreadMessageStore.state.value.hasUnacknowledgedMessages)

        UnreadMessageStore.update(1)
        assertFalse(UnreadMessageStore.state.value.hasUnacknowledgedMessages)

        UnreadMessageStore.update(2)
        assertTrue(UnreadMessageStore.state.value.hasUnacknowledgedMessages)
    }

    @Test
    fun zeroCountResetsTheAcknowledgedState() {
        UnreadMessageStore.update(2)
        UnreadMessageStore.markRead()
        UnreadMessageStore.update(0)
        UnreadMessageStore.update(1)

        assertTrue(UnreadMessageStore.state.value.hasUnacknowledgedMessages)
    }
}
