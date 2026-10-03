package com.lecteur.core.network.di

import retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.lecteur.core.model.MetadataProvider
import com.lecteur.core.network.BuildConfig
import com.lecteur.core.network.tmdb.RequestPacer
import com.lecteur.core.network.tmdb.TmdbApi
import com.lecteur.core.network.tmdb.TmdbApiKey
import com.lecteur.core.network.tmdb.TmdbAuthInterceptor
import com.lecteur.core.network.tmdb.TmdbClient
import com.lecteur.core.network.tmdb.TmdbRateLimitInterceptor
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val TMDB_BASE_URL = "https://api.themoviedb.org/3/"

    @Provides
    @TmdbApiKey
    fun tmdbApiKey(): String = BuildConfig.TMDB_API_KEY

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun tmdbApi(json: Json, @TmdbApiKey apiKey: String): TmdbApi {
        // No body logging: the api_key travels in the query string
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(TmdbAuthInterceptor(apiKey))
            .addInterceptor(TmdbRateLimitInterceptor(RequestPacer()))
            .build()

        return Retrofit.Builder()
            .baseUrl(TMDB_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TmdbApi::class.java)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MetadataBindings {
    @Binds abstract fun metadataProvider(impl: TmdbClient): MetadataProvider
}
