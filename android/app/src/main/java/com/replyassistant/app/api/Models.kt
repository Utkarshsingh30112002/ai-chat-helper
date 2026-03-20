package com.replyassistant.app.api

import com.google.gson.annotations.SerializedName

data class SuggestRequest(
    val message: String,
    val sender: String? = null,
    val locale: String? = "en",
    val tone: String? = "neutral"
)

data class SuggestResponse(
    @SerializedName("suggestions")
    val suggestions: List<String>
)

data class ErrorBody(
    val error: String? = null
)

data class ClientLogRequest(
    val level: String,
    val message: String,
    val stack: String? = null,
    val appVersion: String? = null,
    val androidSdk: Int? = null,
    val deviceModel: String? = null
)
