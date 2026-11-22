package com.kmworks.kmreader.core.update

/**
 * Version strings as the release tags carry them (`v1.2.0`) and as the build carries them (`1.2.0`).
 *
 * GitHub tags prefix the installed `versionName` with a `v`, so a plain string comparison would never
 * see a newer release. Comparison is numeric and segment-wise (`1.10.0` is newer than `1.9.3`), missing
 * segments count as zero (`1.2` equals `1.2.0`), and a pre-release suffix sorts below its final release
 * (`1.3.0-beta.1` < `1.3.0`), so a beta never replaces a stable build.
 */
object AppVersion {

    /** A parsed version: its numeric segments plus the pre-release suffix, if any. */
    data class Parsed(val segments: List<Int>, val prerelease: String?) : Comparable<Parsed> {

        override fun compareTo(other: Parsed): Int {
            val length = maxOf(segments.size, other.segments.size)
            for (index in 0 until length) {
                val left = segments.getOrElse(index) { 0 }
                val right = other.segments.getOrElse(index) { 0 }
                if (left != right) return left.compareTo(right)
            }
            return when {
                prerelease == null && other.prerelease == null -> 0
                // A final release outranks every pre-release of the same core version.
                prerelease == null -> 1
                other.prerelease == null -> -1
                else -> comparePrerelease(prerelease, other.prerelease)
            }
        }
    }

    /** Parses [raw]; null when it holds no numeric version at all. */
    fun parse(raw: String): Parsed? {
        val text = raw.trim().removePrefix("v").removePrefix("V")
        if (text.isEmpty()) return null
        // Build metadata (`+sha`) never affects ordering, and the first `-` starts the pre-release suffix.
        val withoutBuild = text.substringBefore('+')
        val core = withoutBuild.substringBefore('-')
        val prerelease = withoutBuild
            .substringAfter('-', missingDelimiterValue = "")
            .takeIf { it.isNotEmpty() }
        val segments = core.split('.')
            .map { part -> part.takeWhile { it.isDigit() } }
            .filter { it.isNotEmpty() }
            .mapNotNull { it.toIntOrNull() }
        if (segments.isEmpty()) return null
        return Parsed(segments = segments, prerelease = prerelease)
    }

    /** True when [candidate] is strictly newer than [current]; unreadable input never counts as newer. */
    fun isNewer(candidate: String, current: String): Boolean {
        val next = parse(candidate) ?: return false
        val installed = parse(current) ?: return false
        return next > installed
    }

    /**
     * Semver pre-release precedence: dot-separated identifiers, numeric ones compared as numbers and
     * ranked below alphanumeric ones, with fewer identifiers ranking lower when all shared ones match.
     */
    private fun comparePrerelease(left: String, right: String): Int {
        val leftParts = left.split('.')
        val rightParts = right.split('.')
        for (index in 0 until minOf(leftParts.size, rightParts.size)) {
            val a = leftParts[index]
            val b = rightParts[index]
            val aNumber = a.toIntOrNull()
            val bNumber = b.toIntOrNull()
            val result = when {
                aNumber != null && bNumber != null -> aNumber.compareTo(bNumber)
                aNumber != null -> -1
                bNumber != null -> 1
                else -> a.compareTo(b)
            }
            if (result != 0) return result
        }
        return leftParts.size.compareTo(rightParts.size)
    }
}
