package com.replyassistant.app.api

import android.content.Context
import android.os.Build
import com.replyassistant.app.BuildConfig
import com.replyassistant.app.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class SuggestRepository(context: Context) {

    private val settings = SettingsRepository(context.applicationContext)
    private var retrofit: Retrofit? = null
    private var api: SuggestApi? = null
    private var cachedBaseUrl: String? = null

    fun shouldSkipDuplicate(message: String, sender: String?): Boolean =
        SuggestDedupe.shouldSkip(message, sender)

    private fun ensureClient() {
        val base = settings.baseUrl.trim().let { if (it.endsWith("/")) it else "$it/" }
        if (base.isBlank()) throw IllegalStateException("Base URL not set")
        val token = settings.bearerToken
        if (token.isBlank()) throw IllegalStateException("Token not set")

        if (api != null && cachedBaseUrl == base && retrofit != null) {
            return
        }
        cachedBaseUrl = base

        val auth = Interceptor { chain ->
            val t = settings.bearerToken
            val req = chain.request().newBuilder()
                .header("Authorization", "Bearer $t")
                .build()
            chain.proceed(req)
        }
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(auth)
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        retrofit = Retrofit.Builder()
            .baseUrl(base)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        api = retrofit!!.create(SuggestApi::class.java)
    }

    suspend fun suggest(message: String, sender: String?): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            if (!settings.isConfigured()) {
                return@withContext Result.failure(IllegalStateException("Configure base URL and token in the app"))
            }
            ensureClient()
            val body = SuggestRequest(
                message = message.take(16_000),
                sender = sender,
                locale = "en",
                tone = "neutral"
            )
            val resp = api!!.suggest(body)
            val list = resp.suggestions
            return@withContext if (list.size != 3) {
                Result.failure(IllegalStateException("Expected 3 suggestions"))
            } else {
                Result.success(list)
            }
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    /** Fire-and-forget client diagnostics to POST /v1/client-log (same auth as suggest). */
    suspend fun sendClientLog(level: String, message: String, stack: String? = null) =
        withContext(Dispatchers.IO) {
            try {
                if (!settings.isConfigured()) {
                    return@withContext
                }
                ensureClient()
                api!!.clientLog(
                    ClientLogRequest(
                        level = level.take(32),
                        message = message.take(8000),
                        stack = stack?.take(16000),
                        appVersion = BuildConfig.VERSION_NAME,
                        androidSdk = Build.VERSION.SDK_INT,
                        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".take(128)
                    )
                )
            } catch (_: Exception) {
            }
        }
}
