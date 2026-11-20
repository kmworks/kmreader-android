package com.kmworks.kmreader.ui.components

import android.os.SystemClock
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long the indicator stays up even when the reload finishes sooner (mirrors iOS
 *  RefreshableMinimumHold.swift, 800 ms): a fast reload must not snap the control back instantly. */
private const val MINIMUM_HOLD_MILLIS = 800L

/**
 * Pull-to-refresh whose indicator stays visible for at least [MINIMUM_HOLD_MILLIS], and longer when
 * [onRefresh] runs longer, so the spinner never blinks away. Mirrors iOS `.refreshableWithMinimumHold`.
 *
 * [onRefresh] suspends until the reload landed: join the view model's job
 * (`viewModel.refresh().join()`) so the work keeps running in the view model scope if this screen
 * leaves composition mid-refresh.
 *
 * [indicatorTopPadding] lifts the indicator clear of a floating top bar the content scrolls
 * underneath; pass the same bar padding the scroll containers use for their content padding.
 *
 * [onRefreshingChange] reports the gesture state so a screen can drop another loading bar while this
 * indicator already shows that a reload is running.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullRefreshBox(
    onRefresh: suspend () -> Unit,
    modifier: Modifier = Modifier,
    indicatorTopPadding: Dp = 0.dp,
    onRefreshingChange: (Boolean) -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            // A second pull while one runs is ignored: the indicator is already up.
            if (!refreshing) {
                refreshing = true
                onRefreshingChange(true)
                scope.launch {
                    val startedAt = SystemClock.elapsedRealtime()
                    try {
                        onRefresh()
                    } finally {
                        val remaining = MINIMUM_HOLD_MILLIS - (SystemClock.elapsedRealtime() - startedAt)
                        if (remaining > 0) delay(remaining)
                        refreshing = false
                        onRefreshingChange(false)
                    }
                }
            }
        },
        modifier = modifier,
        state = state,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = refreshing,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = indicatorTopPadding),
            )
        },
        content = content,
    )
}
