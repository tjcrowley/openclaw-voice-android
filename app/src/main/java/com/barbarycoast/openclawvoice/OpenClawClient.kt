package com.barbarycoast.openclawvoice

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OkHttp SSE streaming client for OpenAI-compatible chat completions API.
 */
class OpenClawClient {

    companion object {
        private const val TAG = "OpenClawClient"
        private const val API_URL = "https://hermes.tailb3d66d.ts.net/v1/chat/completions"
        private const val API_TOKEN = BuildConfig.OPENCLAW_API_TOKEN
        private const val MODEL = "openclaw:main"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val sseFactory = EventSources.createFactory(client)

    data class Message(val role: String, val content: String)

    /**
     * Send a streaming chat completion request.
     * Emits content delta strings as they arrive.
     * The flow completes when the stream ends or errors.
     */
    fun streamChat(
        messages: List<Message>,
        user: String? = null
    ): Flow<String> = callbackFlow {
        val messagesArray = JSONArray().apply {
            for (msg in messages) {
                put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                })
            }
        }

        val body = JSONObject().apply {
            put("model", MODEL)
            put("messages", messagesArray)
            put("stream", true)
            if (user != null) put("user", user)
        }

        val request = Request.Builder()
            .url(API_URL)
            .addHeader("Authorization", "Bearer $API_TOKEN")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data == "[DONE]") {
                    close()
                    return
                }
                try {
                    val json = JSONObject(data)
                    val choices = json.optJSONArray("choices") ?: return
                    val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: return
                    val content = delta.optString("content", "")
                    if (content.isNotEmpty()) {
                        trySend(content)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse SSE data: $data", e)
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                Log.e(TAG, "SSE failure: ${t?.message}, response=${response?.code}", t)
                close(IOException(t?.message ?: "SSE connection failed"))
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val eventSource = sseFactory.newEventSource(request, listener)

        awaitClose {
            eventSource.cancel()
        }
    }

    /**
     * Non-streaming request (for testing or fallback).
     */
    suspend fun chat(messages: List<Message>, user: String? = null): String =
        withContext(Dispatchers.IO) {
            val messagesArray = JSONArray().apply {
                for (msg in messages) {
                    put(JSONObject().apply {
                        put("role", msg.role)
                        put("content", msg.content)
                    })
                }
            }

            val body = JSONObject().apply {
                put("model", MODEL)
                put("messages", messagesArray)
                put("stream", false)
                if (user != null) put("user", user)
            }

            val request = Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer $API_TOKEN")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: throw IOException("Empty response")

            if (!response.isSuccessful) {
                throw IOException("API error ${response.code}: $responseBody")
            }

            val json = JSONObject(responseBody)
            json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }

    fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
