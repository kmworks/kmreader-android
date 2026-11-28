package com.kmworks.kmreader.ui.reader

/**
 * Resolution of the reading direction shared by both readers.
 *
 * Mirrors the original iOS `resolvePreferredReadingDirection` (PdfReaderView.swift): the forced
 * default wins, then the direction the server reports for the series, then the global default.
 * The DIVINA reader resolves the same chain from `series.metadata.readingDirection`.
 */
internal fun resolveReadingDirection(
    seriesDirection: ReadingDirection?,
    forceDefault: Boolean,
    defaultDirection: ReadingDirection,
): ReadingDirection = if (forceDefault) defaultDirection else seriesDirection ?: defaultDirection

/**
 * Series metadata token → direction. The server sends the enum name (`LEFT_TO_RIGHT`,
 * `RIGHT_TO_LEFT`, `VERTICAL`, `WEBTOON`); an unknown or blank token resolves to null so the caller
 * falls back to the global default instead of guessing.
 *
 * The tokens are compared exactly after trimming: `LEFT_TO_RIGHT` contains "RIGHT", so a substring
 * test would read every LTR series as RTL.
 */
internal fun readingDirectionFromSeriesToken(token: String?): ReadingDirection? =
    when (token?.trim()?.uppercase()) {
        "LEFT_TO_RIGHT" -> ReadingDirection.Ltr
        "RIGHT_TO_LEFT" -> ReadingDirection.Rtl
        "VERTICAL" -> ReadingDirection.Vertical
        "WEBTOON" -> ReadingDirection.Webtoon
        else -> null
    }

/**
 * WebPub manifest `metadata.readingProgression` → direction (the lower-case Readium wire values that
 * Komga's WPReadingProgressionDto emits). Komga derives it from the series reading direction, so the
 * EPUB reader reads the direction from the manifest it already loads.
 *
 * `auto` (and anything unknown) means "let the publication decide": null, so the caller falls back to
 * the global default.
 */
internal fun readingDirectionFromProgression(progression: String?): ReadingDirection? =
    when (progression?.trim()?.lowercase()) {
        "ltr" -> ReadingDirection.Ltr
        "rtl" -> ReadingDirection.Rtl
        "ttb", "btt" -> ReadingDirection.Vertical
        else -> null
    }
