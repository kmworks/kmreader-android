package com.kmworks.kmreader.ui.reader

import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Volume-button page turning (mirrors the iOS volumeButtonPageTurnEnabled AppStorage key).
 *
 * Unlike iOS — which has to watch MPVolumeView and restore the audio level afterwards — Android
 * can simply mark the KeyEvent handled: a consumed press never reaches the system, so the volume
 * stays exactly where it was and no volume HUD appears while reading.
 */
enum class VolumeKeyPage { Next, Previous }

/**
 * Volume up turns to the next page and volume down to the previous one; in right-to-left reading
 * order the pages run the other way, so the two keys mirror (the same mapping as the iOS
 * handleVolumeButton and the left/right tap zones).
 */
fun volumeKeyPage(volumeUp: Boolean, rtl: Boolean): VolumeKeyPage =
    if (volumeUp != rtl) VolumeKeyPage.Next else VolumeKeyPage.Previous

/**
 * Window-level dispatch for the readers whose page is an Android view (the EPUB WebView and the
 * PDF PdfView): those views are focusable and take the window focus on touch, so Compose's
 * preview-key pass never sees a press that lands on them. The reader registers a handler while it
 * is on screen, and MainActivity offers every volume key to it before the view hierarchy — and the
 * system — gets the event, consuming the ones a reader handled.
 */
object ReaderVolumeKeyDispatcher {

    private var handler: ((KeyEvent) -> Boolean)? = null

    /** Installs [handler] and returns the detach function; the most recent registration wins. */
    fun attach(handler: (KeyEvent) -> Boolean): () -> Unit {
        this.handler = handler
        return { if (this.handler === handler) this.handler = null }
    }

    /**
     * Offers one event to the registered reader. Both actions of a handled key are consumed so the
     * system never adjusts the volume; the handler turns the page on the initial press only.
     */
    fun dispatch(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }
        return handler?.invoke(event) == true
    }
}

/**
 * Registers the calling reader with [ReaderVolumeKeyDispatcher] while [enabled] and the
 * composition last. Used by the readers that host an Android view; the comic reader is pure
 * Compose and uses [rememberVolumeKeyPagingModifier] instead.
 */
@Composable
fun VolumeKeyPagingEffect(
    enabled: Boolean,
    rtl: Boolean,
    onTurn: (VolumeKeyPage) -> Unit,
) {
    val turn by rememberUpdatedState(onTurn)
    val rtlNow by rememberUpdatedState(rtl)
    DisposableEffect(enabled) {
        if (!enabled) {
            return@DisposableEffect onDispose {}
        }
        val detach = ReaderVolumeKeyDispatcher.attach { event ->
            val page = when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> volumeKeyPage(volumeUp = true, rtl = rtlNow)
                KeyEvent.KEYCODE_VOLUME_DOWN -> volumeKeyPage(volumeUp = false, rtl = rtlNow)
                else -> return@attach false
            }
            // A page turns on the initial press: a held key (repeatCount > 0) and the matching
            // key-up are still consumed, they just do not turn again.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                turn(page)
            }
            true
        }
        onDispose { detach() }
    }
}

/**
 * Focus-based volume-key paging for a pure-Compose reader: the node this modifier sits on takes
 * the focus and handles the presses in the preview pass, before the window sees them (consuming
 * them keeps the system volume untouched). The focus is requested again whenever paging becomes
 * enabled — a modal sheet takes it away, and closing the sheet does not give it back by itself.
 */
@Composable
fun rememberVolumeKeyPagingModifier(
    enabled: Boolean,
    rtl: Boolean,
    onTurn: (VolumeKeyPage) -> Unit,
): Modifier {
    val focusRequester = remember { FocusRequester() }
    val turn by rememberUpdatedState(onTurn)
    val rtlNow by rememberUpdatedState(rtl)
    val enabledNow by rememberUpdatedState(enabled)
    LaunchedEffect(enabled) {
        // requestFocus throws when the node is not attached yet (first composition before the
        // modifier is placed); the next enabled change or recomposition retries.
        if (enabled) runCatching { focusRequester.requestFocus() }
    }
    return Modifier
        .onPreviewKeyEvent { event ->
            if (!enabledNow || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            // Consume the repeats of a held key too, without turning more than once per press.
            if (event.nativeKeyEvent.repeatCount != 0) return@onPreviewKeyEvent true
            val page = when (event.key) {
                Key.VolumeUp -> volumeKeyPage(volumeUp = true, rtl = rtlNow)
                Key.VolumeDown -> volumeKeyPage(volumeUp = false, rtl = rtlNow)
                else -> return@onPreviewKeyEvent false
            }
            turn(page)
            true
        }
        .focusRequester(focusRequester)
        .focusable()
}
