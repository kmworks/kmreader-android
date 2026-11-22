package com.kmworks.kmreader.ui.detail

/**
 * Komga parses the release date as a `LocalDate`: a value that is not `yyyy-MM-dd` fails the whole
 * PATCH with a 400. The edit sheet validates with this before submitting instead of letting the
 * save die silently.
 */
internal fun isIsoLocalDate(text: String): Boolean =
    runCatching { java.time.LocalDate.parse(text.trim()) }.isSuccess
