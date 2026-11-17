package com.kmworks.kmreader.data

/** Smart list target type (aligned with SmartList.target: book/series). */
enum class SmartListTarget(val label: String) {
    Book("Books"), Series("Series")
}

/** Collection detail navigation model (real content is loaded and overridden by CollectionDetailViewModel via the registry). */
data class CollectionDetail(
    val id: Int,
    val name: String,
    val summary: String?,
    val ordered: Boolean,
    val seriesIds: List<Int>,
)

/** Read list detail navigation model; bookIds are ordered and the order is the reading order (real content is overridden by the VM). */
data class ReadListDetail(
    val id: Int,
    val name: String,
    val summary: String?,
    val ordered: Boolean,
    val bookIds: List<Int>,
)

/** Smart list detail navigation model (real content is loaded and overridden by SmartListDetailViewModel via the registry). */
data class SmartListDetail(
    val id: Int,
    val name: String,
    val summary: String?,
    val target: SmartListTarget,
    val modifiedLabel: String,
    val bookIds: List<Int> = emptyList(),
    val seriesIds: List<Int> = emptyList(),
)

/** Server lock state of each book metadata field (BookMetadataDto.xxxLock passed through; all unlocked by default). */
data class BookMetadataLocks(
    val titleLock: Boolean = false,
    val numberLock: Boolean = false,
    val numberSortLock: Boolean = false,
    val releaseDateLock: Boolean = false,
    val isbnLock: Boolean = false,
    val summaryLock: Boolean = false,
    val authorsLock: Boolean = false,
    val tagsLock: Boolean = false,
    val linksLock: Boolean = false,
    val notableEntitiesLock: Boolean = false,
)

/** Supplemental book detail metadata (release date/size/format/path/summary/authors/tags, mapped from BookDto). */
data class BookDetailMeta(
    val releaseDate: String,
    val sizeLabel: String,
    val format: String,
    val filePath: String,
    val summary: String,
    val authors: List<Creator>,
    val tags: List<String>,
    /** Raw values of the editable metadata (aligned with the General/Links fields of BookEditSheet). */
    val number: String = "",
    val numberSort: Float? = null,
    val isbn: String? = null,
    val links: List<Pair<String, String>> = emptyList(),
    /** Notable entities (aligned with iOS book.metadata.notableEntities). */
    val notableEntities: List<NotableEntity> = emptyList(),
    /** Server lock state of each field (all false when there is no DTO). */
    val locks: BookMetadataLocks = BookMetadataLocks(),
)
