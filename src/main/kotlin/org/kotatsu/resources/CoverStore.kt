package org.kotatsu.resources

import org.kotatsu.model.manga.Manga
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

const val MAX_COVER_BYTES = 5 * 1024 * 1024
const val COVER_CAPABILITY_HEADER = "X-Kotatsu-Covers"

/** Immutable image blobs and per-user pointers. No device paths or credentials are published. */
class CoverStore(
    private val root: Path,
    publicBaseUrl: String,
    private val secret: String,
    private val referers: Map<String, String>,
    private val fetchImage: (URI, String) -> ByteArray = ::fetchCoverImage,
) {
    private val baseUrl = publicBaseUrl.trimEnd('/')
    private val locks = Array(64) { Any() }

    init {
        val uri = URI(baseUrl)
        require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null)
        Files.createDirectories(root.resolve("blobs"))
    }

    fun put(userId: Int, mangaId: Long, bytes: ByteArray): String {
        require(bytes.size in 1..MAX_COVER_BYTES && coverMime(bytes) != null) { "Unsupported cover image" }
        val hash = sha256(bytes)
        val blob = root.resolve("blobs/$hash")
        if (!Files.exists(blob)) atomicWrite(blob, bytes)
        atomicWrite(root.resolve("users/$userId/$mangaId"), hash.toByteArray())
        return hash
    }

    fun hashes(userId: Int): Map<String, String> {
        val dir = root.resolve("users/$userId")
        if (!Files.isDirectory(dir)) return emptyMap()
        return Files.list(dir).use { files ->
            files.filter { it.fileName.toString().toLongOrNull() != null }
                .map { it.fileName.toString() to Files.readString(it).trim() }
                .filter { (_, hash) -> validHash(hash) && Files.isRegularFile(root.resolve("blobs/$hash")) }
                .toList().toMap()
        }
    }

    /** Only read-only API representations change. Android POST payloads retain their original URLs. */
    fun external(manga: Manga, userId: Int): Manga {
        if (manga.source == "LOCAL") {
            val index = root.resolve("users/$userId/${manga.id}")
            val hash = if (Files.isRegularFile(index)) Files.readString(index).trim() else ""
            val url = if (validHash(hash) && Files.isRegularFile(root.resolve("blobs/$hash"))) {
                "$baseUrl/covers/local/$hash/${sign("local:$hash")}"
            } else ""
            return manga.copy(coverUrl = url, largeCoverUrl = url.takeIf { it.isNotEmpty() })
        }
        fun proxy(url: String): String {
            val uri = runCatching { URI(url) }.getOrNull() ?: return url
            if (!allowed(uri)) return url
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(url.toByteArray())
            return "$baseUrl/covers/remote/$encoded/${sign("remote:$encoded")}"
        }
        return manga.copy(coverUrl = proxy(manga.coverUrl), largeCoverUrl = manga.largeCoverUrl?.let(::proxy))
    }

    fun local(hash: String, signature: String): ByteArray? {
        if (!validHash(hash) || !verified("local:$hash", signature)) return null
        val file = root.resolve("blobs/$hash")
        return if (Files.isRegularFile(file)) Files.readAllBytes(file) else null
    }

    fun remote(encoded: String, signature: String): ByteArray? {
        if (encoded.length > 4096 || !verified("remote:$encoded", signature)) return null
        val uri = runCatching { URI(String(Base64.getUrlDecoder().decode(encoded))) }.getOrNull() ?: return null
        if (!allowed(uri)) return null
        val key = sha256(uri.toString().toByteArray())
        synchronized(locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]) {
            val cache = root.resolve("remote/$key")
            if (Files.isRegularFile(cache) && System.currentTimeMillis() - Files.getLastModifiedTime(cache).toMillis() < 86_400_000) {
                return Files.readAllBytes(cache)
            }
            val bytes = fetchImage(uri, referers.getValue(uri.host.lowercase()))
            if (bytes.size !in 1..MAX_COVER_BYTES || coverMime(bytes) == null) throw IOException("Invalid upstream image")
            atomicWrite(cache, bytes)
            return bytes
        }
    }

    private fun allowed(uri: URI) = uri.scheme == "https" && uri.userInfo == null &&
        uri.port in setOf(-1, 443) && uri.fragment == null && referers.containsKey(uri.host?.lowercase())

    private fun sign(value: String): String = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        doFinal(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun verified(value: String, signature: String) = signature.length == 64 &&
        MessageDigest.isEqual(sign(value).toByteArray(), signature.toByteArray())
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }

private fun validHash(value: String) = value.matches(Regex("[a-f0-9]{64}"))

internal fun coverMime(bytes: ByteArray): String? = when {
    bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
    bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)) -> "image/png"
    bytes.size >= 6 && String(bytes, 0, 6) in setOf("GIF87a", "GIF89a") -> "image/gif"
    bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WEBP" -> "image/webp"
    else -> null
}

private fun atomicWrite(path: Path, bytes: ByteArray) {
    Files.createDirectories(path.parent)
    val temp = Files.createTempFile(path.parent, ".cover-", ".tmp")
    try {
        Files.write(temp, bytes)
        try { Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(temp, path, REPLACE_EXISTING) }
    } finally { Files.deleteIfExists(temp) }
}

private fun fetchCoverImage(uri: URI, referer: String): ByteArray {
    // Hosts come from an administrator allowlist, not an image URL query parameter.
    if (InetAddress.getAllByName(uri.host).any {
            it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress ||
                it.isMulticastAddress || (it.address.size == 16 && (it.address[0].toInt() and 0xfe) == 0xfc)
        }) throw IOException("Private upstream address")
    val connection = uri.toURL().openConnection() as HttpURLConnection
    try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 5_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Referer", referer)
        connection.setRequestProperty("Accept", "image/*")
        connection.setRequestProperty("User-Agent", "Kotatsu-Syncserver/1.0")
        if (connection.responseCode != 200) throw IOException("Upstream image returned HTTP ${connection.responseCode}")
        if (connection.contentLengthLong > MAX_COVER_BYTES) throw IOException("Upstream image is too large")
        return connection.inputStream.use { it.readNBytes(MAX_COVER_BYTES + 1) }
    } finally { connection.disconnect() }
}
