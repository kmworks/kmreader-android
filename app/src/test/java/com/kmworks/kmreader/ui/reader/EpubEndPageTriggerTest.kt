package com.kmworks.kmreader.ui.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubEndPageTriggerTest {

    @Test
    fun showsInTheLastChapter() {
        assertTrue(EpubEndPageTrigger.shouldShow(chapterIndex = 2, chapterCount = 3))
        assertTrue(EpubEndPageTrigger.shouldShow(chapterIndex = 0, chapterCount = 1))
    }

    @Test
    fun doesNotShowInAnEarlierChapter() {
        assertFalse(EpubEndPageTrigger.shouldShow(chapterIndex = 1, chapterCount = 3))
        assertFalse(EpubEndPageTrigger.shouldShow(chapterIndex = 0, chapterCount = 3))
    }

    @Test
    fun doesNotShowWithoutChapters() {
        assertFalse(EpubEndPageTrigger.shouldShow(chapterIndex = 0, chapterCount = 0))
    }
}
