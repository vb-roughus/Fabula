package app.fabula.data

import kotlinx.serialization.Serializable

enum class LibraryType { Audiobook, RadioPlay }

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
    val type: LibraryType = LibraryType.Audiobook,
    val libraryFolderId: Int = 0,
    val libraryFolderName: String = "",
    /** ISO-8601 timestamp of when the scanner first discovered this book.
     *  Used to order the home screen's "Zuletzt hinzugefügt" rows. */
    val addedAt: String? = null,
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
    val type: LibraryType = LibraryType.Audiobook,
    val libraryFolderId: Int = 0,
    val libraryFolderName: String = "",
    val progress: ProgressSummaryDto? = null,
    val chapters: List<ChapterDto> = emptyList(),
    val files: List<AudioFileDto> = emptyList()
)

/**
 * Narrows a cached book detail back to the summary shape the library and home
 * tiles render. Used to show downloaded books when the server is unreachable.
 */
fun BookDetailDto.toSummary(): BookSummaryDto = BookSummaryDto(
    id = id,
    title = title,
    subtitle = subtitle,
    authors = authors,
    narrators = narrators,
    seriesId = seriesId,
    series = series,
    seriesPosition = seriesPosition,
    duration = duration,
    coverUrl = coverUrl,
    type = type,
    libraryFolderId = libraryFolderId,
    libraryFolderName = libraryFolderName,
    progress = progress
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
    val offsetInBook: String,
    /** Size on disk. Defaults to 0 against older servers that don't send it --
     *  the download progress then falls back to duration weighting. */
    val sizeBytes: Long = 0
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
data class SetFinishedRequest(
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
    val coverUrl: String? = null,
    val duration: String = "00:00:00",
    val progress: ProgressSummaryDto? = null
)

@Serializable
data class SeriesDetailDto(
    val id: Int,
    val name: String,
    val description: String? = null,
    val books: List<SeriesBookDto> = emptyList()
)

@Serializable
data class BookmarkDto(
    val id: Int,
    val bookId: Int,
    val position: String,
    val note: String? = null,
    val createdAt: String
)

@Serializable
data class CreateBookmarkRequest(val position: String, val note: String? = null)

@Serializable
data class UpdateBookmarkRequest(val note: String? = null)

@Serializable
data class HighlightDto(
    val id: Int,
    val bookId: Int,
    val start: String,
    val end: String,
    val title: String? = null,
    val note: String? = null,
    val createdAt: String
)

@Serializable
data class CreateHighlightRequest(
    val start: String,
    val end: String,
    val title: String? = null,
    val note: String? = null
)

@Serializable
data class UpdateHighlightRequest(val title: String? = null, val note: String? = null)

@Serializable
data class SeriesRequest(val name: String, val description: String? = null)

@Serializable
data class AssignSeriesRequest(val seriesId: Int? = null, val seriesPosition: Double? = null)

// --- auth ----------------------------------------------------------------

@Serializable
data class SetupStatusDto(val needsSetup: Boolean)

@Serializable
data class AuthUserDto(val id: Int, val username: String, val isAdmin: Boolean)

@Serializable
data class AuthResponseDto(val token: String, val user: AuthUserDto)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class SetupRequest(val username: String, val password: String)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

@Serializable
data class CreateUserRequest(val username: String, val password: String, val isAdmin: Boolean)

@Serializable
data class AdminResetPasswordRequest(val newPassword: String)

@Serializable
data class SetAdminRequest(val isAdmin: Boolean)

@Serializable
data class UserDetailDto(
    val id: Int,
    val username: String,
    val isAdmin: Boolean,
    val createdAt: String
)

// --- in-app update ---------------------------------------------------------

@Serializable
data class AppVersionDto(val versionCode: Int, val versionName: String)

@Serializable
data class AppUpdateConfigDto(
    val repo: String? = null,
    val hasToken: Boolean = false,
    val checkMinutes: Int = 15,
    val currentVersionCode: Int? = null,
    val currentVersionName: String? = null
)

@Serializable
data class UpdateAppConfigRequest(val repo: String?, val token: String?)

@Serializable
data class AppUpdateCheckDto(
    val configured: Boolean,
    val ok: Boolean,
    val message: String,
    val versionCode: Int? = null,
    val versionName: String? = null
)

// --- server self-update ----------------------------------------------------

/**
 * How far a server update has got. Sent as a name, not a number, and defaulted
 * so an older server that doesn't know the field can't break deserialisation.
 */
@Serializable
enum class ServerUpdateState {
    Idle, Downloading, Verifying, Installing, Succeeded, Failed
}

@Serializable
data class ServerUpdateStatusDto(
    val state: ServerUpdateState = ServerUpdateState.Idle,
    val fromVersion: String? = null,
    val toVersion: String? = null,
    val startedAtUtc: String? = null,
    val handoffAtUtc: String? = null,
    val message: String? = null
)

@Serializable
data class ServerUpdateInfoDto(
    /** False where there is no service to restart -- a dev run, or Linux. */
    val supported: Boolean = false,
    val unsupportedReason: String? = null,
    val currentVersion: String? = null,
    val latestVersion: String? = null,
    val available: Boolean = false,
    val status: ServerUpdateStatusDto = ServerUpdateStatusDto()
)

@Serializable
data class ServerUpdateCheckDto(
    val configured: Boolean,
    val ok: Boolean,
    val message: String,
    val latestVersion: String? = null
)
