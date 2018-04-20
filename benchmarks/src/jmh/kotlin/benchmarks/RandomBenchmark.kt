package benchmarks

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.*
import java.util.concurrent.*


@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
open class RandomBenchmark {

    companion object {
        val MASKS = (0..1024).map { nextMask(it) }.toIntArray()

        fun nextMask(value: Int): Int {
            if (value == 0) return 0
            return (1 shl 32 - Integer.numberOfLeadingZeros(value - 1)) - 1
        }
    }

    @JvmField
    val iterations = 1024


    @State(Scope.Benchmark)
    open class RngState {
        @JvmField
        public var rngState: Int = ThreadLocalRandom.current().nextInt()
    }

    @Benchmark
    fun divRandom(state: RngState, bh: Blackhole) {
        for (i in 1 until iterations) {
            bh.consume(nextIntDiv(state, i))
        }
    }

    @Benchmark
    fun maskRandom(state: RngState, bh: Blackhole) {
        for (i in 1 until iterations) {
            bh.consume(nextIntMask(state, i))
        }
    }

    @Benchmark
    fun unfairMaskRandom(state: RngState, bh: Blackhole) {
        for (i in 1 until iterations) {
            bh.consume(nextUnfairIntMask(state, i))
        }
    }

    private fun nextIntDiv(s: RandomBenchmark.RngState, upperBound: Int): Int {
        var state = s.rngState
        state = state xor (state shl 13)
        state = state xor (state shr 17)
        state = state xor (state shl 5)
        s.rngState = state

        val mask = upperBound - 1
        if (mask and upperBound == 0) {
            return state and mask
        }

        // todo: consider dropping output of bound value & generating another one (until in bounds)
        // todo: reminder operation is very, very slow (can be slower than generating another random with xor/shift)
        // todo: needs benchmark
        return (state and Int.MAX_VALUE) % upperBound
    }

    private fun nextIntMask(s: RandomBenchmark.RngState, upperBound: Int): Int {
        val mask = MASKS[upperBound]

        while (true) {
            var state = s.rngState
            state = state xor (state shl 13)
            state = state xor (state shr 17)
            state = state xor (state shl 5)
            s.rngState = state

            val nextRandom = state and mask
            if (nextRandom < upperBound) {
                return nextRandom
            }
        }
    }

    private fun nextUnfairIntMask(s: RandomBenchmark.RngState, upperBound: Int): Int {
        val mask = MASKS[upperBound]

        var state = next(s)

        val nextRandom = state and mask
        if (nextRandom >= upperBound) {
            val next = next(s)
            if (next > upperBound) {
                return next - upperBound
            }
            return next
        }

        return nextRandom
    }

    private fun next(s: RngState): Int {
        var state = s.rngState
        state = state xor (state shl 13)
        state = state xor (state shr 17)
        state = state xor (state shl 5)
        s.rngState = state
        return state
    }

}

private fun nextInt(s: RandomBenchmark.RngState, upperBound: Int): Int {
    val mask = RandomBenchmark.MASKS[upperBound]

    var state = s.rngState
    state = state xor (state shl 13)
    state = state xor (state shr 17)
    state = state xor (state shl 5)
    s.rngState = state

    val nextRandom = state and mask
    if (nextRandom >= upperBound) {
        return nextRandom - upperBound
    }

    return nextRandom
}

fun main(args: Array<String>) {
    val st = RandomBenchmark.RngState()
    testUniformDistribution(st, 200)
}

private fun testUniformDistribution(st: RandomBenchmark.RngState, bound: Int) {
    val result = IntArray(bound)
    val iterations = 10_000_000
    repeat(iterations) {
        ++result[nextInt(st, bound)]
    }

    val bucketSize = iterations / bound
    for (v in result.withIndex()) {
        val ratio = v.value.toDouble() / bucketSize
        // 10% deviation
        if (ratio <= 1.1) println("Ratio: $ratio for ${v.index}")
        if (ratio >= 0.9) println("Ratio: $ratio for ${v.index}")
    }
}