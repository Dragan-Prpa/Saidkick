package com.example

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeoutException

class OpenAiCompatibleLlmProvider(
    private val config: AssistantConfig,
) : LlmProvider {

    private val httpClient: HttpClient by lazy { HttpClient.newHttpClient() }

    override fun complete(request: LlmRequest): LlmResponse {
        val apiKey = config.llmApiKey
            ?: return LlmResponse.Error("LLM is not configured. Set LLM_API_KEY in .env.")

        val payload = toRequestBody(request)
        val endpoint = config.llmBaseUrl.trimEnd('/') + "/chat/completions"

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(Duration.ofSeconds(config.llmTimeoutSeconds.toLong()))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()

        return runCatching {
            httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        }.fold(
            onSuccess = { response ->
                when {
                    response.statusCode() !in 200..299 -> {
                        LlmResponse.Error("LLM request failed with HTTP ${response.statusCode()}.")
                    }

                    else -> parseContent(response.body())
                }
            },
            onFailure = { error ->
                val message = when (error) {
                    is TimeoutException -> "LLM request timed out."
                    else -> "LLM request failed: ${error.message ?: "Unknown error"}"
                }
                LlmResponse.Error(message)
            },
        )
    }

    private fun toRequestBody(request: LlmRequest): String {
        val messagesJson = request.messages.joinToString(",") { msg ->
            """{"role":${toJsonString(msg.role)},"content":${toJsonString(msg.content)}}"""
        }
        return """
            {
              "model": ${toJsonString(request.model)},
              "messages": [$messagesJson]
            }
        """.trimIndent()
    }

    private fun parseContent(raw: String): LlmResponse {
        val messageMarker = "\"message\""
        val messageStart = raw.indexOf(messageMarker)
        val contentMarker = "\"content\":"

        val contentStart = when {
            messageStart != -1 -> raw.indexOf(contentMarker, messageStart)
            else -> raw.indexOf(contentMarker)
        }

        if (contentStart == -1) {
            return LlmResponse.Error(
                "LLM response parsing failed (no message content field). Response starts with: ${raw.take(180)}",
            )
        }

        val valueStart = contentStart + contentMarker.length
        val firstQuote = raw.indexOf('"', valueStart)
        if (firstQuote == -1) {
            val contentEnd = raw.indexOfAny(charArrayOf(',', '}'), valueStart)
            val rawScalar = if (contentEnd == -1) raw.substring(valueStart) else raw.substring(valueStart, contentEnd)
            val scalarValue = rawScalar.trim()
            if (scalarValue.equals("null", ignoreCase = true) || scalarValue.isBlank()) {
                return LlmResponse.Error("LLM returned an empty response.")
            }

            return LlmResponse.Success(scalarValue)
        }

        val extracted = extractJsonStringValue(raw, firstQuote)
            ?: return LlmResponse.Error("LLM response parsing failed (invalid content payload).")

        if (extracted.isBlank()) {
            return LlmResponse.Error("LLM returned an empty response.")
        }

        return LlmResponse.Success(extracted)
    }

    private fun extractJsonStringValue(json: String, openingQuoteIndex: Int): String? {
        val out = StringBuilder()
        var escaped = false
        var i = openingQuoteIndex + 1
        while (i < json.length) {
            val ch = json[i]
            if (escaped) {
                when (ch) {
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    else -> out.append(ch)
                }
                escaped = false
            } else {
                when (ch) {
                    '\\' -> escaped = true
                    '"' -> return out.toString()
                    else -> out.append(ch)
                }
            }
            i++
        }
        return null
    }

    private fun toJsonString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        return "\"$escaped\""
    }
}
