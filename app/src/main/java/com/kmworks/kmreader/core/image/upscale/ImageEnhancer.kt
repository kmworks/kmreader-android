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

    @Volatile
    private var generation = 0

    @Volatile
    private var consecutiveFailures = 0

    @Volatile
    var isDisabled: Boolean = false
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
                signal.trySend(Unit)
            }
        }
        return deferred.await() as T?
    }

    /** Invalidates every queued task (chapter switch / leaving the reader) and re-arms the breaker. */
    fun nextGeneration() {
        lock.withLock {
            generation++
            consecutiveFailures = 0
            isDisabled = false
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

    private fun pickNext(): Task? {
        lock.withLock {
            while (true) {
                val task = queue.poll() ?: return null
                if (task.generation != generation) {
                    taskByKey.remove(task.key)
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
            val result = try {
                task.block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "enhancement failed: ${task.key}", e)
                AppLog.w(EnhancementDecoder.APP_TAG, "enhancement failed: ${e::class.simpleName}: ${e.message}")
                null
            }
            lock.withLock {
                taskByKey.remove(task.key)
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
                    }
                    task.deferred.complete(result)
                }
            }
        }
    }
}
