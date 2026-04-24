package app.fabula.data

import retrofit2.http.Body
import retrofit2.http.GET
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

    @GET("api/series")
    suspend fun listSeries(): List<SeriesSummaryDto>

    @GET("api/series/{id}")
    suspend fun getSeries(@Path("id") id: Int): SeriesDetailDto
}
