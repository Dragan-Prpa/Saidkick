package com.example

enum class McpTransport {
    HTTP_SSE,
    HTTP_POST;

    companion object {
        fun fromValue(value: String?): McpTransport {
            return when (value?.trim()?.lowercase()) {
                "http-post", "http_post", "post" -> HTTP_POST
                else -> HTTP_SSE
            }
        }
    }
}

enum class McpConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DEGRADED,
    ERROR,
}

data class McpServerConfig(
    val name: String,
    val baseUrl: String,
    val enabled: Boolean = true,
    val transport: McpTransport = McpTransport.HTTP_SSE,
    val apiKey: String? = null,
    val timeoutSeconds: Int = 20,
    val messagePath: String? = null,
    val ssePath: String? = null,
)

data class McpToolDescriptor(
    val serverName: String,
    val name: String,
    val description: String,
    val inputSchemaJson: String,
)

data class McpToolCallResult(
    val serverName: String,
    val toolName: String,
    val success: Boolean,
    val content: String,
    val rawJson: String? = null,
)

data class McpServerSnapshot(
    val name: String,
    val transport: McpTransport,
    val state: McpConnectionState,
    val toolCount: Int,
    val lastError: String? = null,
)