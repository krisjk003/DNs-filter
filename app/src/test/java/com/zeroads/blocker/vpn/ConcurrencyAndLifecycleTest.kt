package com.zeroads.blocker.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ConcurrencyAndLifecycleTest {

    @Test
    fun testSimultaneousStatisticsUpdates() {
        val statsFlow = MutableStateFlow(VpnStats())
        val threadCount = 10
        val incrementsPerThread = 1000
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        for (i in 0 until threadCount) {
            executor.execute {
                for (j in 0 until incrementsPerThread) {
                    statsFlow.update { current ->
                        current.copy(
                            totalRequests = current.totalRequests + 1,
                            blockedRequests = current.blockedRequests + 1
                        )
                    }
                }
                latch.countDown()
            }
        }

        latch.await(5, TimeUnit.SECONDS)
        executor.shutdown()

        val finalStats = statsFlow.value
        assertEquals((threadCount * incrementsPerThread).toLong(), finalStats.totalRequests)
        assertEquals((threadCount * incrementsPerThread).toLong(), finalStats.blockedRequests)
    }

    @Test
    fun testBoundedQueueBehaviorAndRejection() {
        val queueCapacity = 5
        val queue = ArrayBlockingQueue<Runnable>(queueCapacity)
        val rejectedCount = AtomicInteger(0)

        val executor = ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, queue
        ) { _, _ ->
            rejectedCount.incrementAndGet()
        }

        val latch = CountDownLatch(1)

        // First task blocks the single thread
        executor.execute {
            latch.await(2, TimeUnit.SECONDS)
        }

        // Fill up queue
        for (i in 0 until queueCapacity) {
            executor.execute { }
        }

        // Next executions should be rejected
        for (i in 0 until 3) {
            executor.execute { }
        }

        assertEquals(3, rejectedCount.get())

        latch.countDown()
        executor.shutdown()
    }

    @Test
    fun testGracefulExecutorShutdown() {
        val executor = Executors.newFixedThreadPool(2)
        val taskCompleted = AtomicInteger(0)

        executor.execute {
            taskCompleted.incrementAndGet()
        }

        executor.shutdown()
        val terminated = executor.awaitTermination(2, TimeUnit.SECONDS)

        assertTrue(terminated)
        assertEquals(1, taskCompleted.get())
    }
}