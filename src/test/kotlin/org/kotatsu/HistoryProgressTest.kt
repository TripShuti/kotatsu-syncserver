package org.kotatsu

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.kotatsu.model.history.History
import org.kotatsu.model.history.currentChapterPosition
import org.kotatsu.model.history.HistoryPackage
import org.kotatsu.model.history.withChapterPositions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoryProgressTest {
    @Test
    fun chapterPositionIncludesPartlyReadChapter() {
        assertEquals(2, currentChapterPosition(0.0025373932f, 416))
        assertEquals(36, currentChapterPosition(0.31840354f, 110))
        assertEquals(1, currentChapterPosition(0f, 110))
        assertEquals(110, currentChapterPosition(1f, 110))
        assertNull(currentChapterPosition(-1f, 110))
        assertNull(currentChapterPosition(Float.NaN, 110))
        assertNull(currentChapterPosition(0.5f, -1))
        assertNull(currentChapterPosition(0.5f, 0))
    }

    @Test
    fun existingSyncPayloadProducesComputedResponseField() {
        val history = Json.decodeFromString<History>("""
            {"manga_id":1,"manga":{"manga_id":1,"title":"Local manga",
            "alt_title":null,"url":"local","public_url":"","rating":-1,
            "cover_url":"","large_cover_url":null,"tags":[],"state":null,
            "author":null,"source":"LOCAL"},"created_at":0,"updated_at":0,
            "chapter_id":123,"page":4,"scroll":0,"percent":0.15,
            "chapters":10,"deleted_at":0}
        """.trimIndent())
        val syncResponse = Json.parseToJsonElement(Json.encodeToString(history)).jsonObject
        assertNull(syncResponse["current_chapter"])
        val response = HistoryPackage(listOf(history), 0).withChapterPositions()
            .getValue("history").jsonArray[0].jsonObject
        assertEquals("2", response.getValue("current_chapter").jsonPrimitive.content)
    }
}
