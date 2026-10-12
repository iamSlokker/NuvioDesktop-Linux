package com.nuvio.app.features.collection

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.testing.runHeadlessProbe
import com.nuvio.app.testing.withHeadlessFixture
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class LinuxFolderDetailConcurrencyTest {
    @Test fun concurrentTabCompletionsBothSurvive() = withHeadlessFixture {
        runHeadlessProbe(it, FolderDetailConcurrencyProbe::class.java, "race")
    }
    @Test fun allTabRebuildCannotOverwriteConcurrentSourceCompletion() = withHeadlessFixture {
        runHeadlessProbe(it, FolderDetailConcurrencyProbe::class.java, "all")
    }
    @Test fun staleTabIndexDoesNotReplaceCurrentState() = withHeadlessFixture {
        runHeadlessProbe(it, FolderDetailConcurrencyProbe::class.java, "stale")
    }
}

internal object FolderDetailConcurrencyProbe {
    @Suppress("UNCHECKED_CAST")
    @JvmStatic fun main(args: Array<String>) {
        // Exercise the real repository update, without adding a production test hook
        // or starting catalog requests. Each probe owns its isolated JVM/storage.
        val repository = FolderDetailRepository
        val field = repository.javaClass.getDeclaredField("_uiState").apply { isAccessible = true }
        val state = field.get(repository) as MutableStateFlow<FolderDetailUiState>
        val update = repository.javaClass.getDeclaredMethod("updateTab", Int::class.javaPrimitiveType,
            Function1::class.java).apply { isAccessible = true }
        val initial = FolderDetailUiState(tabs = listOf(FolderTab("one"), FolderTab("two")), selectedTabIndex = 1)
        state.value = initial
        if (args[1] == "stale") {
            val transform: (FolderTab) -> FolderTab = { error("Invalid index reached transform") }
            update.invoke(repository, 9, transform)
            assertSame(initial, state.value)
            return
        }
        if (args[1] == "all") {
            val rebuilding = CountDownLatch(1)
            val release = CountDownLatch(1)
            val worker = AtomicReference<Thread?>()
            val paused = AtomicBoolean()
            val oldItem = MetaPreview("old", "movie", "Old")
            val newItem = MetaPreview("new", "movie", "New")
            // Pause the actual round-robin merge after it has captured its input.
            // Other threads (including StateFlow equality checks) remain unblocked.
            val oldItems = object : AbstractList<MetaPreview>() {
                override val size = 1
                override fun get(index: Int): MetaPreview {
                    check(index == 0)
                    if (Thread.currentThread() == worker.get() && paused.compareAndSet(false, true)) {
                        rebuilding.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    return oldItem
                }
            }
            state.value = initial.copy(tabs = listOf(FolderTab("All", isAllTab = true),
                FolderTab("Source", items = oldItems)))
            val rebuild = repository.javaClass.getDeclaredMethod("rebuildAllTab").apply { isAccessible = true }
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit { worker.set(Thread.currentThread()); rebuild.invoke(repository) }
                assertTrue(rebuilding.await(5, TimeUnit.SECONDS))
                val transform: (FolderTab) -> FolderTab = { it.copy(items = listOf(newItem), isLoading = false) }
                update.invoke(repository, 1, transform)
                release.countDown()
                future.get(10, TimeUnit.SECONDS)
                assertEquals(listOf(newItem), state.value.tabs[1].items)
                assertEquals(listOf(newItem), state.value.tabs[0].items)
                assertFalse(state.value.tabs[0].isLoading)
            } finally {
                release.countDown()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
            return
        }
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (0..1).map { index -> executor.submit {
                val attempts = AtomicInteger()
                val transform: (FolderTab) -> FolderTab = {
                    if (attempts.incrementAndGet() == 1) barrier.await(5, TimeUnit.SECONDS)
                    it.copy(isLoading = false, error = "completed-$index")
                }
                update.invoke(repository, index, transform)
            } }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(listOf("completed-0", "completed-1"), state.value.tabs.map { it.error })
            assertTrue(state.value.tabs.none { it.isLoading })
            assertFalse(state.value.isLoading)
            assertEquals(1, state.value.selectedTabIndex)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
