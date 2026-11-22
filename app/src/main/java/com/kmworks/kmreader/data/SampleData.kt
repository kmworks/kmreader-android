package com.kmworks.kmreader.data

import androidx.annotation.StringRes
import com.kmworks.kmreader.R
import kotlinx.serialization.json.JsonObject

/** Author role, mirroring KMReader AuthorRole (same icon mapping). [token] is the raw server role value. */
enum class AuthorRole(@param:StringRes val labelRes: Int, val token: String) {
    Writer(R.string.author_role_writer, "writer"),
    Penciller(R.string.author_role_penciller, "penciller"),
    Inker(R.string.author_role_inker, "inker"),
    Colorist(R.string.author_role_colorist, "colorist"),
    Letterer(R.string.author_role_letterer, "letterer"),
    Cover(R.string.author_role_cover, "cover"),
    Editor(R.string.author_role_editor, "editor"),
    Translator(R.string.author_role_translator, "translator"),
    Custom(R.string.author_role_custom, "custom"),
    ;

    companion object {
        /** Predefined roles selectable in edit forms (mirrors iOS AuthorRole.predefinedCases). */
        val predefined: List<AuthorRole>
            get() = listOf(Writer, Penciller, Inker, Colorist, Letterer, Cover, Editor, Translator)
    }
}

/**
 * Creator entry on the detail page (sorted by role, mirroring SeriesHeroInfoView.creatorItems).
 * [customRole] keeps the raw server-provided custom role text (used as the display name when
 * role is AuthorRole.Custom, mirroring iOS AuthorRole.custom(String)).
 */
data class Creator(
    val name: String,
    val role: AuthorRole,
    val customRole: String? = null,
)

/** Notable entity aggregated from book metadata (character/team/location), mirroring NotableEntity. */
@kotlinx.serialization.Serializable
data class NotableEntity(val name: String, val kind: String)

/** Known entity kinds (mirrors iOS NotableEntity.knownKinds: character/team/location). */
val NotableEntityKnownKinds = listOf("character", "team", "location")

/** Filter/route value key: kind ␟ name (␟-separated, mirrors iOS NotableEntity.filterKey). */
val NotableEntity.filterKey: String get() = "$kind\u001F$name"

/** Restore an entity from its value key (picker write-back / route un-selection). */
fun notableEntityFromFilterKey(key: String): NotableEntity? =
    key.split('\u001F').takeIf { it.size == 2 }?.let { NotableEntity(name = it[1], kind = it[0]) }

/** Series. [progress] is the overall read ratio of the series, null means not started. */
data class Series(
    val id: Int,
    val title: String,
    val author: String,
    val hue: Float,
    val bookCount: Int,
    val unreadCount: Int,
    val progress: Float?,
    val publisher: String? = null,
    val status: String? = null,
    val language: String? = null,
    /**
     * Raw Komga reading direction: LEFT_TO_RIGHT / RIGHT_TO_LEFT / VERTICAL / WEBTOON. Kept as the
     * server token (like [status]) so the UI can show all four values; shells and cached rows that
     * cannot supply one fall back to LEFT_TO_RIGHT.
     */
    val readingDirection: String = "LEFT_TO_RIGHT",
    val releaseYear: Int? = null,
    val totalBookCount: Int? = null,
    val ageRating: Int? = null,
    val creators: List<Creator> = emptyList(),
    val notableEntities: List<NotableEntity> = emptyList(),
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val links: List<Pair<String, String>> = emptyList(),
    val summary: String? = null,
    /** One-shot (mirrors iOS series.oneshot): the card detail row shows the one-shot line and taps into the one-shot detail. */
    val oneshot: Boolean = false,
)

/** Book. progress null = unread, 1f = finished. */
data class Book(
    val id: Int,
    val number: String,
    val title: String,
    val seriesTitle: String,
    /**
     * Owning series DTO id (mirrors Komga book.seriesId). Carried on the row so a download enqueued from a
     * book card can be tagged with its series and the card can show the series download state.
     */
    val seriesDtoId: String? = null,
    val hue: Float,
    val pageCount: Int,
    val progress: Float?,
    /** One-shot (mirrors iOS book.oneshot). */
    val oneshot: Boolean = false,
    /** First author name, used as the one-shot card subtitle (mirrors iOS authors.primary). */
    val author: String? = null,
    /** Media status (READY/ERROR/UNSUPPORTED/OUTDATED/UNKNOWN, mirrors iOS MediaStatus). */
    val mediaStatus: String = "READY",
    /**
     * Server media type (application/x-cbz, application/epub+zip, application/pdf, …), empty when the
     * row does not know it. Carried on the book itself so opening the reader never needs a round trip:
     * the format has to be known before the reader page is pushed (see [readerFormat]).
     */
    val mediaType: String = "",
    /**
     * Komga reports whether an EPUB is DIVINA-compatible (server-rendered pages) or must be read as a
     * WebPub/whole-file EPUB. Mirrors MediaDto.epubDivinaCompatible; the download kind depends on it.
     */
    val epubDivinaCompatible: Boolean = false,
    /** Marked deleted on the server (mirrors iOS book.deleted; shown in red as "Unavailable" on the detail page). */
    val deleted: Boolean = false,
    /** External metadata links (komf identification candidates; series links are often empty for one-shots so fall back to the book). */
    val links: List<Pair<String, String>> = emptyList(),
    /** Notable entities (character/team/location/custom, mirrors iOS book.metadata.notableEntities). */
    val notableEntities: List<NotableEntity> = emptyList(),
    /** Last read timestamp (epoch millis, from readProgress.readDate; mirrors iOS readProgress.readDate). */
    val lastReadAtMs: Long? = null,
) {
    /** Mirrors iOS BookDisplayItem.bookTitleLine: one-shots carry no number prefix. */
    val titleLine: String get() = if (oneshot) title else "$number - $title"

    /**
     * Reader route decided from [mediaType]: EPUB / PDF / everything else is the DIVINA reader, matching
     * the server's media types. An empty/unknown value opens DIVINA, the same fallback the old lookup had.
     */
    val readerFormat: String
        get() = when {
            "epub" in mediaType.lowercase() -> "EPUB"
            "pdf" in mediaType.lowercase() -> "PDF"
            else -> "CBZ"
        }
}

/** Mirrors KMReader LayoutConfig (iPhone values, calibrated against Apple Books): large 152 / medium 116 / small 80 / horizontal 250. */
enum class DashboardCardKind(val widthDp: Int) {
    Large(152), Medium(116), Small(80), Horizontal(250)
}

data class DashboardSection<T>(
    val id: String,
    val title: String,
    val kind: DashboardCardKind,
    val items: List<T>,
)

/** Shared UI model container (demo fixtures were removed entirely; all content comes from the server). */
object SampleData {

    data class ListEntry(
        val id: Int,
        val title: String,
        val count: Int,
        val hue: Float,
        val pinned: Boolean,
        /** Smart lists only: target type (book/series) and modification date; null for plain lists. */
        val targetLabel: String? = null,
        val modifiedLabel: String? = null,
        /** Initial values for the edit sheet; sourced from the real DTO so blank values never overwrite server fields. */
        val summary: String? = null,
        val ordered: Boolean? = null,
        val smartListTarget: SmartListTarget? = null,
        /** Smart lists only: the server search document, read back for the editor's filter summary. */
        val search: JsonObject? = null,
    )
}
