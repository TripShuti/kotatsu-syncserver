package org.kotatsu

import org.kotatsu.resources.*
import org.kotatsu.model.manga.Manga
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.net.URI
import java.util.Base64
import kotlin.test.*

internal val coverPng: ByteArray = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jZQAAAABJRU5ErkJggg==")
internal fun coverManga(source: String = "LOCAL", cover: String = "file:///device/manga/cover.jpg"): Manga =
    Json.decodeFromString<Manga>("""{"manga_id":7,"title":"Test","alt_title":null,"url":"local","public_url":"","rating":-1,"cover_url":"","large_cover_url":null,"tags":[],"state":null,"author":null,"source":"LOCAL"}""").copy(source = source, coverUrl = cover)

class CoverStoreTest {
    private val root = Files.createTempDirectory("kotatsu-covers-test")
    private fun store() = CoverStore(root, "https://sync.example/prefix", "test-secret", emptyMap())
    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }

    @Test fun uploadedCoverSurvivesRestartAndRemainsScopedToItsOwner() {
        val store = store()
        val manga = coverManga()
        assertEquals("", store.external(manga, 1).coverUrl)
        val hash = store.put(1, 7, coverPng)
        assertEquals(hash, store.hashes(1)["7"])
        assertTrue(store.hashes(2).isEmpty())
        assertEquals("", store.external(manga, 2).coverUrl)
        val url = store().external(manga, 1).coverUrl
        assertTrue(url.startsWith("https://sync.example/prefix/covers/local/"))
        assertFalse(url.contains("device"))
        val signature = url.substringAfterLast('/')
        assertContentEquals(coverPng, store().local(hash, signature))
        assertNull(store.local(hash, "a".repeat(64)))
        assertEquals("file:///device/manga/cover.jpg", manga.coverUrl)
    }

    @Test fun changedCoverGetsANewImmutableUrlAndInvalidImagesAreRejected() {
        val store = store()
        store.put(1, 7, coverPng)
        val before = store.external(coverManga(), 1).coverUrl
        store.put(1, 7, coverPng + byteArrayOf(0))
        assertNotEquals(before, store.external(coverManga(), 1).coverUrl)
        assertFailsWith<IllegalArgumentException> { store.put(1, 7, "<html>error</html>".toByteArray()) }
        assertFailsWith<IllegalArgumentException> { store.put(1, 7, ByteArray(MAX_COVER_BYTES + 1)) }
    }

    @Test fun protectedImagesUseConfiguredRefererAndCachedBytesWithoutPublishingCredentials() {
        var reads = 0
        val store = CoverStore(root, "https://sync.example", "test-secret", mapOf("covers.example" to "https://manga.example/")) { uri, referer ->
            assertEquals(URI("https://covers.example/image.png"), uri)
            assertEquals("https://manga.example/", referer)
            reads++
            coverPng
        }
        val url = store.external(coverManga("MANGALIB", "https://covers.example/image.png"), 1).coverUrl
        val parts = URI(url).path.split('/')
        assertContentEquals(coverPng, store.remote(parts[3], parts[4]))
        assertContentEquals(coverPng, store.remote(parts[3], parts[4]))
        assertEquals(1, reads)
        assertNull(store.remote(parts[3] + "x", parts[4]))
        for (unsafe in listOf("http://covers.example/x", "https://other.example/x", "https://user:pass@covers.example/x", "https://covers.example:444/x")) {
            assertEquals(unsafe, store.external(coverManga("MANGALIB", unsafe), 1).coverUrl)
        }
        assertEquals(1, reads)
    }
}
