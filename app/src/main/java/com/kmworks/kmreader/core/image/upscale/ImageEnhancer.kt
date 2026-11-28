package com.kmworks.kmreader.core.image.upscale

import android.util.Log
import com.kmworks.kmreader.core.logging.AppLog
import java.util.PriorityQueue
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Priority queue for waifu2x enhancement tasks (docs/waifu2x-upscaling-plan.md §7, mirroring
 * mihon's ImageEnhancer):
 *
 * - priorities: [PRIORITY_VISIBLE] > [PRIORITY_ADJACENT] > [PRIORITY_ELEVATED] > [PRIORITY_PRELOAD];
 *   within one priority, smaller distance to the target page wins, then FIFO
 * - duplicate keys share one queued task; a more urgent request for a queued key boosts its
 *   priority (mihon's preemptForVisibleRequest)
 * - [nextGeneration] drops every queued task of the previous reading session (chapter switch /
 *   leaving the reader); an in-flight native task cannot be cancelled, so its result is discarded
 * - circuit breaker: [MAX_CONSECUTIVE_FAILURES] failures in a row (e.g. broken Vulkan driver)
 *   disable enhancement until the next generation instead of stalling page loads
 */
@Singleton
class ImageEnhancer @Inject constructor(
    private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "ImageEnhancer"

        const val PRIORITY_PRELOAD = 0
        const val PRIORITY_ELEVATED = 1
        const val PRIORITY_ADJACENT = 2
        const val PRIORITY_VISIBLE = 3

        private const val MAX_CONSECUTIVE_FAILURES = 3

        /**
         * Hard ceiling for queued native work. Preload tasks are dropped once the queue is full;
         * visible requests still get in and can boost an existing preload of the same page. Without
         * this a fast settled-index stream can enqueue more large source bitmaps than the single
         * worker can drain, turning a short prefetch burst into sustained memory/GPU pressure.
         */
        private const val MAX_PENDING_TASKS = 6

        /**
         * A page that takes this long is beyond what prefetch can hide. Stop admitting background
         * enhancements for the rest of the reading session; the visible page still runs, but the
         * queue cannot build up a multi-minute backlog on a slow GPU.
         */
        private const val SLOW_TASK_MILLIS = 5_000L

        /** A later quick page proves the slow run was an outlier, so background work may resume. */
        private const val FAST_RECOVERY_MILLIS = 1_500L
    }

    private class Task(
        val key: String,
        var priority: Int,
        val distance: Int,
        val seq: Long,
        val generation: Int,
        val deferred: CompletableDeferred<Any?>,
        val block: suspend () -> Any?,
    ) {
        var started: Boolean = false
    }

    private val comparator = compareByDescending<Task> { it.priority }
        .thenBy { it.distance }
        .thenBy { it.seq }
    private val queue = PriorityQueue(comparator)
    private val taskByKey = HashMap<String, Task>()
    private val lock = ReentrantLock()
    private val signal = Channel<Unit>(Channel.CONFLATED)

    private val _readyKeys = MutableStateFlow<Set<String>>(emptySet())

    /**
     * EnhancementCache keys that finished since the reader opened. The reader watches this to swap
     * a page from the already-shown source bitmap to the enhanced result (plan §5.4, item 3),
     * instead of blocking the first frame on a ~1 s native run.
     */
    val readyKeys: StateFlow<Set<String>> = _readyKeys.asStateFlow()

    private val _processingKeys = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Keys queued or currently inside the native engine. The reader uses this for the per-page
     * "2x" badge: it appears as soon as the request is queued, pulses while the tile loop runs, and
     * disappears when the result lands in [readyKeys] (or fails/cancels).
     */
    val processingKeys: StateFlow<Set<String>> = _processingKeys.asStateFlow()

    @Volatile
    private var generation = 0

    @Volatile
    private var consecutiveFailures = 0

    @Volatile
    var isDisabled: Boolean = false
        private set

    /** True after a page took long enough that preloading would only create a backlog. */
    @Volatile
    var isSlow: Boolean = false
        private set

    private var nextSeq = 0L

    init {
        scope.launch(Dispatchers.Default) { workerLoop() }
    }

    /**
     * Runs [block] through the queue and returns its result. Returns null when enhancement is
     * circuit-broken, when the task's generation was superseded while queued, or when [block]
     * itself failed — callers must always have a non-enhanced fallback ready.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> submit(
        key: String,
        priority: Int,
        distance: Int = 0,
        block: suspend () -> T?,
    ): T? {
        if (isDisabled) return null
        val gen = generation
        val deferred: CompletableDeferred<Any?>
        lock.withLock {
            val existing = taskByKey[key]
            if (existing == null && priority == PRIORITY_PRELOAD &&
                (isSlow || taskByKey.size >= MAX_PENDING_TASKS)
            ) {
                return null
            }
            if (existing != null) {
                // Boost a still-queued task when a more urgent request arrives for the same page.
                if (!existing.started && priority > existing.priority) {
                    queue.remove(existing)
                    existing.priority = priority
                    queue.add(existing)
                }
                deferred = existing.deferred
            } else {
                deferred = CompletableDeferred()
                val task = Task(key, priority, distance.coerceAtLeast(0), nextSeq++, gen, deferred) { block() }
                taskByKey[key] = task
                queue.add(task)
                _processingKeys.update { it + key }
                signal.trySend(Unit)
            }
        }
        return deferred.await() as T?
    }

    /** Park/resume native tile submission while the reader is being touched. */
    fun setPaused(paused: Boolean) = Waifu2xBridge.setPaused(paused)

    /** Invalidates every queued task (chapter switch / leaving the reader) and re-arms the breaker. */
    fun nextGeneration() {
        lock.withLock {
            generation++
            // Also stop the page already inside nativeProcess; otherwise the queue is empty but the
            // GPU stays busy for the rest of that 10-15 second page.
            Waifu2xBridge.cancelInFlight()
            consecutiveFailures = 0
            isDisabled = false
            isSlow = false
            _readyKeys.value = emptySet()
            _processingKeys.value = emptySet()
            val iterator = queue.iterator()
            while (iterator.hasNext()) {
                val task = iterator.next()
                if (task.generation != generation) {
                    iterator.remove()
                    taskByKey.remove(task.key)
                    task.deferred.complete(null)
                }
            }
        }
    }

    /**
     * Fire-and-forget variant of [submit]: queues the work and returns immediately, so the caller
     * can show the source image and let [readyKeys] trigger the swap once the result is cached.
     */
    fun prefetch(key: String, priority: Int, distance: Int = 0, block: suspend () -> Any?) {
        scope.launch { submit(key, priority, distance, block) }
    }

    private fun pickNext(): Task? {
        lock.withLock {
            while (true) {
                val task = queue.poll() ?: return null
                if (task.generation != generation) {
                    taskByKey.remove(task.key)
                    _processingKeys.update { it - task.key }
                    task.deferred.complete(null)
                    continue
                }
                task.started = true
                return task
            }
        }
    }

    private suspend fun workerLoop() {
        while (true) {
            val task = pickNext() ?: run {
                signal.receive()
                continue
            }
            val startedNs = System.nanoTime()
            val result = try {
                task.block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "enhancement failed: ${task.key}", e)
                AppLog.w(EnhancementDecoder.APP_TAG, "enhancement failed: ${e::class.simpleName}: ${e.message}")
                null
            }
            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
            lock.withLock {
                taskByKey.remove(task.key)
                _processingKeys.update { it - task.key }
                if (task.generation != generation) {
                    // Superseded while running: the result belongs to the old reading session.
                    task.deferred.complete(null)
                } else {
                    if (result == null) {
                        consecutiveFailures++
                        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES && !isDisabled) {
                            isDisabled = true
                            Log.e(TAG, "circuit breaker: $MAX_CONSECUTIVE_FAILURES consecutive failures, enhancement disabled until next generation")
                            AppLog.e(
                                EnhancementDecoder.APP_TAG,
                                "circuit breaker: $MAX_CONSECUTIVE_FAILURES consecutive failures, upscaling paused until the reader is reopened",
                            )
                        }
                    } else {
                        consecutiveFailures = 0
                        if (elapsedMs >= SLOW_TASK_MILLIS) {
                            isSlow = true
                            Log.i(TAG, "enhancement took ${elapsedMs}ms; pausing background prefetch")
                        } else if (elapsedMs <= FAST_RECOVERY_MILLIS) {
                            isSlow = false
                        }
                        // Keep every completed key for this reader generation. Trimming this set would
                        // make a still-visible page silently lose its ready flag (and stay on the
                        // source bitmap) once enough later pages finish. Generation changes clear it.
                        _readyKeys.update { it + task.key }
                    }
                    task.deferred.complete(result)
                }
            }
        }
    }
}
