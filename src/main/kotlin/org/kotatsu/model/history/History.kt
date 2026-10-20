package org.kotatsu.model.history

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import org.kotatsu.model.manga.Manga
import kotlin.math.ceil

@Serializable
@JsonIgnoreUnknownKeys
data class History(
    @SerialName("manga_id") val mangaId: Long,
    @SerialName("manga") val manga: Manga,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("chapter_id") val chapterId: Long,
    @SerialName("page") val page: Int,
    @SerialName("scroll") val scroll: Float,
    @SerialName("percent") val percent: Float,
    @SerialName("chapters") val chapters: Int = -1,
    @SerialName("deleted_at") var deletedAt: Long,
) {
    // One-based position in the synced chapter list, not the catalogue's chapter label.
    // Recomputed from stored progress when serving history; no database migration needed.
    val currentChapter: Int?
        get() = currentChapterPosition(percent, chapters)
}

internal fun currentChapterPosition(percent: Float, chapters: Int): Int? {
    if (chapters <= 0 || !percent.isFinite() || percent < 0f || percent > 1f) return null
    return ceil(percent.toDouble() * chapters).toInt().coerceIn(1, chapters)
}
