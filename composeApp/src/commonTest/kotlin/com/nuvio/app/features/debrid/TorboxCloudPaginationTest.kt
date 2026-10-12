package com.nuvio.app.features.debrid

import com.nuvio.app.features.addons.RAW_HTTP_TRUNCATION_MARKER
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

class TorboxCloudPaginationTest {
    private fun page(ids: IntRange, status: Int = 200, success: Boolean = true) =
        DebridApiResponse(status, TorboxEnvelopeDto(success = success,
            data = ids.map { TorboxCloudItemDto(id = JsonPrimitive(it), name = "Item $it") }), "fixture")

    @Test fun aggregatesEveryPageInOrder() = runBlocking {
        val calls = mutableListOf<Pair<Int, Int>>()
        val result = fetchTorboxCloudPages { offset, limit ->
            calls += offset to limit
            page(offset until minOf(offset + limit, 215))
        }
        assertEquals(listOf(0 to 100, 100 to 100, 200 to 100), calls)
        assertEquals((0 until 215).map { JsonPrimitive(it) }, result.body!!.data!!.map { it.id })
        assertEquals("", result.rawBody)
    }

    @Test fun exactPageBoundaryFetchesEmptyFinalPage() = runBlocking {
        val calls = mutableListOf<Int>()
        val result = fetchTorboxCloudPages { offset, limit ->
            calls += offset
            page(offset until minOf(offset + limit, 100))
        }
        assertEquals(listOf(0, 100), calls)
        assertEquals(100, result.body!!.data!!.size)
    }

    @Test fun truncationRetriesSameOffsetWithSmallerPage() = runBlocking {
        val calls = mutableListOf<Pair<Int, Int>>()
        val result = fetchTorboxCloudPages { offset, limit ->
            calls += offset to limit
            if (limit > 25) page(1..0).copy(rawBody = "partial\n$RAW_HTTP_TRUNCATION_MARKER", body = null)
            else page(offset until minOf(offset + limit, 30))
        }
        assertEquals(listOf(0 to 100, 0 to 50, 0 to 25, 25 to 25), calls)
        assertEquals(30, result.body!!.data!!.size)
    }

    @Test fun singleOversizedItemFailsAfterBoundedRetries() = runBlocking {
        var calls = 0
        assertFailsWith<IllegalStateException> {
            fetchTorboxCloudPages {
                _, _ -> calls++
                page(1..0).copy(body = null, rawBody = "partial\n$RAW_HTTP_TRUNCATION_MARKER")
            }
        }
        assertEquals(7, calls) // 100, 50, 25, 12, 6, 3, 1
    }

    @Test fun apiAndDecodeFailuresDoNotPublishPartialSuccess() = runBlocking {
        val failures = listOf(page(1..0, status = 429), page(1..0, success = false), page(1..0).copy(body = null))
        for (failure in failures) {
            var calls = 0
            val result = fetchTorboxCloudPages { _, _ -> if (++calls == 1) page(0..99) else failure }
            assertEquals(failure, result)
            assertEquals(2, calls)
        }
    }

    @Test fun cancellationIsNotRetriedOrConvertedToSuccess() = runBlocking {
        var calls = 0
        assertFailsWith<CancellationException> {
            fetchTorboxCloudPages { _, _ -> calls++; throw CancellationException("fixture") }
        }
        assertEquals(1, calls)
    }

    @Test fun repeatedPageCannotLoopForeverWhenServerIgnoresOffset() = runBlocking {
        var calls = 0
        assertFailsWith<IllegalStateException> {
            fetchTorboxCloudPages { _, _ -> calls++; page(0..99) }
        }
        assertEquals(2, calls)
    }

    @Test fun emptyCloudIsSuccessful() = runBlocking {
        var calls = 0
        val result = fetchTorboxCloudPages { _, _ -> calls++; page(1..0) }
        assertEquals(1, calls)
        assertTrue(result.body!!.data!!.isEmpty())
    }
}
