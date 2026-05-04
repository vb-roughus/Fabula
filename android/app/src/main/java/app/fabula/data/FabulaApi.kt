package app.fabula.data

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface FabulaApi {
    @GET("api/books")
    suspend fun listBooks(
        @Query("search") search: String? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100
    ): PagedResultDto<BookSummaryDto>

    @GET("api/books/{id}")
    suspend fun getBook(@Path("id") id: Int): BookDetailDto

    @GET("api/progress/{bookId}")
    suspend fun getProgress(@Path("bookId") bookId: Int): ProgressDto

    @PUT("api/progress/{bookId}")
    suspend fun saveProgress(
        @Path("bookId") bookId: Int,
        @Body body: UpdateProgressRequest
    ): ProgressDto

    @POST("api/progress/{bookId}/finished")
    suspend fun setFinished(
        @Path("bookId") bookId: Int,
        @Body body: SetFinishedRequest
    ): ProgressDto

    @GET("api/series")
    suspend fun listSeries(): List<SeriesSummaryDto>

    @GET("api/series/{id}")
    suspend fun getSeries(@Path("id") id: Int): SeriesDetailDto

    @POST("api/series")
    suspend fun createSeries(@Body body: SeriesRequest): SeriesSummaryDto

    @PUT("api/series/{id}")
    suspend fun updateSeries(
        @Path("id") id: Int,
        @Body body: SeriesRequest
    ): SeriesSummaryDto

    @DELETE("api/series/{id}")
    suspend fun deleteSeries(@Path("id") id: Int)

    @PUT("api/books/{bookId}/series")
    suspend fun assignBookSeries(
        @Path("bookId") bookId: Int,
        @Body body: AssignSeriesRequest
    )

    @GET("api/books/{bookId}/bookmarks")
    suspend fun listBookmarks(@Path("bookId") bookId: Int): List<BookmarkDto>

    @POST("api/books/{bookId}/bookmarks")
    suspend fun createBookmark(
        @Path("bookId") bookId: Int,
        @Body body: CreateBookmarkRequest
    ): BookmarkDto

    @PATCH("api/bookmarks/{id}")
    suspend fun updateBookmark(
        @Path("id") id: Int,
        @Body body: UpdateBookmarkRequest
    ): BookmarkDto

    @DELETE("api/bookmarks/{id}")
    suspend fun deleteBookmark(@Path("id") id: Int)

    // --- auth -------------------------------------------------------------

    @GET("api/setup")
    suspend fun getSetupStatus(): SetupStatusDto

    @POST("api/setup")
    suspend fun setup(@Body body: SetupRequest): AuthResponseDto

    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponseDto

    @GET("api/auth/me")
    suspend fun getMe(): AuthUserDto

    @POST("api/me/password")
    suspend fun changeMyPassword(@Body body: ChangePasswordRequest)

    @GET("api/users")
    suspend fun listUsers(): List<UserDetailDto>

    @POST("api/users")
    suspend fun createUser(@Body body: CreateUserRequest): UserDetailDto

    @DELETE("api/users/{id}")
    suspend fun deleteUser(@Path("id") id: Int)

    @POST("api/users/{id}/admin")
    suspend fun setUserAdmin(@Path("id") id: Int, @Body body: SetAdminRequest)

    @POST("api/users/{id}/password")
    suspend fun adminResetPassword(@Path("id") id: Int, @Body body: AdminResetPasswordRequest)
}
