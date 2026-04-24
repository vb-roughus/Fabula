package app.fabula.data

import kotlinx.serialization.Serializable

@Serializable
data class BookSummaryDto(
    val id: Int,
    val title: String,
    val subtitle: String? = null,
    val authors: List<String> = emptyList(),
    val narrators: List<String> = emptyList(),
    val seriesId: Int? = null,
    val series: String? = null,
    val seriesPosition: Double? = null,
    val duration: String,
    val coverUrl: String? = null,
    val progress: ProgressSummaryDto? = null
)

@Serializable
data class ProgressSummaryDto(
    val position: String,
    val finished: Boolean,
    val updatedAt: String? = null
)

@Serializable
data class BookDetailDto(
    val id: Int,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val authors: List<String> = emptyList(),
    val narrators: List<String> = emptyList(),
    val seriesId: Int? = null,
    val series: String? = null,
    val seriesPosition: Double? = null,
    val language: String? = null,
    val publisher: String? = null,
    val publishYear: Int? = null,
    val isbn: String? = null,
    val asin: String? = null,
    val duration: String,
    val coverUrl: String? = null,
    val progress: ProgressSummaryDto? = null,
    val chapters: List<ChapterDto> = emptyList(),
    val files: List<AudioFileDto> = emptyList()
)

@Serializable
data class ChapterDto(
    val index: Int,
    val title: String,
    val start: String,
    val end: String
)

@Serializable
data class AudioFileDto(
    val id: Int,
    val trackIndex: Int,
    val duration: String,
    val offsetInBook: String
)

@Serializable
data class PagedResultDto<T>(
    val items: List<T>,
    val total: Int,
    val page: Int,
    val pageSize: Int
)

@Serializable
data class ProgressDto(
    val bookId: Int,
    val position: String,
    val finished: Boolean,
    val updatedAt: String? = null,
    val device: String? = null
)

@Serializable
data class UpdateProgressRequest(
    val position: String,
    val finished: Boolean,
    val device: String
)

@Serializable
data class SeriesSummaryDto(
    val id: Int,
    val name: String,
    val description: String? = null,
    val bookCount: Int,
    val coverUrl: String? = null
)

@Serializable
data class SeriesBookDto(
    val id: Int,
    val title: String,
    val authors: List<String> = emptyList(),
    val position: Double? = null,
    val coverUrl: String? = null
)

@Serializable
data class SeriesDetailDto(
    val id: Int,
    val name: String,
    val description: String? = null,
    val books: List<SeriesBookDto> = emptyList()
)
