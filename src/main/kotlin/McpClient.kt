package com.example

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.Volatile

class McpClient(
    private val config: McpServerConfig,
) : AutoCloseable {
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(config.timeoutSeconds.toLong()))
        .build()
    private val requestIds = AtomicLong(0)
    private val eventExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Saidkick-MCP-${config.name}").apply {
            isDaemon = true
        }
    }

    @Volatile
    private var eventInputStream: InputStream? = null

    @Volatile
    private var connected = false

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var cachedTools: List<McpToolDescriptor> = emptyList()

    fun connect(): McpServerSnapshot {
        if (!config.enabled) {
            connected = false
            lastError = null
            return snapshot(McpConnectionState.DISCONNECTED, 0)
        }

        lastError = null
        return try {
            sendRequest("initialize", buildInitializeParams())
            if (config.transport == McpTransport.HTTP_SSE && !config.ssePath.isNullOrBlank()) {
                startEventListener()
            }
            val tools = listTools()
            cachedTools = tools
            connected = true
            snapshot(McpConnectionState.CONNECTED, tools.size)
        } catch (error: Exception) {
            connected = false
            lastError = error.message ?: error.javaClass.simpleName
            snapshot(McpConnectionState.ERROR, 0, lastError)
        }
    }

    fun refreshTools(): List<McpToolDescriptor> {
        if (!connected && !config.enabled) return emptyList()
        val tools = listTools()
        cachedTools = tools
        return tools
    }

    fun getCachedTools(): List<McpToolDescriptor> = cachedTools

    fun listTools(): List<McpToolDescriptor> {
        val response = sendRequest("tools/list", JsonObject())
        val toolsElement = response.get("tools") ?: return emptyList()

        if (!toolsElement.isJsonArray) return emptyList()

        return toolsElement.asJsonArray.mapNotNull { element ->
            val obj = element.asJsonObject
            val name = obj.get("name")?.asString?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null

            McpToolDescriptor(
                serverName = config.name,
                name = name,
                description = obj.get("description")?.asString.orEmpty(),
                inputSchemaJson = obj.get("inputSchema")?.toString() ?: "{}",
            )
        }
    }

    fun callTool(toolName: String, arguments: Map<String, Any?>): McpToolCallResult {
        val params = JsonObject().apply {
            addProperty("name", toolName)
            add("arguments", gson.toJsonTree(arguments))
        }
        val response = sendRequest("tools/call", params)
        return McpToolCallResult(
            serverName = config.name,
            toolName = toolName,
            success = true,
            content = extractContent(response),
            rawJson = response.toString(),
        )
    }

    fun isConnected(): Boolean = connected

    fun getLastError(): String? = lastError

    fun snapshot(toolCount: Int = 0): McpServerSnapshot {
        return snapshot(
            if (connected) McpConnectionState.CONNECTED else McpConnectionState.DISCONNECTED,
            toolCount,
            lastError,
        )
    }

    override fun close() {
        connected = false
        eventInputStream?.close()
        eventInputStream = null
        eventExecutor.shutdownNow()
    }

    private fun snapshot(state: McpConnectionState, toolCount: Int, error: String? = null): McpServerSnapshot {
        return McpServerSnapshot(
            name = config.name,
            transport = config.transport,
            state = state,
            toolCount = toolCount,
            lastError = error,
        )
    }

    private fun buildInitializeParams(): JsonObject {
        return JsonObject().apply {
            addProperty("protocolVersion", "2024-11-05")
            add("clientInfo", JsonObject().apply {
                addProperty("name", "Saidkick")
                addProperty("version", "1.0.0")
            })
            add("capabilities", JsonObject())
        }
    }

    private fun startEventListener() {
        val sseUri = resolveEndpoint(config.ssePath)
        val requestBuilder = HttpRequest.newBuilder()
            .uri(sseUri)
            .timeout(Duration.ofSeconds(config.timeoutSeconds.toLong()))
            .header("Accept", "text/event-stream")
            .GET()

        if (!config.apiKey.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer ${config.apiKey}")
        }

        eventExecutor.submit {
            try {
                val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())
                if (response.statusCode() !in 200..299) {
                    lastError = "SSE stream failed with HTTP ${response.statusCode()}"
                    connected = false
                    return@submit
                }

                response.body().use { body ->
                    eventInputStream = body
                    readEventStream(body)
                }
            } catch (error: Exception) {
                lastError = error.message ?: error.javaClass.simpleName
                connected = false
            } finally {
                eventInputStream = null
            }
        }
    }

    private fun readEventStream(inputStream: InputStream) {
        inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            var eventName: String? = null
            val payload = StringBuilder()

            while (true) {
                val line = reader.readLine() ?: break
                when {
                    line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        if (payload.isNotEmpty()) payload.append('\n')
                        payload.append(line.removePrefix("data:").trim())
                    }

                    line.isBlank() -> {
                        if (payload.isNotBlank()) {
                            handleSseEvent(eventName, payload.toString())
                        }
                        eventName = null
                        payload.setLength(0)
                    }
                }
            }
        }
    }

    private fun handleSseEvent(eventName: String?, payload: String) {
        if (payload.isBlank()) return

        runCatching { JsonParser.parseString(payload).asJsonObject }
            .onSuccess { json ->
                val method = json.get("method")?.asString?.trim().orEmpty()
                if (method.contains("tool", ignoreCase = true) || eventName?.contains("tool", ignoreCase = true) == true) {
                    runCatching { refreshTools() }
                }
            }
    }

    private fun sendRequest(method: String, params: JsonElement?): JsonObject {
        val requestJson = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", requestIds.incrementAndGet())
            addProperty("method", method)
            if (params != null) add("params", params)
        }

        val requestBuilder = HttpRequest.newBuilder()
            .uri(resolveEndpoint(config.messagePath))
            .timeout(Duration.ofSeconds(config.timeoutSeconds.toLong()))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestJson)))

        if (!config.apiKey.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer ${config.apiKey}")
        }

        val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("MCP request '$method' failed with HTTP ${response.statusCode()}")
        }

        val body = response.body().orEmpty()
        val responseElement = JsonParser.parseString(body).asJsonObject
        val error = responseElement.getAsJsonObject("error")
        if (error != null) {
            throw IllegalStateException(error.get("message")?.asString ?: "MCP request '$method' failed")
        }

        return responseElement.getAsJsonObject("result") ?: JsonObject()
    }

    private fun resolveEndpoint(path: String?): URI {
        val base = config.baseUrl.trimEnd('/')
        val resolvedPath = path?.trim()?.takeIf { it.isNotBlank() }
        val url = when {
            resolvedPath == null -> base
            resolvedPath.startsWith("http://") || resolvedPath.startsWith("https://") -> resolvedPath
            resolvedPath.startsWith("/") -> base + resolvedPath
            else -> "$base/$resolvedPath"
        }
        return URI.create(url)
    }

    private fun extractContent(result: JsonObject): String {
        val content = result.get("content") ?: return result.toString()
        if (content.isJsonPrimitive) {
            return content.asString
        }

        if (!content.isJsonArray) {
            return content.toString()
        }

        val parts = content.asJsonArray.mapNotNull { entry ->
            val obj = entry.asJsonObject
            obj.get("text")?.asString
                ?: obj.get("content")?.asString
                ?: obj.get("value")?.asString
        }

        return if (parts.isNotEmpty()) parts.joinToString("\n") else content.toString()
    }
}