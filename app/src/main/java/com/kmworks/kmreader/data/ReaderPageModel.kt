package com.kmworks.kmreader.data

/**
 * Page geometry: width/height come from Komga's `PageDto.width/height` (nullable; unknown sizes are
 * treated as portrait).
 *
 * How the original decides a wide (spread) page: `height > width` is portrait, otherwise landscape /
 * spread (KMReader/Features/Reader/Models/Divina/ReaderViewItemGenerator.swift).
 */
data class ReaderPage(
    val id: Int,
    val number: Int,
    val width: Int,
    val height: Int,
) {
    val isLandscape: Boolean get() = width > height
}

/** One display cell of the reader, mirroring the original `ReaderViewItem`. */
sealed interface ReaderViewItem {
    /** Page numbers covered by this cell (a dual pair covers two pages). */
    val pages: List<Int>

    /** Single page. */
    data class Single(val page: ReaderPage) : ReaderViewItem {
        override val pages: List<Int> = listOf(page.number)
    }

    /** Dual pair shown side by side. */
    data class Dual(val first: ReaderPage, val second: ReaderPage) : ReaderViewItem {
        override val pages: List<Int> = listOf(first.number, second.number)
    }

    /** A wide spread page cut into two halves, each turned separately. */
    data class Half(val page: ReaderPage, val secondHalf: Boolean) : ReaderViewItem {
        override val pages: List<Int> = listOf(page.number)
    }
}

/**
 * Rules for generating display cells, 1:1 with the original
 * `ReaderViewItemGenerator.generateViewItems` (Models/Divina/ReaderViewItemGenerator.swift).
 *
 * Original branches (pageCurl is not implemented on Android, so it counts as false):
 * - forceDualPairs (page layout = dual, outside the landscape gate): portrait cover → single page;
 *   splittable wide page → halves; landscape page → single page; last page / solo page /
 *   unpairable next page → single page; otherwise → dual pair.
 * - not forced (auto layout, allowDualPairs gated by landscape): landscape not splittable → single
 *   page; non-wide cover → single page; solo page → single page; last page → single page;
 *   allowDualPairs and the next page is portrait and not solo → dual pair; otherwise → single page.
 * - Wide pages only participate in splitting when "split wide pages" is on: ltr/rtl cut them in two
 *   halves. The caller gates this on a portrait screen and an upright page, so a landscape screen or
 *   a 90°/270° rotation yields a single page.
 * - Solo cover is forced only when soloCover=true (mirrors the original noCover=!shouldSoloCover).
 */
object ReaderViewItemGenerator {

    fun generate(
        pages: List<ReaderPage>,
        noCover: Boolean,
        allowDualPairs: Boolean,
        forceDualPairs: Boolean,
        splitWidePages: Boolean,
        soloPages: Set<Int> = emptySet(),
    ): List<ReaderViewItem> {
        if (pages.isEmpty()) return emptyList()
        val items = mutableListOf<ReaderViewItem>()
        var index = 0

        fun isSolo(page: ReaderPage): Boolean = page.number in soloPages
        fun pairable(page: ReaderPage?): Boolean =
            page != null && !page.isLandscape && !isSolo(page)

        while (index < pages.size) {
            val page = pages[index]
            val isCoverPage = index == 0
            val coverShouldSolo = isCoverPage && !noCover
            val coverPairable = coverShouldSolo && !page.isLandscape
            val wideEligibleForSplit = splitWidePages && page.isLandscape
            val isLast = index == pages.lastIndex
            val next = pages.getOrNull(index + 1)

            if (forceDualPairs) {
                when {
                    coverPairable -> {
                        items += ReaderViewItem.Single(page)
                        index += 1
                    }
                    wideEligibleForSplit -> {
                        items += ReaderViewItem.Half(page, secondHalf = false)
                        items += ReaderViewItem.Half(page, secondHalf = true)
                        index += 1
                    }
                    page.isLandscape -> {
                        items += ReaderViewItem.Single(page)
                        index += 1
                    }
                    isLast || isSolo(page) || !pairable(next) -> {
                        items += ReaderViewItem.Single(page)
                        index += 1
                    }
                    else -> {
                        items += ReaderViewItem.Dual(page, next!!)
                        index += 2
                    }
                }
                continue
            }

            when {
                wideEligibleForSplit -> {
                    items += ReaderViewItem.Half(page, secondHalf = false)
                    items += ReaderViewItem.Half(page, secondHalf = true)
                    index += 1
                }
                page.isLandscape -> {
                    items += ReaderViewItem.Single(page)
                    index += 1
                }
                coverShouldSolo -> {
                    items += ReaderViewItem.Single(page)
                    index += 1
                }
                isSolo(page) -> {
                    items += ReaderViewItem.Single(page)
                    index += 1
                }
                isLast -> {
                    items += ReaderViewItem.Single(page)
                    index += 1
                }
                allowDualPairs && next != null && !next.isLandscape && !isSolo(next) -> {
                    items += ReaderViewItem.Dual(page, next)
                    index += 2
                }
                else -> {
                    items += ReaderViewItem.Single(page)
                    index += 1
                }
            }
        }
        return items
    }
}
