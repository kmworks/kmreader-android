package com.kmworks.kmreader.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The app's single modal container: a centered card.
 *
 * This replaces the ModalBottomSheet wrapper the app started with. A bottom sheet grew with its
 * content up to the full screen and always rose from the bottom edge, so a long form had no
 * visible end and no stable place to look; a short one still pinned itself to the bottom, miles
 * away from the finger that opened it. A centered card fixes both: the window is always in the
 * same place, its width is the width the sheet had (screen width capped at 640dp, 16dp of screen
 * margin), and the height follows the content up to the available height — the window minus the
 * system bars and this card's own 24dp margins. A short card stays short; a long body grows to
 * that height and then scrolls inside the card instead of being squeezed into a square.
 *
 * The body owns its scrolling: a plain Column body adds `verticalScroll`, a LazyColumn body works
 * as is, because the height cap gives `weight(1f)` a bounded parent to divide.
 *
 * A tap on the backdrop dismisses, like back does — the dialog window is full-screen (see the note
 * on the scrim in the body), so the platform's own outside-touch handling cannot fire here.
 *
 * @param fullHeight fill the available height even when the body does not do it on its own; for a
 * panel whose body is a short list that is still meant to occupy the whole card. A body that
 * already fills (a Scaffold, a `fillMaxSize` Box, a `weight(1f)` list) needs nothing — the default
 * cap gives it the available height anyway.
 */
@Composable
fun AppModalDialog(
    onDismissRequest: () -> Unit,
    fullHeight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // The card handles the insets itself (see below), so the window must not consume them.
            decorFitsSystemWindows = false,
        ),
    ) {
        // `usePlatformDefaultWidth = false` stretches the dialog window to the whole screen, and
        // the platform only reports "tapped outside the window" for touches beyond its bounds —
        // with a full-screen window that never happens, which left the dimmed backdrop inert and
        // back as the only way out. The scrim below is that missing gesture target.
        //
        // Its handler is registered once and reads the caller's current lambda through
        // `rememberUpdatedState`; keying the gesture detector on the lambda would tear it down and
        // re-register it on every recomposition of the host screen.
        val dismiss by rememberUpdatedState(onDismissRequest)
        Box(modifier = Modifier.fillMaxSize()) {
            // Invisible: the window already paints the dim behind the content, so this only has to
            // catch taps. It sits under the card in draw order, which is also hit-test order, so a
            // press that lands on the card is absorbed there and never reaches this scrim.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { dismiss() } },
            )
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    // Edge-to-edge window: keep the card clear of the system bars and let it ride above
                    // the keyboard. union() so the IME inset is not counted twice (safeDrawing has it).
                    .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                val cardWidth = maxWidth.coerceAtMost(640.dp)
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    // M3 assigns dialogs the surfaceContainerHigh role: in dark themes the card must
                    // sit ABOVE the page surface in lightness (tonal elevation replaces the shadow,
                    // which is invisible on dark pages). surfaceContainerLow made the card darker than
                    // the dimmed backdrop, melting the edge in dark mode.
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .width(cardWidth)
                        .then(
                            if (fullHeight) {
                                Modifier.fillMaxHeight()
                            } else {
                                // Height follows the content, capped only by the available height.
                                Modifier.heightIn(max = maxHeight)
                            },
                        )
                        // Swallow presses on the card so they cannot fall through to the dismiss scrim
                        // behind it — a dialog that closes when you tap its own body would be unusable.
                        .pointerInput(Unit) { detectTapGestures { } },
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            // The sheet's drag handle used to be this gap; keep the same top breathing room.
                            .padding(top = 16.dp),
                    ) {
                        content()
                    }
                }
            }
        }
    }
}

