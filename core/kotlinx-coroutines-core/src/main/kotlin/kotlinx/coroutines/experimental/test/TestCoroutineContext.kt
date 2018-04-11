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

import kotlinx.atomicfu.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.internal.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.experimental.*

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
    private val uncaughtExceptions = mutableListOf<Throwable>()

    private val ctxDispatcher = Dispatcher()
    
    private val ctxHandler = CoroutineExceptionHandler { _, exception ->
        uncaughtExceptions += exception
    }

    // The ordered queue for the runnable tasks.
    private val queue = ThreadSafeHeap<TimedRunnable>()

    // The per-scheduler global order counter.
    private val counter = atomic(0L)

    // Storing time in nanoseconds internally.
    private val time = atomic(0L)

    /**
     * Exceptions that were caught during a [launch] or a [async] + [Deferred.await].
     */
    public val exceptions: List<Throwable> get() = uncaughtExceptions

    // -- CoroutineContext implementation 

    public override fun <R> fold(initial: R, operation: (R, CoroutineContext.Element) -> R): R =
        operation(operation(initial, ctxDispatcher), ctxHandler)

    @Suppress("UNCHECKED_CAST")
    public override fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? = when {
        key === ContinuationInterceptor -> ctxDispatcher as E
        key === CoroutineExceptionHandler -> ctxHandler as E
        else -> null
    }

    public override fun minusKey(key: CoroutineContext.Key<*>): CoroutineContext = when {
        key === ContinuationInterceptor -> ctxHandler
        key === CoroutineExceptionHandler -> ctxDispatcher
        else -> this
    }
    
    /**
     * Returns the current virtual clock-time as it is known to this CoroutineContext.
     *
     * @param unit The [TimeUnit] in which the clock-time must be returned.
     * @return The virtual clock-time
     */
    public fun now(unit: TimeUnit = TimeUnit.MILLISECONDS)=
        unit.convert(time.value, TimeUnit.NANOSECONDS)

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
    public fun advanceTimeBy(delayTime: Long, unit: TimeUnit = TimeUnit.MILLISECONDS): Long {
        val oldTime = time.value
        advanceTimeTo(oldTime + unit.toNanos(delayTime), TimeUnit.NANOSECONDS)
        return unit.convert(time.value - oldTime, TimeUnit.NANOSECONDS)
    }

    /**
     * Moves the CoroutineContext's clock-time to a particular moment in time.
     *
     * @param targetTime The point in time to which to move the CoroutineContext's clock.
     * @param unit The [TimeUnit] in which [targetTime] is expressed.
     */
    fun advanceTimeTo(targetTime: Long, unit: TimeUnit = TimeUnit.MILLISECONDS) {
        val nanoTime = unit.toNanos(targetTime)
        triggerActions(nanoTime)
        if (nanoTime > time.value) time.value = nanoTime
    }

    /**
     * Triggers any actions that have not yet been triggered and that are scheduled to be triggered at or
     * before this CoroutineContext's present virtual clock-time.
     */
    public fun triggerActions() = triggerActions(time.value)

    /**
     * Cancels all not yet triggered actions. Be careful calling this, since it can seriously
     * mess with your coroutines work. This method should usually be called on tear-down of a
     * unit test.
     */
    public fun cancelAllActions() = queue.clear()

    private fun post(block: Runnable) =
        queue.addLast(TimedRunnable(block, counter.getAndIncrement()))

    private fun postDelayed(block: Runnable, delayTime: Long) =
        TimedRunnable(block, counter.getAndIncrement(), time.value + TimeUnit.MILLISECONDS.toNanos(delayTime))
            .also {
                queue.addLast(it)
            }

    private fun processNextEvent(): Long {
        val current = queue.peek()
        if (current != null) {
            /** Automatically advance time for [EventLoop]-callbacks */
            triggerActions(current.time)
        }
        return if (queue.isEmpty) Long.MAX_VALUE else 0L
    }

    private fun triggerActions(targetTime: Long) {
        while (true) {
            val current = queue.removeFirstIf { it.time <= targetTime } ?: break
            // If the scheduled time is 0 (immediate) use current virtual time
            if (current.time != 0L) time.value = current.time
            current.run()
        }
    }

    public override fun toString(): String = name ?: "TestCoroutineContext@$hexAddress"

    private inner class Dispatcher : CoroutineDispatcher(), Delay, EventLoop {
        override fun dispatch(context: CoroutineContext, block: Runnable) = post(block)

        override fun scheduleResumeAfterDelay(time: Long, unit: TimeUnit, continuation: CancellableContinuation<Unit>) {
            postDelayed(Runnable {
                with(continuation) { resumeUndispatched(Unit) }
            }, unit.toMillis(time))
        }

        override fun invokeOnTimeout(time: Long, unit: TimeUnit, block: Runnable): DisposableHandle {
            val node = postDelayed(block, unit.toMillis(time))
            return object : DisposableHandle {
                override fun dispose() {
                    queue.remove(node)
                }
            }
        }

        override fun processNextEvent() = this@TestCoroutineContext.processNextEvent()

        public override fun toString(): String = "Dispatcher(${this@TestCoroutineContext})"
    }
}

private class TimedRunnable(
    private val run: Runnable,
    private val count: Long = 0,
    @JvmField internal val time: Long = 0
) : Comparable<TimedRunnable>, Runnable by run, ThreadSafeHeapNode {
    override var index: Int = 0

    override fun run() = run.run()

    override fun compareTo(other: TimedRunnable) = if (time == other.time) {
        count.compareTo(other.count)
    } else {
        time.compareTo(other.time)
    }

    override fun toString() = "TimedRunnable(time=$time, run=$run)"
}