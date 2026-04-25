package com.example

data class LlmMessage(
    val role: String,
    val content: String,
)

data class LlmRequest(
    val model: String,
    val messages: List<LlmMessage>,
)

sealed class LlmResponse {
    data class Success(val content: String) : LlmResponse()
    data class Error(val message: String) : LlmResponse()
}

interface LlmProvider {
    fun complete(request: LlmRequest): LlmResponse
}
