package com.example

data class LlmMessage(
    val role: String,
    val content: String,
    val name: String? = null,
)

data class LlmToolDefinition(
    val name: String,
    val description: String,
    val inputSchemaJson: String,
)

data class LlmToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

data class LlmToolResult(
    val toolCallId: String,
    val name: String,
    val content: String,
    val isError: Boolean = false,
)

data class LlmRequest(
    val model: String,
    val messages: List<LlmMessage>,
    val tools: List<LlmToolDefinition> = emptyList(),
    val toolResults: List<LlmToolResult> = emptyList(),
)

sealed class LlmResponse {
    data class Success(val content: String) : LlmResponse()
    data class ToolCalls(
        val calls: List<LlmToolCall>,
        val assistantMessage: String? = null,
    ) : LlmResponse()
    data class Error(val message: String) : LlmResponse()
}

interface LlmProvider {
    fun complete(request: LlmRequest): LlmResponse
}
