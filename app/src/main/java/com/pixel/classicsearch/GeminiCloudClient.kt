package com.pixel.classicsearch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Lightweight, zero-dependency streaming client for Google Generative AI (Gemini 1.5 Flash).
 * Used when running on non-Pixel devices or when the user configures their own Gemini API Key.
 *
 * Transmits the API key securely via the 'x-goog-api-key' HTTP header to prevent leakage
 * in URLs, server logs, or referrer headers.
 */
object GeminiCloudClient {

    private const val API_ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:streamGenerateContent?alt=sse"

    fun streamAnswer(apiKey: String, query: String): Flow<String> = flow {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) {
            emit("Введите Gemini API Key в настройках приложения для облачных ответов.")
            return@flow
        }

        var conn: HttpsURLConnection? = null
        try {
            val url = URL(API_ENDPOINT)
            conn = (url.openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
                setRequestProperty("x-goog-api-key", trimmedKey)
                connectTimeout = 6000
                readTimeout = 10000
                doOutput = true
            }

            val systemInstruction = "You are a concise, helpful assistant embedded into an Android quick search bar. " +
                    "Answer directly in 1-2 brief sentences in the same language as the user's question without preamble."

            val jsonPayload = JSONObject().apply {
                val contents = JSONArray().apply {
                    val contentObj = JSONObject().apply {
                        val parts = JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "$systemInstruction\n\nQuestion: $query")
                            })
                        }
                        put("parts", parts)
                    }
                    put(contentObj)
                }
                put("contents", contents)

                val genConfig = JSONObject().apply {
                    put("temperature", 0.2)
                    put("maxOutputTokens", 160)
                }
                put("generationConfig", genConfig)
            }

            OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                writer.write(jsonPayload.toString())
                writer.flush()
            }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { reader ->
                    var line: String? = reader.readLine()
                    while (line != null) {
                        if (line.startsWith("data: ")) {
                            val dataJson = line.substring(6).trim()
                            if (dataJson.isNotEmpty() && dataJson != "[DONE]") {
                                try {
                                    val obj = JSONObject(dataJson)
                                    val candidates = obj.optJSONArray("candidates")
                                    if (candidates != null && candidates.length() > 0) {
                                        val cand = candidates.getJSONObject(0)
                                        val content = cand.optJSONObject("content")
                                        val parts = content?.optJSONArray("parts")
                                        if (parts != null && parts.length() > 0) {
                                            val partText = parts.getJSONObject(0).optString("text", "")
                                            if (partText.isNotEmpty()) {
                                                emit(partText)
                                            }
                                        }
                                    }
                                } catch (_: Exception) {
                                    // Skip unparseable chunks
                                }
                            }
                        }
                        line = reader.readLine()
                    }
                }
            } else {
                if (responseCode == 400 || responseCode == 403) {
                    emit("Неверный Gemini API Key или ошибка доступа ($responseCode). Проверьте ключ в настройках.")
                } else {
                    emit("Ошибка Gemini Cloud ($responseCode). Нажмите Enter для перехода в поиск.")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit("Не удалось связаться с Gemini: ${e.localizedMessage ?: "Сетевая ошибка"}")
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {}
        }
    }.flowOn(Dispatchers.IO)
}
