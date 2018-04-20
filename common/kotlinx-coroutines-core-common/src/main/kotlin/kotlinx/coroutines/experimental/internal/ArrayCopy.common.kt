package kotlinx.coroutines.experimental.internal

/**
 * Cross-platfrom array copy. Overlapping of source and destination is not supported
 */
expect fun <E> arraycopy(source: Array<E>, srcPos: Int, destination: Array<E?>, destinationStart: Int, length: Int)