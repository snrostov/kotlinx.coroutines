/*
 * Copyright 2016-2017 JetBrains s.r.o.
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

package kotlinx.coroutines.experimental

import kotlin.coroutines.experimental.*

@Suppress("EXPECTED_DECLARATION_WITH_DEFAULT_PARAMETER")
public expect fun newCoroutineContext(context: CoroutineContext, parent: Job?): CoroutineContext

@Suppress("PropertyName")
public expect val DefaultDispatcher: CoroutineDispatcher

@Suppress("PropertyName")
internal expect val DefaultDelay: Delay

internal expect inline fun <T> withCoroutineContext(context: CoroutineContext, block: () -> T): T
internal expect fun Continuation<*>.toDebugString(): String
internal expect val CoroutineContext.coroutineName: String?