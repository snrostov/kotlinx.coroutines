package kotlinx.coroutines.experimental.channels

import kotlinx.coroutines.experimental.*
import org.junit.*

class RandevouzChannelStressTest : TestBase() {

    @Test
    fun testStress() = runTest {
        val n = 100_000
        val q = RendezvousChannel<Int>()
        val sender = launch(kotlin.coroutines.experimental.coroutineContext) {
            for (i in 1..n) q.send(i)
            expect(2)
        }
        val receiver = launch(kotlin.coroutines.experimental.coroutineContext) {
            for (i in 1..n) check(q.receive() == i)
            expect(3)
        }
        expect(1)
        sender.join()
        receiver.join()
        finish(4)
    }
}