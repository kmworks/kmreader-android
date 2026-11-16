package com.kmworks.kmreader.data

/**
 * 页面几何：宽高来自 Komga 的 `PageDto.width/height`（可空，未知按竖版处理）。
 *
 * 原作判定宽图（跨页）的方式：`height > width` 为竖版，否则为横版/跨页
 * （KMReader/Features/Reader/Models/Divina/ReaderViewItemGenerator.swift）。
 */
data class ReaderPage(
    val id: Int,
    val number: Int,
    val width: Int,
    val height: Int,
) {
    val isLandscape: Boolean get() = width > height
}

/** 阅读器的一个展示单元，对应原作 `ReaderViewItem`。 */
sealed interface ReaderViewItem {
    /** 本单元覆盖的页码（双页=两页）。 */
    val pages: List<Int>

    /** 单页。 */
    data class Single(val page: ReaderPage) : ReaderViewItem {
        override val pages: List<Int> = listOf(page.number)
    }

    /** 双页合幅（左右并排）。 */
    data class Dual(val first: ReaderPage, val second: ReaderPage) : ReaderViewItem {
        override val pages: List<Int> = listOf(first.number, second.number)
    }

    /** 跨页宽图整幅展示（可平移，不切开）。 */
    data class Spread(val page: ReaderPage) : ReaderViewItem {
        override val pages: List<Int> = listOf(page.number)
    }

    /** 跨页宽图被切成两半，分别翻页。 */
    data class Half(val page: ReaderPage, val secondHalf: Boolean) : ReaderViewItem {
        override val pages: List<Int> = listOf(page.number)
    }
}

/**
 * 展示单元的生成规则，1:1 对齐原作
 * `ReaderViewItemGenerator.generateViewItems`（Models/Divina/ReaderViewItemGenerator.swift）。
 *
 * 原作分支（pageCurl 在 Android 不做，视为 false）：
 * - forceDualPairs（页面布局=双页，横屏门控外）：封面竖版→单页；可切宽图→切半/整幅；
 *   横版图→单页；末页/独显页/下一页不可配对→单页；其余→双页。
 * - 非 force（自动布局，allowDualPairs 由横屏门控）：横版不可切→单页；封面非宽→单页；
 *   独显页→单页；末页→单页；allowDualPairs 且下一页竖版且非独显→双页；其余→单页。
 * - 宽图只在「拆分宽页」开启时参与切割：ltr/rtl 切两半，scroll 保留整幅（keepsSplitSpreadsWhole）。
 * - 封面单页（solo cover）只在 soloCover=true 时强制（对齐原作 noCover=!shouldSoloCover）。
 */
object ReaderViewItemGenerator {

    fun generate(
        pages: List<ReaderPage>,
        noCover: Boolean,
        allowDualPairs: Boolean,
        forceDualPairs: Boolean,
        splitWidePages: Boolean,
        keepsSplitSpreadsWhole: Boolean,
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
                        if (keepsSplitSpreadsWhole) {
                            items += ReaderViewItem.Spread(page)
                        } else {
                            items += ReaderViewItem.Half(page, secondHalf = false)
                            items += ReaderViewItem.Half(page, secondHalf = true)
                        }
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
                    if (keepsSplitSpreadsWhole) {
                        items += ReaderViewItem.Spread(page)
                    } else {
                        items += ReaderViewItem.Half(page, secondHalf = false)
                        items += ReaderViewItem.Half(page, secondHalf = true)
                    }
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
