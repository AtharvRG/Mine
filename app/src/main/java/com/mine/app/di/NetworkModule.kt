package com.mine.app.di

import com.mine.app.data.remote.GroqApi
import com.mine.app.data.remote.NimApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout // <-- NEW IMPORT
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit // <-- NEW IMPORT
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient {
        return HttpClient(OkHttp) {
            // 1. Ktor Timeout Plugin
            install(HttpTimeout) {
                requestTimeoutMillis = 120_000L // Wait 2 minutes
                connectTimeoutMillis = 120_000L
                socketTimeoutMillis = 120_000L
            }

            // 2. OkHttp Engine Timeouts (Double protection)
            engine {
                config {
                    readTimeout(120, TimeUnit.SECONDS)
                    connectTimeout(120, TimeUnit.SECONDS)
                    writeTimeout(120, TimeUnit.SECONDS)
                }
            }

            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    encodeDefaults = true
                    explicitNulls = false
                })
            }
        }
    }

    @Provides
    @Singleton
    fun provideNimApi(client: HttpClient): NimApi {
        return NimApi(client)
    }

    @Provides
    @Singleton
    fun provideGroqApi(client: HttpClient): GroqApi {
        return GroqApi(client)
    }
}
