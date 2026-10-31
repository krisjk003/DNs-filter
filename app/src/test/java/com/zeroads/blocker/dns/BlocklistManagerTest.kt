package com.zeroads.blocker.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class BlocklistManagerTest {

    @Test
    fun testLoadFromStreamWithDuplicatesAndComments() {
        val domainFilter = DomainFilter()

        val fileContent = """
            # Advertising
            doubleclick.net
            googlesyndication.com
            
            # Empty lines
            
            
            # Duplicates
            doubleclick.net
            adnxs.com
        """.trimIndent()

        val inputStream = ByteArrayInputStream(fileContent.toByteArray())
        val result = BlocklistManager.loadFromStream(inputStream, domainFilter)

        assertTrue(result.isSuccess)
        assertEquals(3, domainFilter.getBlockedCount())
        assertTrue(domainFilter.isBlocked("doubleclick.net"))
        assertTrue(domainFilter.isBlocked("googlesyndication.com"))
        assertTrue(domainFilter.isBlocked("adnxs.com"))
        assertFalse(domainFilter.isBlocked("example.com"))
    }

    @Test
    fun testEmptyAssetReturnsFailure() {
        val domainFilter = DomainFilter()
        val emptyContent = """
            # Only comments
            # And blank lines
            
        """.trimIndent()

        val inputStream = ByteArrayInputStream(emptyContent.toByteArray())
        val result = BlocklistManager.loadFromStream(inputStream, domainFilter)

        assertTrue(result.isFailure)
        assertEquals(0, domainFilter.getBlockedCount())
    }

    @Test
    fun testInvalidDomainEntries() {
        val domainFilter = DomainFilter()
        val contentWithInvalidDomains = """
            # Valid domain
            doubleclick.net
            
            # Invalid domains with spaces, slashes, or colons
            http://invalid.com
            invalid domain.com
            invalid:8080
            .invalid
        """.trimIndent()

        val inputStream = ByteArrayInputStream(contentWithInvalidDomains.toByteArray())
        val result = BlocklistManager.loadFromStream(inputStream, domainFilter)

        assertTrue(result.isSuccess)
        assertEquals(1, domainFilter.getBlockedCount())
        assertTrue(domainFilter.isBlocked("doubleclick.net"))
    }
}