package com.openqr.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserLauncherTest {
    @Test
    fun `accepts https urls`() {
        val value = BrowserLauncher.sanitize("https://example.com/path?q=1")

        requireNotNull(value)
        assertEquals("https://example.com/path?q=1", value)
    }

    @Test
    fun `rejects unsupported schemes`() {
        assertNull(BrowserLauncher.sanitize("javascript:alert(1)"))
        assertNull(BrowserLauncher.sanitize("intent://example.com"))
        assertNull(BrowserLauncher.sanitize("file:///tmp/test"))
    }

    @Test
    fun `rejects urls without hosts`() {
        assertNull(BrowserLauncher.sanitize("https:///missing-host"))
        assertNull(BrowserLauncher.sanitize("https://user@example.com"))
        assertNull(BrowserLauncher.sanitize("not-a-url"))
    }
}
