package com.mine.app.di

import com.mine.app.data.remote.GroqApi
import com.mine.app.data.remote.NimApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient {
        return HttpClient(OkHttp) {
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
