package org.kotatsu.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.kotatsu.coverStore
import org.kotatsu.database
import org.kotatsu.model.favourites
import org.kotatsu.model.favourite.FavouritesTable
import org.kotatsu.model.history
import org.kotatsu.model.history.HistoryTable
import org.kotatsu.model.manga
import org.kotatsu.model.manga.MangaTable
import org.kotatsu.plugins.currentUser
import org.kotatsu.resources.*
import org.ktorm.dsl.and
import org.ktorm.dsl.eq
import org.ktorm.entity.find
import java.io.IOException

@Serializable
data class CoverInventory(val version: Int, val max_bytes: Int, val covers: Map<String, String>)

fun Route.coverRoutes(
    store: CoverStore = coverStore,
    userId: (ApplicationCall) -> Int? = { it.currentUser?.id },
    canUpload: (Int, Long) -> Boolean = ::canUploadCover,
) {
    authenticate("auth-jwt") {
        get("/resource/covers") {
            val user = userId(call) ?: return@get call.respond(HttpStatusCode.Unauthorized)
            call.respond(withContext(Dispatchers.IO) { CoverInventory(version = 1, max_bytes = MAX_COVER_BYTES, covers = store.hashes(user)) })
        }
        put("/resource/covers/{id}") {
            val user = userId(call) ?: return@put call.respond(HttpStatusCode.Unauthorized)
            val id = call.parameters["id"]?.toLongOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest)
            if (!withContext(Dispatchers.IO) { canUpload(user, id) }) return@put call.respond(HttpStatusCode.NotFound)
            if ((call.request.header(HttpHeaders.ContentLength)?.toLongOrNull() ?: 0) > MAX_COVER_BYTES) {
                return@put call.respond(HttpStatusCode.PayloadTooLarge)
            }
            val bytes = withContext(Dispatchers.IO) {
                call.receiveStream().use { it.readNBytes(MAX_COVER_BYTES + 1) }
            }
            if (bytes.size > MAX_COVER_BYTES) return@put call.respond(HttpStatusCode.PayloadTooLarge)
            if (coverMime(bytes) == null) return@put call.respond(HttpStatusCode.UnsupportedMediaType)
            val hash = withContext(Dispatchers.IO) { store.put(user, id, bytes) }
            call.response.header(HttpHeaders.ETag, "\"$hash\"")
            call.respond(HttpStatusCode.NoContent)
        }
    }
    get("/covers/{kind}/{key}/{signature}") {
        val key = call.parameters["key"] ?: return@get call.respond(HttpStatusCode.NotFound)
        val signature = call.parameters["signature"] ?: return@get call.respond(HttpStatusCode.NotFound)
        val bytes = try {
            withContext(Dispatchers.IO) {
                when (call.parameters["kind"]) {
                    "local" -> store.local(key, signature)
                    "remote" -> store.remote(key, signature)
                    else -> null
                }
            }
        } catch (_: IOException) {
            return@get call.respond(HttpStatusCode.BadGateway)
        } ?: return@get call.respond(HttpStatusCode.NotFound)
        val mime = coverMime(bytes) ?: return@get call.respond(HttpStatusCode.NotFound)
        val etag = "\"${sha256(bytes)}\""
        call.response.header(HttpHeaders.ETag, etag)
        call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
        call.response.header("X-Content-Type-Options", "nosniff")
        if (call.request.header(HttpHeaders.IfNoneMatch) == etag) call.respond(HttpStatusCode.NotModified)
        else call.respondBytes(bytes, ContentType.parse(mime))
    }
}

private fun canUploadCover(userId: Int, mangaId: Long): Boolean {
    if (database.manga.find { it.id eq mangaId }?.source != "LOCAL") return false
    return database.history.find { (it.userId eq userId) and (it.manga eq mangaId) } != null ||
        database.favourites.find { (it.userId eq userId) and (it.manga eq mangaId) } != null
}
