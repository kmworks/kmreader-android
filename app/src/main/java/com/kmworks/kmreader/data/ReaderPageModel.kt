package com.kmworks.kmreader.data

/**
 * 页面几何：宽高来自 Komga 的 `PageDto.width/height`（可空，未知按竖版处理；
 * 无会话时回退 pagesFor 假数据）。
 *
 * 原作判定宽图（跨页）的方式：`height > width` 为竖版，否则为横版/跨页
 * （KMReader/Features/Reader/Models/Divina/ReaderViewItemGenerator.swift:64-71）。
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
 * `ReaderViewItemGenerator.generateViewItems`（同文件 :36-190）：
 *
 * - 宽图（横版）只在「拆分宽页」开启（或仿真卷页、双页强制配对）时才参与切割；
 *   单页模式下切两半（`keepsSplitSpreadsWhole` 时保留整幅），双页模式下整幅占一个 spread。
 * - 封面页、末页、solo 页永远单页展示。
 * - 双页模式下相邻的宽页不与前一页配对，留给自己的展示单元。
 */
object ReaderViewItemGenerator {

    /** 离线/样例回退假数据：每 6 页出现一个跨页宽图；封面固定竖版。 */
    fun pagesFor(book: Book): List<ReaderPage> = buildList {
        for (number in 1..book.pageCount) {
            val isSpread = number > 1 && number % 6 == 0
            add(
                ReaderPage(
                    id = book.id * 1000 + number,
                    number = number,
                    width = if (isSpread) 2800 else 1400,
                    height = 2000,
                ),
            )
        }
    }

    fun generate(
        pages: List<ReaderPage>,
        dualPage: Boolean,
        splitWidePages: Boolean,
        keepsSplitSpreadsWhole: Boolean = true,
    ): List<ReaderViewItem> {
        if (pages.isEmpty()) return emptyList()
        val items = mutableListOf<ReaderViewItem>()
        var index = 0

        while (index < pages.size) {
            val page = pages[index]
            val isCoverPage = index == 0
            val isWideCoverPage = isCoverPage && page.isLandscape
            val wideEligibleForSplit =
                splitWidePages && page.isLandscape && (isWideCoverPage || !isCoverPage)
            val isLast = index == pages.lastIndex

            if (dualPage) {
                if (wideEligibleForSplit) {
                    items += ReaderViewItem.Spread(page)
                    index += 1
                    continue
                }
                val next = pages.getOrNull(index + 1)
                val nextPairable = next != null && !next.isLandscape
                val showSingle = (isCoverPage && !page.isLandscape) || isLast || !nextPairable
                if (showSingle || next == null) {
                    items += ReaderViewItem.Single(page)
                    index += 1
                } else {
                    items += ReaderViewItem.Dual(page, next)
                    index += 2
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
                else -> {
                    items += ReaderViewItem.Single(page)
                    index += 1
                }
            }
        }
        return items
    }
}
