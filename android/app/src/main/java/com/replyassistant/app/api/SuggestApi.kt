package com.replyassistant.app.api

import retrofit2.http.Body
import retrofit2.http.POST

interface SuggestApi {

    @POST("v1/suggest")
    suspend fun suggest(@Body body: SuggestRequest): SuggestResponse

    @POST("v1/client-log")
    suspend fun clientLog(@Body body: ClientLogRequest): Map<String, Boolean>
}
