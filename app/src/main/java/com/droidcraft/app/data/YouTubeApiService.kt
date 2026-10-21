package com.droidcraft.app.data

import com.droidcraft.app.model.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*

interface YouTubeApiService {

    @GET("/api/youtube/channels")
    suspend fun getConnectedChannels(): List<YouTubeChannel>

    @POST("/api/assistant/chat")
    suspend fun dispatchAiCommand(@Body request: AssistantRequest): AssistantResponse

    @POST("/api/pipeline/generate")
    suspend fun generateVideoPackage(@Body request: GeneratePackageRequest): GeneratedPackageResponse

    @POST("/api/upload/verify")
    suspend fun verifyVideoUpload(@Query("videoId") videoId: String): VerificationResponse

    @GET("/api/upload/duplicate-check")
    suspend fun checkDuplicate(@Query("sha256") sha256: String): Boolean

    companion object {
        fun create(): YouTubeApiService {
            return Retrofit.Builder()
                .baseUrl("https://api.droidcraft.app")
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(YouTubeApiService::class.java)
        }
    }
}