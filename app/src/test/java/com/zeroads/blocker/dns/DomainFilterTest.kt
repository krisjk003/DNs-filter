package com.zeroads.blocker.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DomainFilterTest {

    private lateinit var domainFilter: DomainFilter

    @Before
    fun setUp() {
        domainFilter = DomainFilter()
    }

    @Test
    fun testExactMatching() {
        domainFilter.loadEntries(listOf("doubleclick.net"))
        assertTrue(domainFilter.isBlocked("doubleclick.net"))
        assertFalse(domainFilter.isBlocked("example.com"))
    }

    @Test
    fun testSubdomainMatching() {
        domainFilter.loadEntries(listOf("doubleclick.net"))
        assertTrue(domainFilter.isBlocked("ads.doubleclick.net"))
        assertTrue(domainFilter.isBlocked("more.ads.doubleclick.net"))
        assertFalse(domainFilter.isBlocked("notdoubleclick.net")) // should not match
    }

    @Test
    fun testCaseInsensitiveMatching() {
        domainFilter.loadEntries(listOf("DoubleCLICK.net"))
        assertTrue(domainFilter.isBlocked("doubleclick.net"))
        assertTrue(domainFilter.isBlocked("DOUBLECLICK.NET"))
        assertTrue(domainFilter.isBlocked("ADS.doubleclick.NET"))
    }

    @Test
    fun testTrailingDotHandling() {
        domainFilter.loadEntries(listOf("doubleclick.net."))
        assertTrue(domainFilter.isBlocked("doubleclick.net"))
        assertTrue(domainFilter.isBlocked("doubleclick.net."))
        assertTrue(domainFilter.isBlocked("ads.doubleclick.net."))
    }

    @Test
    fun testNormalDomainAllowance() {
        domainFilter.loadEntries(listOf("googleadservices.com"))
        assertFalse(domainFilter.isBlocked("example.com"))
        assertFalse(domainFilter.isBlocked("google.com"))
    }

    @Test
    fun testYouTubeAllowance() {
        // We explicitly should NOT block youtube.com unless it's in the list
        domainFilter.loadEntries(listOf("googleadservices.com"))
        assertFalse(domainFilter.isBlocked("youtube.com"))
        assertFalse(domainFilter.isBlocked("www.youtube.com"))
    }

    @Test
    fun testEmptyEntriesAndDuplicateEntries() {
        // Duplicates should be counted once. Empty/blank lines should be ignored.
        domainFilter.loadEntries(listOf(
            "doubleclick.net",
            "doubleclick.net",
            "  ",
            "",
            "adnxs.com"
        ))
        assertEquals(2, domainFilter.getBlockedCount())
        assertTrue(domainFilter.isBlocked("doubleclick.net"))
        assertTrue(domainFilter.isBlocked("adnxs.com"))
    }
}