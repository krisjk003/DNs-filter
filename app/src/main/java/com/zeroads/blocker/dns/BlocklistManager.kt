package com.zeroads.blocker.dns

import android.content.Context
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Manages loading and updating the domain blocklist.
 */
class BlocklistManager(
    private val context: Context,
    private val domainFilter: DomainFilter
) {

    /**
     * Loads the blocklist from an asset file.
     *
     * @param fileName The name of the file in the assets folder.
     * @return Result.success(true) if blocklist loaded with at least 1 valid domain,
     *         Result.failure if file missing, empty, or unreadable.
     */
    fun loadFromAssets(fileName: String = "blocklist.txt"): Result<Boolean> {
        return try {
            val assets = context.assets ?: return Result.failure(IllegalStateException("Assets unavailable"))
            assets.open(fileName).use { inputStream ->
                loadFromStream(inputStream, domainFilter)
            }
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        /**
         * Parses the blocklist from an input stream.
         * Made accessible for testing purposes without needing a Context.
         *
         * @return Result.success(true) if at least 1 valid domain was loaded,
         *         Result.failure if no valid domains found or stream unreadable.
         */
        fun loadFromStream(inputStream: InputStream, domainFilter: DomainFilter): Result<Boolean> {
            return try {
                val domains = mutableSetOf<String>()
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    reader.forEachLine { line ->
                        val trimmed = line.trim()
                        // Ignore comments and blank lines
                        if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                            if (isValidDomainEntry(trimmed)) {
                                domains.add(trimmed)
                            }
                        }
                    }
                }
                if (domains.isEmpty()) {
                    Result.failure(IllegalArgumentException("Blocklist contains no valid domain entries"))
                } else {
                    domainFilter.loadEntries(domains)
                    Result.success(true)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        private fun isValidDomainEntry(entry: String): Boolean {
            val clean = entry.trim().removeSuffix(".")
            if (clean.isEmpty() || clean.length > 253) return false
            if (clean.any { it.isWhitespace() || it == '/' || it == ':' }) return false
            val labels = clean.split(".")
            for (label in labels) {
                if (label.isEmpty() || label.length > 63) return false
            }
            return true
        }
    }
}