package org.kotatsu

import com.github.mustachejava.MustacheFactory
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import org.kotatsu.mail.MailSender
import org.kotatsu.plugins.*
import org.ktorm.database.Database
import org.kotatsu.resources.CoverStore
import kotlinx.serialization.json.Json
import java.nio.file.Path

lateinit var database: Database
lateinit var hikariDataSource: HikariDataSource
lateinit var mustacheFactory: MustacheFactory
lateinit var mailService: MailSender
lateinit var coverStore: CoverStore

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

fun Application.module() {
    runMigrations()

    configureSerialization()
    configureMail()
    configureDatabase()
    configureAuthentication()
    configureLogging()
    configureTemplating()
    configureCompression()
    configureStatusPages()
    configureRateLimit()
    val coverConfig = environment.config
    val referers = coverConfig.propertyOrNull("kotatsu.cover_referers")?.getString()?.takeIf { it.isNotBlank() }
        ?: """{"cover.cdnlibs.org":"https://mangalib.org/","cover.imglib.info":"https://mangalib.org/"}"""
    coverStore = CoverStore(
        Path.of(coverConfig.property("kotatsu.covers_dir").getString()),
        coverConfig.propertyOrNull("kotatsu.covers_public_base_url")?.getString()?.takeIf { it.isNotBlank() }
            ?: coverConfig.property("kotatsu.base_url").getString(),
        coverConfig.property("jwt.secret").getString(),
        Json.decodeFromString<Map<String, String>>(referers).mapKeys { it.key.lowercase() },
    )
    configureRouting()
}
