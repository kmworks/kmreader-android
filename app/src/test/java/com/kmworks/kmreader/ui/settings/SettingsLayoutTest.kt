package com.kmworks.kmreader.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The settings hub switches to two columns on tablets; the split must stay level and keep the order. */
class SettingsLayoutTest {

    private fun group(title: String, rows: Int, subtitled: Boolean = true) = SettingsGroup(
        title = title,
        rows = List(rows) { index ->
            SettingsRow(
                title = "$title $index",
                icon = Icons.Outlined.Info,
                accent = SettingsAccent.Gray,
                subtitle = if (subtitled) "description" else null,
            )
        },
    )

    private fun estimatedHeight(group: SettingsGroup): Int =
        group.rows.sumOf { if (it.subtitle.isNullOrEmpty()) 56 else 72 }

    @Test
    fun keepsEachColumnLevelAndOrdered() {
        val groups = listOf(
            group("a", rows = 3),
            group("b", rows = 2),
            group("c", rows = 1),
            group("d", rows = 2),
            group("e", rows = 1),
        )

        val (left, right) = balanceSettingsGroups(groups)

        assertEquals(listOf("a", "d"), left.map { it.title })
        assertEquals(listOf("b", "c", "e"), right.map { it.title })
        // Every group lands in exactly one column, in the original relative order.
        assertEquals(groups, (left + right).sortedBy { groups.indexOf(it) })
        assertTrue(
            "columns differ by at most one card",
            abs(left.sumOf(::estimatedHeight) - right.sumOf(::estimatedHeight)) <= 72,
        )
    }

    @Test
    fun putsEverythingInTheFirstColumnWhenThereIsOneGroup() {
        val groups = listOf(group("only", rows = 4))

        val (left, right) = balanceSettingsGroups(groups)

        assertEquals(listOf("only"), left.map { it.title })
        assertTrue(right.isEmpty())
    }

    @Test
    fun rowsWithoutSubtitlesAreShorterForBalancing() {
        // 5 rows without subtitles (280) are lighter than 4 described rows (288).
        val plain = group("plain", rows = 5, subtitled = false)
        val described = group("described", rows = 4, subtitled = true)
        val (left, right) = balanceSettingsGroups(listOf(plain, described, described))

        assertEquals(listOf("plain", "described"), left.map { it.title })
        assertEquals(listOf("described"), right.map { it.title })
    }
}
