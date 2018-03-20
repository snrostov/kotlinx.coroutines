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
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class TestCoroutineContextTest {
    private val context = TestCoroutineContext()

    @After
    fun tearDown() {
        context.cancelAllActions()
    }

    @Test
    fun testDelayWithLaunch() {
        val delay = 1000L

        var executed = false
        launch(context) {
            suspendedDelayedAction(delay) {
                executed = true
            }
        }

        context.advanceTimeBy(delay / 2)
        assertFalse(executed)

        context.advanceTimeBy(delay / 2)
        assertTrue(executed)
    }


    @Test
    fun testTimeJumpWithLaunch() {
        val delay = 1000L

        var executed = false
        launch(context) {
            suspendedDelayedAction(delay) {
                executed = true
            }
        }

        context.advanceTimeTo(delay / 2)
        assertFalse(executed)

        context.advanceTimeTo(delay)
        assertTrue(executed)
    }

    @Test
    fun testDelayWithAsync() {
        val delay = 1000L

        var executed = false
        async(context) {
            suspendedDelayedAction(delay) {
                executed = true
            }
        }

        context.advanceTimeBy(delay / 2)
        assertFalse(executed)

        context.advanceTimeBy(delay / 2)
        assertTrue(executed)
    }

    @Test
    fun testDelayWithRunBlocking() {
        val delay = 1000L

        var executed = false
        runBlocking(context) {
            suspendedDelayedAction(delay) {
                executed = true
            }
        }

        assertTrue(executed)
        assertEquals(delay, context.now())
    }

    private suspend fun suspendedDelayedAction(delay: Long, action: () -> Unit) {
        delay(delay)
        action()
    }

    @Test
    fun testDelayedFunctionWithRunBlocking() {
        val delay = 1000L
        val expectedValue = 16

        val result = runBlocking(context) {
            suspendedDelayedFunction(delay) {
                expectedValue
            }
        }

        assertEquals(expectedValue, result)
        assertEquals(delay, context.now())
    }

    @Test
    fun testDelayedFunctionWithAsync() {
        val delay = 1000L
        val expectedValue = 16

        val deferred = async(context) {
            suspendedDelayedFunction(delay) {
                expectedValue
            }
        }

        context.advanceTimeBy(delay / 2)
        try {
            deferred.getCompleted()
            fail("The Job should not have been completed yet.")
        } catch (e: Exception) {
            // Success.
        }

        context.advanceTimeBy(delay / 2)
        assertEquals(expectedValue, deferred.getCompleted())
    }

    private suspend fun <T> suspendedDelayedFunction(delay: Long, function: () -> T): T {
        delay(delay / 4)
        return async(context) {
            delay((delay / 4) * 3)
            function()
        }.await()
    }

    @Test
    fun testBlockingFunctionWithRunBlocking() {
        val delay = 1000L
        val expectedValue = 16

        val result = runBlocking(context) {
            suspendedBlockingFunction(delay) {
                expectedValue
            }
        }

        assertEquals(expectedValue, result)
        assertEquals(delay, context.now())
    }

    @Test
    fun testBlockingFunctionWithAsync() {
        val delay = 1000L
        val expectedValue = 16
        var now = 0L

        val deferred = async(context) {
            suspendedBlockingFunction(delay) {
                expectedValue
            }
        }

        now += context.advanceTimeBy((delay / 4) - 1)
        assertEquals((delay / 4) - 1, now)
        assertEquals(now, context.now())
        try {
            deferred.getCompleted()
            fail("The Job should not have been completed yet.")
        } catch (e: Exception) {
            // Success.
        }

        now += context.advanceTimeBy(1)
        assertEquals(delay, context.now())
        assertEquals(now, context.now())
        assertEquals(expectedValue, deferred.getCompleted())
    }

    private suspend fun <T> suspendedBlockingFunction(delay: Long, function: () -> T): T {
        delay(delay / 4)
        return runBlocking(context) {
            delay((delay / 4) * 3)
            function()
        }
    }

    @Test
    fun testTimingOutFunctionWithAsyncAndNoTimeout() {
        val delay = 1000L
        val expectedValue = 67

        val result = async(context) {
            suspendedTimingOutFunction(delay, delay + 1) {
                expectedValue
            }
        }

        context.triggerActions()
        assertEquals(expectedValue, result.getCompleted())
    }

    @Test
    fun testTimingOutFunctionWithAsyncAndTimeout() {
        val delay = 1000L
        val expectedValue = 67

        val result = async(context) {
            suspendedTimingOutFunction(delay, delay) {
                expectedValue
            }
        }

        context.triggerActions()
        assertTrue(result.getCompletionExceptionOrNull() is TimeoutCancellationException)
    }

    @Test
    fun testTimingOutFunctionWithRunBlockingAndTimeout() {
        val delay = 1000L
        val expectedValue = 67

        try {
            runBlocking(context) {
                suspendedTimingOutFunction(delay, delay) {
                    expectedValue
                }
            }
            fail("Expected TimeoutCancellationException to be thrown.")
        } catch (e: TimeoutCancellationException) {
            // Success
        } catch (e: Throwable) {
            fail("Expected TimeoutCancellationException to be thrown: $e")
        }
    }

    private suspend fun <T> suspendedTimingOutFunction(delay: Long, timeOut: Long, function: () -> T): T {
        return runBlocking(context) {
            withTimeout(timeOut) {
                delay(delay / 2)
                val ret = function()
                delay(delay / 2)
                ret
            }
        }
    }

    @Test
    fun testExceptionHandlingWithLaunch() {
        val expectedError = IllegalAccessError("hello")

        launch(context) {
            throw expectedError
        }

        context.triggerActions()
        assertTrue(expectedError === context.exceptions[0])
    }

    @Test
    fun testExceptionHandlingWithLaunchingChildCoroutines() {
        val delay = 1000L
        val expectedError = IllegalAccessError("hello")
        val expectedValue = 12

        launch(context) {
            suspendedAsyncWithExceptionAfterDelay(delay, expectedError, expectedValue, true)
        }

        context.advanceTimeBy(delay)
        assertTrue(expectedError === context.exceptions[0])
    }

    @Test
    fun testExceptionHandlingWithAsyncAndDontWaitForException() {
        val delay = 1000L
        val expectedError = IllegalAccessError("hello")
        val expectedValue = 12

        val result = async(context) {
            suspendedAsyncWithExceptionAfterDelay(delay, expectedError, expectedValue, false)
        }

        context.advanceTimeBy(delay)

        assertNull(result.getCompletionExceptionOrNull())
        assertEquals(expectedValue, result.getCompleted())
    }

    @Test
    fun testExceptionHandlingWithAsyncAndWaitForException() {
        val delay = 1000L
        val expectedError = IllegalAccessError("hello")
        val expectedValue = 12

        val result = async(context) {
            suspendedAsyncWithExceptionAfterDelay(delay, expectedError, expectedValue, true)
        }

        context.advanceTimeBy(delay)

        val e = result.getCompletionExceptionOrNull()
        assertTrue("Expected to be thrown: '$expectedError' but was '$e'", expectedError === e)
    }

    @Test
    fun testExceptionHandlingWithRunBlockingAndDontWaitForException() {
        val delay = 1000L
        val expectedError = IllegalAccessError("hello")
        val expectedValue = 12

        val result = runBlocking(context) {
            suspendedAsyncWithExceptionAfterDelay(delay, expectedError, expectedValue, false)
        }

        context.advanceTimeBy(delay)

        assertEquals(expectedValue, result)
    }

    @Test
    fun testExceptionHandlingWithRunBlockingAndWaitForException() {
        val delay = 1000L
        val expectedError = IllegalAccessError("hello")
        val expectedValue = 12

        try {
            runBlocking(context) {
                suspendedAsyncWithExceptionAfterDelay(delay, expectedError, expectedValue, true)
            }
            fail("Expected to be thrown: '$expectedError'")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Throwable) {
            assertTrue("Expected to be thrown: '$expectedError' but was '$e'", expectedError === e)
        }
    }

    private suspend fun <T> suspendedAsyncWithExceptionAfterDelay(delay: Long, exception: Throwable, value: T, await: Boolean): T {
        val deferred = async(context) {
            delay(delay - 1)
            throw exception
        }

        if (await) {
            deferred.await()
        }
        return value
    }
}