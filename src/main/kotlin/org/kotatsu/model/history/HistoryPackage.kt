package org.kotatsu.model.history

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import kotlin.jvm.internal.Intrinsics
import kotlinx.serialization.json.*

@Serializable
@JsonIgnoreUnknownKeys
data class HistoryPackage(
    @SerialName("history") val history: List<History>,
    @SerialName("timestamp") val timestamp: Long?,
) {
    fun contentEquals(other: HistoryPackage): Boolean {
        return Intrinsics.areEqual(history, other.history)
    }
}

/** Enrich read-only GET responses without changing the Android sync protocol. */
fun HistoryPackage.withChapterPositions(): JsonObject {
    val original = Json.encodeToJsonElement(this).jsonObject
    return JsonObject(original + ("history" to JsonArray(history.map { item ->
        val record = Json.encodeToJsonElement(item).jsonObject
        JsonObject(record + ("current_chapter" to (item.currentChapter?.let(::JsonPrimitive) ?: JsonNull)))
    })))
}
