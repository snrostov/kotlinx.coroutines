/*
 * Copyright 2016-2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kotlinx.coroutines.experimental.test

import kotlinx.coroutines.experimental.*
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.experimental.CoroutineContext

private const val MAX_DELAY = Long.MAX_VALUE - 1

/**
 * This [CoroutineContext] dispatcher can be used to simulate virtual time to speed up
 * code, especially tests, that deal with delays and timeouts in Coroutines.
 *
 * Provide an instance of this TestCoroutineContext when calling the *non-blocking* [launch] or [async]
 * and then advance time or trigger the actions to make the co-routines execute as soon as possible.
 *
 * This works much like the *TestScheduler* in RxJava2, which allows to speed up tests that deal
 * with non-blocking Rx chains that contain delays, timeouts, intervals and such.
 *
 * This dispatcher can also handle *blocking* coroutines that are started by [runBlocking].
 * This dispatcher's virtual time will be automatically advanced based based on the delayed actions
 * within the Coroutine(s).
 *
 * @param name A user-readable name for debugging purposes.
 */
class TestCoroutineContext(private val name: String? = null) : CoroutineContext {
    private val caughtExceptions = mutableListOf<Throwable>()

    private val context = Dispatcher() + CoroutineExceptionHandler(this::handleException)

    private val handler = TestHandler()

    /**
     * Exceptions that were caught during a [launch] or a [async] + [Deferred.await].
     */
    val exceptions: List<Throwable> get() = caughtExceptions

    override fun <R> fold(initial: R, operation: (R, CoroutineContext.Element) -> R): R =
            context.fold(initial, operation)

    override fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? = context[key]

    override fun minusKey(key: CoroutineContext.Key<*>): CoroutineContext = context.minusKey(key)

    /**
     * Returns the current virtual clock-time as it is known to this CoroutineContext.
     *
     * @param unit The [TimeUnit] in which the clock-time must be returned.
     * @return The virtual clock-time
     */
    fun now(unit: TimeUnit = TimeUnit.MILLISECONDS): Long = handler.now(unit)

    /**
     * Moves the CoroutineContext's virtual clock forward by a specified amount of time.
     *
     * The returned delay-time can be larger than the specified delay-time if the code
     * under test contains *blocking* Coroutines.
     *
     * @param delayTime The amount of time to move the CoroutineContext's clock forward.
     * @param unit The [TimeUnit] in which [delayTime] and the return value is expressed.
     * @return The amount of delay-time that this CoroutinesContext's clock has been forwarded.
     */
    fun advanceTimeBy(delayTime: Long, unit: TimeUnit = TimeUnit.MILLISECONDS) =
            handler.advanceTimeBy(delayTime, unit)

    /**
     * Moves the CoroutineContext's clock-time to a particular moment in time.
     *
     * @param targetTime The point in time to which to move the CoroutineContext's clock.
     * @param unit The [TimeUnit] in which [targetTime] is expressed.
     */
    fun advanceTimeTo(targetTime: Long, unit: TimeUnit = TimeUnit.MILLISECONDS) {
        handler.advanceTimeTo(targetTime, unit)
    }

    /**
     * Triggers any actions that have not yet been triggered and that are scheduled to be triggered at or
     * before this CoroutineContext's present virtual clock-time.
     */
    fun triggerActions() {
        handler.triggerActions()
    }

    /**
     * Cancels all not yet triggered actions. Be careful calling this, since it can seriously
     * mess with your coroutines work. This method should usually be called on tear-down of a
     * unit test.
     */
    fun cancelAllActions() {
        handler.cancelAllActions()
    }

    override fun toString(): String = name ?: super.toString()

    override fun equals(other: Any?): Boolean = (other is TestCoroutineContext) && (other.handler === handler)

    override fun hashCode(): Int = System.identityHashCode(handler)

    private fun handleException(@Suppress("UNUSED_PARAMETER") context: CoroutineContext, exception: Throwable) {
        caughtExceptions += exception
    }

    private inner class Dispatcher : CoroutineDispatcher(), Delay, EventLoop {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            handler.post(block)
        }

        override fun scheduleResumeAfterDelay(time: Long, unit: TimeUnit, continuation: CancellableContinuation<Unit>) {
            handler.postDelayed(Runnable {
                with(continuation) { resumeUndispatched(Unit) }
            }, unit.toMillis(time).coerceAtMost(MAX_DELAY))
        }

        override fun invokeOnTimeout(time: Long, unit: TimeUnit, block: Runnable): DisposableHandle {
            handler.postDelayed(block, unit.toMillis(time).coerceAtMost(MAX_DELAY))
            return object : DisposableHandle {
                override fun dispose() {
                    handler.removeCallbacks(block)
                }
            }
        }

        override fun processNextEvent() = handler.processNextEvent()
    }
}

private class TestHandler {
    // The ordered queue for the runnable tasks.
    private val queue = PriorityBlockingQueue<TimedRunnable>(16)

    // The per-scheduler global order counter.
    private var counter = AtomicLong(0L)

    // Storing time in nanoseconds internally.
    private var time = AtomicLong(0L)

    private val nextEventTime get() = if (queue.isEmpty()) Long.MAX_VALUE else 0L

    internal fun post(block: Runnable) {
        queue.add(TimedRunnable(block, counter.getAndIncrement()))
    }

    internal fun postDelayed(block: Runnable, delayTime: Long) {
        queue.add(TimedRunnable(block, counter.getAndIncrement(), time.get() + TimeUnit.MILLISECONDS.toNanos(delayTime)))
    }

    internal fun removeCallbacks(block: Runnable) {
        queue.remove(TimedRunnable(block))
    }

    internal fun now(unit: TimeUnit) = unit.convert(time.get(), TimeUnit.NANOSECONDS)

    internal fun advanceTimeBy(delayTime: Long, unit: TimeUnit): Long {
        val oldTime = time.get()

        advanceTimeTo(oldTime + unit.toNanos(delayTime), TimeUnit.NANOSECONDS)

        return unit.convert(time.get() - oldTime, TimeUnit.NANOSECONDS)
    }

    internal fun advanceTimeTo(targetTime: Long, unit: TimeUnit) {
        val nanoTime = unit.toNanos(targetTime)

        triggerActions(nanoTime)

        if (nanoTime > time.get()) {
            time.set(nanoTime)
        }
    }

    internal fun triggerActions() {
        triggerActions(time.get())
    }

    internal fun cancelAllActions() {
        queue.clear()
    }

    internal fun processNextEvent(): Long {
        val current = queue.peek()
        if (current != null) {
            /** Automatically advance time for [EventLoop]-callbacks */
            triggerActions(current.time)
        }

        return nextEventTime
    }

    private fun triggerActions(targetTime: Long) {
        while (true) {
            val current = queue.peek()
            if (current == null || current.time > targetTime) {
                break
            }

            // If the scheduled time is 0 (immediate) use current virtual time
            if (current.time != 0L) {
                time.set(current.time)
            }

            queue.remove(current)
            current.run()
        }
    }
}

private class TimedRunnable(
        private val run: Runnable,
        private val count: Long = 0,
        internal val time: Long = 0
) : Comparable<TimedRunnable>, Runnable {
    override fun run() {
        run.run()
    }

    override fun compareTo(other: TimedRunnable) = if (time == other.time) {
        count.compareTo(other.count)
    } else {
        time.compareTo(other.time)
    }

    override fun hashCode() = run.hashCode()

    override fun equals(other: Any?) = other is TimedRunnable && (run == other.run)

    override fun toString() = String.format("TimedRunnable(time = %d, run = %s)", time, run.toString())
}