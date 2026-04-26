package com.example

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

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
        val payload = JsonObject().apply {
            addProperty("model", request.model)
            add("messages", buildMessages(request))
            if (request.tools.isNotEmpty()) {
                add("tools", buildTools(request.tools))
            }
        }
        return gson.toJson(payload)
    }

    private fun parseContent(raw: String): LlmResponse {
        return runCatching {
            val root = JsonParser.parseString(raw).asJsonObject
            val choices = root.getAsJsonArray("choices")
            if (choices == null || choices.size() == 0) {
                return LlmResponse.Error("LLM response parsing failed (no choices).")
            }

            val message = choices[0].asJsonObject.getAsJsonObject("message")
                ?: return LlmResponse.Error("LLM response parsing failed (no message object).")

            val toolCalls = parseToolCalls(message.getAsJsonArray("tool_calls"))
            val content = extractMessageContent(message)

            if (toolCalls.isNotEmpty()) {
                return LlmResponse.ToolCalls(toolCalls, content.takeIf { it.isNotBlank() })
            }

            if (content.isBlank()) {
                return LlmResponse.Error("LLM returned an empty response.")
            }

            LlmResponse.Success(content)
        }.getOrElse { error ->
            LlmResponse.Error(
                "LLM response parsing failed: ${error.message ?: "Unknown parse error"}. Response starts with: ${raw.take(180)}",
            )
        }
    }

    private fun buildMessages(request: LlmRequest): JsonArray {
        val messages = JsonArray()
        request.messages.forEach { msg ->
            val item = JsonObject().apply {
                addProperty("role", msg.role)
                addProperty("content", msg.content)
                if (!msg.name.isNullOrBlank()) {
                    addProperty("name", msg.name)
                }
            }
            messages.add(item)
        }

        request.toolResults.forEach { result ->
            val item = JsonObject().apply {
                addProperty("role", "tool")
                addProperty("tool_call_id", result.toolCallId)
                addProperty("name", result.name)
                addProperty("content", result.content)
            }
            messages.add(item)
        }

        return messages
    }

    private fun buildTools(tools: List<LlmToolDefinition>): JsonArray {
        val output = JsonArray()
        tools.forEach { tool ->
            val schema = runCatching { JsonParser.parseString(tool.inputSchemaJson) }
                .getOrElse { JsonObject() }

            val toolJson = JsonObject().apply {
                addProperty("type", "function")
                add("function", JsonObject().apply {
                    addProperty("name", tool.name)
                    addProperty("description", tool.description)
                    add("parameters", schema)
                })
            }
            output.add(toolJson)
        }
        return output
    }

    private fun parseToolCalls(toolCalls: JsonArray?): List<LlmToolCall> {
        if (toolCalls == null || toolCalls.size() == 0) return emptyList()

        return toolCalls.mapNotNull { item ->
            val obj = item.asJsonObject
            val id = obj.get("id")?.asString?.trim().orEmpty()
            val function = obj.getAsJsonObject("function") ?: return@mapNotNull null
            val name = function.get("name")?.asString?.trim().orEmpty()
            val arguments = function.get("arguments")

            if (id.isBlank() || name.isBlank() || arguments == null) return@mapNotNull null

            val argumentsJson = when {
                arguments.isJsonPrimitive -> arguments.asString
                else -> arguments.toString()
            }

            LlmToolCall(
                id = id,
                name = name,
                argumentsJson = argumentsJson,
            )
        }
    }

    private fun extractMessageContent(message: JsonObject): String {
        val contentElement = message.get("content") ?: return ""
        if (contentElement.isJsonNull) return ""
        if (contentElement.isJsonPrimitive) return contentElement.asString
        if (!contentElement.isJsonArray) return contentElement.toString()

        val parts = contentElement.asJsonArray.mapNotNull { segment ->
            val obj = segment.asJsonObject
            obj.get("text")?.asString
                ?: obj.get("content")?.asString
        }

        return if (parts.isNotEmpty()) parts.joinToString("\n") else contentElement.toString()
    }
}
