package com.zeroads.blocker.dns

import java.util.Locale

/**
 * Filters domains against a set of blocked domains.
 * Supports exact matching and subdomain matching.
 */
class DomainFilter {
    private val blockedDomains = HashSet<String>()

    /**
     * Replaces the current blocklist with the provided entries.
     */
    fun loadEntries(entries: Collection<String>) {
        val normalized = entries.mapNotNull { entry ->
            val normalized = normalize(entry)
            if (normalized.isNotEmpty()) normalized else null
        }

        synchronized(blockedDomains) {
            blockedDomains.clear()
            blockedDomains.addAll(normalized)
        }
    }

    /**
     * Checks if a domain or its parent domains are blocked.
     */
    fun isBlocked(domain: String): Boolean {
        if (domain.isBlank()) return false
        val normalizedDomain = normalize(domain)

        synchronized(blockedDomains) {
            if (blockedDomains.contains(normalizedDomain)) {
                return true
            }

            var index = normalizedDomain.indexOf('.')
            while (index != -1 && index < normalizedDomain.length - 1) {
                val parentDomain = normalizedDomain.substring(index + 1)
                if (blockedDomains.contains(parentDomain)) {
                    return true
                }
                index = normalizedDomain.indexOf('.', index + 1)
            }
        }

        return false
    }

    /**
     * Returns the number of blocked domains.
     */
    fun getBlockedCount(): Int {
        synchronized(blockedDomains) {
            return blockedDomains.size
        }
    }

    private fun normalize(domain: String): String {
        return domain.trim().removeSuffix(".").lowercase(Locale.ROOT)
    }
}