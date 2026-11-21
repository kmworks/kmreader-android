package com.kmworks.kmreader.ui.dashboard

/**
 * The book a read list continues with, plus the numbers the card shows.
 *
 * @property nextBookIndex 0-based index of that book inside the list.
 * @property booksRead how many books of the list are finished.
 * @property lastReadAtMs most recent read timestamp across the list's books; the dashboard sorts on it.
 */
internal data class ReadListContinuationPick(
    val nextBookIndex: Int,
    val booksRead: Int,
    val lastReadAtMs: Long?,
)

/**
 * Picks the book a read list continues with, mirroring the iOS continuation rules
 * (DatabaseOperator+ReadListReading.resolveReadListContinuation):
 *
 *  - a book already in progress wins;
 *  - otherwise, once a book is finished, the list continues with the next unread one (falling back to the
 *    earliest unread one, so a list read out of order still resumes);
 *  - a list nobody has started has no continuation at all.
 *
 * That last rule is the point: "the first unfinished book" is trivially true for an all-unread list, which
 * would advertise every newly created list as "in progress". The iOS version gets the same effect by only
 * looking at read lists that have a local reading state; here the progress of the books themselves decides.
 *
 * @param progresses each book's reading progress as a 0..1 fraction (`null` = never opened).
 * @param readDatesMs each book's last read timestamp (`null` = never opened).
 * @return the pick, or null when the list has nothing to continue (empty, all unread, or all finished).
 */
internal fun resolveReadListContinuation(
    progresses: List<Float?>,
    readDatesMs: List<Long?>,
): ReadListContinuationPick? {
    if (progresses.isEmpty()) return null
    val booksRead = progresses.count { progress -> (progress ?: 0f) >= 1f }
    val inProgress = progresses.indexOfFirst { progress -> progress != null && progress > 0f && progress < 1f }
    if (inProgress >= 0) {
        return ReadListContinuationPick(inProgress, booksRead, readDatesMs.filterNotNull().maxOrNull())
    }
    val finished = progresses.indexOfFirst { progress -> (progress ?: 0f) >= 1f }
    if (finished < 0) return null
    fun isUnread(index: Int): Boolean {
        val progress = progresses[index] ?: return true
        return progress <= 0f
    }
    // Continue after the finished book; a list read out of order resumes at its earliest unread book.
    val next = (finished + 1 until progresses.size).firstOrNull(::isUnread)
        ?: progresses.indices.firstOrNull(::isUnread)
        ?: return null
    return ReadListContinuationPick(next, booksRead, readDatesMs.filterNotNull().maxOrNull())
}
