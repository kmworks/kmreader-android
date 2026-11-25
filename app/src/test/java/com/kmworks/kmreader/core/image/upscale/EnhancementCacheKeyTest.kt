package com.kmworks.kmreader.core.image.upscale

import com.kmworks.kmreader.ui.reader.DenoiseLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cache-key layout (docs/waifu2x-upscaling-plan.md §6).
 *
 * A page's key is [EnhancementCache.pageKey] (URL + [UpscaleConfig.MODEL_SIGNATURE]) plus a denoise
 * *variant* suffix. The split is what stops a denoise switch from re-running the whole book: the page
 * identity stays the same, only the requested variant misses.
 */
class EnhancementCacheKeyTest {

    private val page = "http://192.168.1.254:25600/api/v1/books/0RPWCA2D5YAP5/pages/17"

    @Test
    fun `same page and level produce a stable key`() {
        assertEquals(
            EnhancementCache.cacheKey(page, DenoiseLevel.Low),
            EnhancementCache.cacheKey(page, DenoiseLevel.Low),
        )
    }

    @Test
    fun `every denoise level is a distinct variant of one page`() {
        val keys = DenoiseLevel.entries.map { EnhancementCache.cacheKey(page, it) }
        assertEquals(DenoiseLevel.entries.size, keys.toSet().size)

        val pageKey = EnhancementCache.pageKey(page)
        assertTrue(keys.all { it.startsWith("$pageKey#") })
    }

    @Test
    fun `the page key is independent of the denoise level`() {
        // The point of the split: a level switch must not create a new page identity.
        val pageKey = EnhancementCache.pageKey(page)
        for (denoise in DenoiseLevel.entries) {
            assertFalse(pageKey.contains(denoise.name))
            assertFalse(UpscaleConfig.MODEL_SIGNATURE.contains(denoise.name))
        }
    }

    @Test
    fun `different pages never share a key`() {
        val other = page.replace("/pages/17", "/pages/18")
        assertNotEquals(EnhancementCache.pageKey(page), EnhancementCache.pageKey(other))
        for (denoise in DenoiseLevel.entries) {
            assertNotEquals(
                EnhancementCache.cacheKey(page, denoise),
                EnhancementCache.cacheKey(other, denoise),
            )
        }
    }

    @Test
    fun `keys are hashed and never leak the URL`() {
        val pageKey = EnhancementCache.pageKey(page)
        assertEquals(64, pageKey.length)
        assertTrue(pageKey.all { it in "0123456789abcdef" })
        assertFalse(EnhancementCache.cacheKey(page, DenoiseLevel.Max).contains("0RPWCA2D5YAP5"))
    }

    @Test
    fun `the model signature carries the scale and the bundled-model version`() {
        // Bumping Waifu2xBridge.MODEL_CACHE_VERSION is the documented way to invalidate everything.
        assertEquals(
            "upconv7-s${Waifu2xBridge.SCALE}-mv${Waifu2xBridge.MODEL_CACHE_VERSION}",
            UpscaleConfig.MODEL_SIGNATURE,
        )
    }

    @Test
    fun `at most two variants per page are kept`() {
        // Bounded footprint: the level in use plus the previous one, so A→B→A stays a cache hit.
        assertEquals(2, EnhancementCache.MAX_VARIANTS_PER_PAGE)
    }
}
