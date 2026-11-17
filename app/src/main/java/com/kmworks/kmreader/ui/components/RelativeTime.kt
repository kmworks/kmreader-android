package com.kmworks.kmreader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.kmworks.kmreader.R

/**
 * Localized relative time label (mirrors iOS RelativeDateTimeFormatter named+abbreviated:
 * exactly 1 unit uses "yesterday"/"last week"/"last month"/"last year").
 */
@Composable
fun relativeTimeLabel(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val diff = (nowMs - epochMs).coerceAtLeast(0)
    val minutes = (diff / 60_000).toInt()
    val hours = (diff / 3_600_000).toInt()
    val days = (diff / 86_400_000).toInt()
    return when {
        minutes < 1 -> stringResource(R.string.time_just_now)
        hours < 1 -> pluralStringResource(R.plurals.time_minutes_ago, minutes, minutes)
        days < 1 -> pluralStringResource(R.plurals.time_hours_ago, hours, hours)
        days < 2 -> stringResource(R.string.time_yesterday)
        days < 7 -> pluralStringResource(R.plurals.time_days_ago, days, days)
        days < 14 -> stringResource(R.string.time_last_week)
        days < 30 -> pluralStringResource(R.plurals.time_weeks_ago, days / 7, days / 7)
        days < 60 -> stringResource(R.string.time_last_month)
        days < 365 -> pluralStringResource(R.plurals.time_months_ago, days / 30, days / 30)
        days < 730 -> stringResource(R.string.time_last_year)
        else -> pluralStringResource(R.plurals.time_years_ago, days / 365, days / 365)
    }
}
