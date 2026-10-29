package com.nuvio.app.features.mdblist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MdbListLibraryRepositoryTest {
    @Test
    fun `combined watchlist payload maps movies and shows`() {
        val items = MdbListLibraryRepository.decodeWatchlistForTest(
            """
            {
              "movies": [{
                "title": "Arrival",
                "release_year": 2016,
                "ids": {"imdb": "tt2543164", "tmdb": 329865},
                "poster": "https://example.test/arrival.jpg",
                "genres": ["Drama", "Science Fiction"]
              }],
              "shows": [{
                "title": "Severance",
                "mediatype": "show",
                "ids": {"tmdb": 95396, "tvdb": 371980},
                "poster": {"url": "https://example.test/severance.jpg"}
              }]
            }
            """.trimIndent(),
        )

        assertEquals(listOf("movie", "series"), items.map { it.type })
        assertEquals("tt2543164", items[0].id)
        assertEquals("tmdb:95396", items[1].id)
        assertEquals("https://example.test/severance.jpg", items[1].poster)
    }

    @Test
    fun `added dates are read from MDBList date strings`() {
        val items = MdbListLibraryRepository.decodeListPageForTest(
            """
            [
              {"id": 1, "title": "A", "mediatype": "movie", "listed_at": "2024-05-01T12:00:00Z"},
              {"id": 2, "title": "B", "mediatype": "movie", "added_at": "2024-05-02T12:00:00.123456+00:00"},
              {"id": 3, "title": "C", "mediatype": "show", "watchlist_at": "2024-05-03 08:30:00"},
              {"id": 4, "title": "D", "mediatype": "movie", "listed_at": "2024-05-04"},
              {"id": 5, "title": "E", "mediatype": "movie", "added_at": 1714824000},
              {"id": 6, "title": "F", "mediatype": "movie", "added_at": "not a date"}
            ]
            """.trimIndent(),
        )

        assertEquals(
            listOf(1714564800000L, 1714651200123L, 1714725000000L, 1714780800000L, 1714824000000L, 0L),
            items.map { it.savedAtEpochMs },
        )
    }

    @Test
    fun `watchlist is ranked newest first whichever way MDBList returns it`() {
        val oldestFirst = MdbListLibraryRepository.decodeWatchlistForTest(
            """
            {"items": [
              {"id": "m1", "title": "Old", "mediatype": "movie", "ids": {"imdb": "tt1"}, "watchlist_at": "2023-01-01T00:00:00Z"},
              {"id": "m2", "title": "Mid", "mediatype": "show", "ids": {"imdb": "tt2"}, "watchlist_at": "2024-01-01T00:00:00Z"},
              {"id": "m3", "title": "New", "mediatype": "movie", "ids": {"imdb": "tt3"}, "watchlist_at": "2025-01-01T00:00:00Z"}
            ]}
            """.trimIndent(),
        )
        val ordered = MdbListLibraryRepository.orderWatchlist(oldestFirst, fetchedAt = 2_000_000_000_000L)

        assertEquals(listOf("New", "Mid", "Old"), ordered.map { it.name })
        assertEquals(listOf(1, 2, 3), ordered.map { it.traktRank })
    }

    @Test
    fun `undated watchlist keeps the API order for recently added`() {
        val items = MdbListLibraryRepository.decodeWatchlistForTest(
            """
            {"items": [
              {"title": "Zodiac", "mediatype": "movie", "ids": {"imdb": "tt1"}},
              {"title": "Alien", "mediatype": "movie", "ids": {"imdb": "tt2"}}
            ]}
            """.trimIndent(),
        )
        val ordered = MdbListLibraryRepository.orderWatchlist(items, fetchedAt = 1_000L)

        assertEquals(listOf("Zodiac", "Alien"), ordered.map { it.name })
        assertEquals(listOf(1_000L, 999L), ordered.map { it.savedAtEpochMs })
    }

    @Test
    fun `poster paths become TMDB URLs and unusable posters become none`() {
        val items = MdbListLibraryRepository.decodeListPageForTest(
            """
            [
              {"id": 1, "title": "A", "mediatype": "show", "poster": "/abc.jpg"},
              {"id": 2, "title": "B", "mediatype": "show", "poster": ""},
              {"id": 3, "title": "C", "mediatype": "show", "poster": "N/A"},
              {"id": 4, "title": "D", "mediatype": "show", "poster": "https://example.test/d.jpg"}
            ]
            """.trimIndent(),
        )

        assertEquals(
            listOf("https://image.tmdb.org/t/p/w500/abc.jpg", null, null, "https://example.test/d.jpg"),
            items.map { it.poster },
        )
    }

    @Test
    fun `watchlist mutation uses MDBList movies and shows wire shape`() {
        val ids = MdbListScrobbleRepository.MdbListIds(imdb = "tt2543164", tmdb = 329865)
        val movie = MdbListLibraryRepository.encodeMutationForTest("movie", ids)
        val show = MdbListLibraryRepository.encodeMutationForTest("series", ids)

        assertTrue("\"movies\"" in movie)
        assertTrue("\"shows\"" in show)
        assertTrue("\"imdb\":\"tt2543164\"" in movie)
        assertTrue("\"tmdb\":329865" in movie)
    }
}
