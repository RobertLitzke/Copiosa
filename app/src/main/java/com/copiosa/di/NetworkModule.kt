package com.copiosa.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.copiosa.BuildConfig
import com.copiosa.data.remote.CommonsApi
import com.copiosa.data.remote.GeminiApi
import com.copiosa.data.remote.OpenRouterApi
import com.copiosa.data.remote.RetryInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/"
    private const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1/"
    private const val COMMONS_BASE_URL = "https://commons.wikimedia.org/"

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor())
            .apply {
                // Request lines are a debugging aid; a release build has no business writing which
                // model was called, and how often, to a log other apps' crash reports can carry.
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
                }
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideGeminiApi(client: OkHttpClient, json: Json): GeminiApi =
        Retrofit.Builder()
            .baseUrl(GEMINI_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GeminiApi::class.java)

    @Provides
    @Singleton
    fun provideOpenRouterApi(client: OkHttpClient, json: Json): OpenRouterApi =
        Retrofit.Builder()
            .baseUrl(OPENROUTER_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OpenRouterApi::class.java)

    @Provides
    @Singleton
    fun provideCommonsApi(client: OkHttpClient, json: Json): CommonsApi =
        Retrofit.Builder()
            .baseUrl(COMMONS_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(CommonsApi::class.java)
}
