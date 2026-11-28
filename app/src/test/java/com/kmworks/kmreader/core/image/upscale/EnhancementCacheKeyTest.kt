package com.kmworks.kmreader.core.image.upscale

import com.kmworks.kmreader.ui.reader.DenoiseLevel
import com.kmworks.kmreader.ui.reader.UpscalePrecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cache-key layout (docs/waifu2x-upscaling-plan.md §6).
 *
 * A page's key is [EnhancementCache.pageKey] (URL + [UpscaleConfig.MODEL_SIGNATURE]) plus denoise
 * and precision *variant* suffixes. The split is what stops a denoise switch from re-running the
 * whole book, while precision variants stay isolated because FP16 output can be driver-corrupted.
 */
class EnhancementCacheKeyTest {

    private val page = "http://192.168.1.254:25600/api/v1/books/0RPWCA2D5YAP5/pages/17"

    @Test
    fun `same page and level produce a stable key`() {
        assertEquals(
            EnhancementCache.cacheKey(page, DenoiseLevel.Low, UpscalePrecision.Auto),
            EnhancementCache.cacheKey(page, DenoiseLevel.Low, UpscalePrecision.Auto),
        )
    }

    @Test
    fun `every denoise level is a distinct variant of one page`() {
        val keys = DenoiseLevel.entries.map {
            EnhancementCache.cacheKey(page, it, UpscalePrecision.Auto)
        }
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
                EnhancementCache.cacheKey(page, denoise, UpscalePrecision.Auto),
                EnhancementCache.cacheKey(other, denoise, UpscalePrecision.Auto),
            )
        }
    }

    @Test
    fun `precision variants never alias`() {
        // Switching to FP32 is the escape hatch for a driver that corrupts FP16 output, so it must
        // never be served the old FP16 bitmap.
        val keys = UpscalePrecision.entries.map {
            EnhancementCache.cacheKey(page, DenoiseLevel.Low, it)
        }
        assertEquals(UpscalePrecision.entries.size, keys.toSet().size)
    }

    @Test
    fun `keys are hashed and never leak the URL`() {
        val pageKey = EnhancementCache.pageKey(page)
        assertEquals(64, pageKey.length)
        assertTrue(pageKey.all { it in "0123456789abcdef" })
        assertFalse(
            EnhancementCache.cacheKey(page, DenoiseLevel.Max, UpscalePrecision.Auto)
                .contains("0RPWCA2D5YAP5"),
        )
    }

    @Test
    fun `the model signature carries the scale and the bundled-model version`() {
        // Bumping Waifu2xBridge.MODEL_CACHE_VERSION is the documented way to invalidate everything.
        assertEquals(
            "upconv7-s${Waifu2xBridge.SCALE}-mv${Waifu2xBridge.MODEL_CACHE_VERSION}" +
                "-ov${UpscaleConfig.OUTPUT_VERSION}",
            UpscaleConfig.MODEL_SIGNATURE,
        )
    }

    @Test
    fun `at most two variants per page are kept`() {
        // Bounded footprint: the level in use plus the previous one, so A→B→A stays a cache hit.
        assertEquals(2, EnhancementCache.MAX_VARIANTS_PER_PAGE)
    }
}
