package org.kotatsu

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import org.kotatsu.resources.*
import org.kotatsu.routes.coverRoutes
import java.net.URI
import java.nio.file.Files
import kotlin.test.*

class CoverRoutesTest {
    @Test fun uploadRequiresAuthenticationAndOwnershipWhileSignedImagesLoadWithoutBearerToken() = testApplication {
        val root = Files.createTempDirectory("cover-route-test")
        try {
            val store = CoverStore(root, "https://sync.example", "test-secret", emptyMap())
            application {
                install(ContentNegotiation) { json() }
                install(Authentication) {
                    bearer("auth-jwt") { authenticate { if (it.token == "user1") UserIdPrincipal("1") else null } }
                }
                routing { coverRoutes(store, { it.principal<UserIdPrincipal>()?.name?.toInt() }, { user, id -> user == 1 && id == 7L }) }
            }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/resource/covers").status)
            assertEquals(HttpStatusCode.Unauthorized, client.put("/resource/covers/7") { setBody(coverPng) }.status)
            assertEquals(HttpStatusCode.NotFound, client.put("/resource/covers/8") { bearerAuth("user1"); setBody(coverPng) }.status)
            assertEquals(HttpStatusCode.UnsupportedMediaType, client.put("/resource/covers/7") { bearerAuth("user1"); setBody("not an image") }.status)
            assertEquals(HttpStatusCode.PayloadTooLarge, client.put("/resource/covers/7") { bearerAuth("user1"); setBody(ByteArray(MAX_COVER_BYTES + 1)) }.status)
            assertEquals(HttpStatusCode.NoContent, client.put("/resource/covers/7") { bearerAuth("user1"); setBody(coverPng) }.status)
            val inventory = client.get("/resource/covers") { bearerAuth("user1") }.bodyAsText()
            assertTrue(inventory.contains("\"7\""))
            assertTrue(inventory.contains("\"version\":1"))
            assertTrue(inventory.contains("\"max_bytes\":$MAX_COVER_BYTES"))
            val path = URI(store.external(coverManga(), 1).coverUrl).path
            val image = client.get(path)
            assertEquals(HttpStatusCode.OK, image.status)
            assertEquals("image/png", image.headers[HttpHeaders.ContentType])
            assertContentEquals(coverPng, image.bodyAsBytes())
            assertEquals(HttpStatusCode.NotModified, client.get(path) { header(HttpHeaders.IfNoneMatch, image.headers[HttpHeaders.ETag]!!) }.status)
            assertEquals(HttpStatusCode.NotFound, client.get(path + "x").status)
        } finally { root.toFile().deleteRecursively() }
    }
}
