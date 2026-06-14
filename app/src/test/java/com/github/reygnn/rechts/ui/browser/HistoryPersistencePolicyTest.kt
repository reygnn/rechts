package com.github.reygnn.rechts.ui.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPersistencePolicyTest {

    private val max = 512 * 1024

    @Test
    fun `normal mode within budget is persisted`() {
        assertTrue(HistoryPersistencePolicy.shouldPersist(isPrivate = false, marshalledSize = 1_000, maxBytes = max))
    }

    @Test
    fun `private session is never persisted, regardless of size`() {
        assertFalse(HistoryPersistencePolicy.shouldPersist(isPrivate = true, marshalledSize = 0, maxBytes = max))
        assertFalse(HistoryPersistencePolicy.shouldPersist(isPrivate = true, marshalledSize = 1_000, maxBytes = max))
    }

    @Test
    fun `oversize history is dropped in normal mode`() {
        assertFalse(HistoryPersistencePolicy.shouldPersist(isPrivate = false, marshalledSize = max + 1, maxBytes = max))
    }

    @Test
    fun `size exactly at the cap is still persisted`() {
        // The cap is inclusive — only what exceeds it is dropped.
        assertTrue(HistoryPersistencePolicy.shouldPersist(isPrivate = false, marshalledSize = max, maxBytes = max))
    }
}
