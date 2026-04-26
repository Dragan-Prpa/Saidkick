package com.example

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

class SaidkickAssistant(
    private val config: AssistantConfig,
    private val mcpServerManager: McpServerManager? = null,
    private val llmProvider: LlmProvider = OpenAiCompatibleLlmProvider(config),
) {
    private val history = mutableListOf<LlmMessage>()
    private val gson = Gson()

    private val personalityDescriptions = mapOf(
        PersonalityPreset.COACH to "Practical, calm, and action-oriented. Gives clear steps and nudges toward best coding practices. Keeps things short and clear, and direct.",
        PersonalityPreset.ARCHITECT to "System-level and structured. Focuses on design trade-offs, maintainability, and long-term impact. Often goes into detail and speculation how the changes and actions might affect possible future actions.",
        PersonalityPreset.CHEERLEADER to "Encouraging and positive. Keeps momentum high while still giving technically correct guidance. Always optimistic and praising the developer about every success, and giving credit for trying when failing.",
        PersonalityPreset.REVIEWER to "Critical but constructive. Prioritizes correctness, edge cases, and code quality with direct suggestions. Not afraid to criticize the developer and give negative feedback.",
    )

    fun respondTo(userInput: String): String {
        val trimmed = userInput.trim()
        if (trimmed.isBlank()) return "Please enter a message."

        history += LlmMessage(role = "user", content = trimmed)

        return respondWithOptionalTools(
            systemMessage = buildSystemMessage(),
            messages = history.toList(),
            enforceShortSentence = false,
        ).also { content ->
            if (!content.startsWith("[Error]")) {
                history += LlmMessage(role = "assistant", content = content)
            }
        }
    }

    fun respondToInternalPrompt(prompt: String): String {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) return ""

        return respondWithOptionalTools(
            systemMessage = buildSystemMessage(
                additionalRules = """
                    This is an internal proactive event prompt.
                    Respond with exactly one sentence.
                    Keep the response at most 20 words.
                    Keep it as short as possible while adhering to personality.
                """.trimIndent(),
            ),
            messages = listOf(LlmMessage(role = "user", content = trimmed)),
            enforceShortSentence = true,
        )
    }

    private fun respondWithOptionalTools(
        systemMessage: LlmMessage,
        messages: List<LlmMessage>,
        enforceShortSentence: Boolean,
    ): String {
        val toolContext = createToolContext()
        val toolResults = mutableListOf<LlmToolResult>()

        repeat(MAX_TOOL_LOOP_ITERATIONS) {
            val response = llmProvider.complete(
                LlmRequest(
                    model = config.llmModel,
                    messages = listOf(systemMessage) + messages,
                    tools = toolContext.definitions,
                    toolResults = toolResults,
                ),
            )

            when (response) {
                is LlmResponse.Success -> {
                    return if (enforceShortSentence) {
                        enforceSingleShortSentence(response.content)
                    } else {
                        response.content
                    }
                }

                is LlmResponse.Error -> {
                    val fallback = tryRespondWithoutTools(systemMessage, messages, enforceShortSentence, response.message)
                    if (fallback != null) {
                        return fallback
                    }
                    return "[Error] ${response.message}"
                }

                is LlmResponse.ToolCalls -> {
                    if (response.calls.isEmpty()) {
                        val fallback = response.assistantMessage ?: "I could not resolve the requested tool call."
                        return if (enforceShortSentence) enforceSingleShortSentence(fallback) else fallback
                    }

                    val newResults = response.calls.map { call ->
                        val descriptor = toolContext.byLlmName[call.name]
                        if (descriptor == null) {
                            return@map LlmToolResult(
                                toolCallId = call.id,
                                name = call.name,
                                content = "Tool '${call.name}' is not available.",
                                isError = true,
                            )
                        }

                        val arguments = parseArguments(call.argumentsJson)
                        val result = mcpServerManager?.callTool(
                            descriptor.serverName,
                            descriptor.name,
                            arguments,
                        ) ?: McpToolCallResult(
                            serverName = descriptor.serverName,
                            toolName = descriptor.name,
                            success = false,
                            content = "MCP manager is unavailable.",
                        )

                        LlmToolResult(
                            toolCallId = call.id,
                            name = call.name,
                            content = result.content,
                            isError = !result.success,
                        )
                    }

                    toolResults += newResults
                }
            }
        }

        return "[Error] Tool loop exceeded ${MAX_TOOL_LOOP_ITERATIONS} iterations."
    }

    private fun tryRespondWithoutTools(
        systemMessage: LlmMessage,
        messages: List<LlmMessage>,
        enforceShortSentence: Boolean,
        originalErrorMessage: String,
    ): String? {
        val lowered = originalErrorMessage.lowercase()
        val likelyToolCallingMismatch = lowered.contains("tool") ||
            lowered.contains("function") ||
            lowered.contains("unsupported") ||
            lowered.contains("invalid") ||
            lowered.contains("400")

        if (!likelyToolCallingMismatch) return null

        val fallbackResponse = llmProvider.complete(
            LlmRequest(
                model = config.llmModel,
                messages = listOf(systemMessage) + messages,
            ),
        )

        return when (fallbackResponse) {
            is LlmResponse.Success -> {
                if (enforceShortSentence) enforceSingleShortSentence(fallbackResponse.content) else fallbackResponse.content
            }

            is LlmResponse.Error -> "[Error] ${fallbackResponse.message}"
            is LlmResponse.ToolCalls -> {
                val content = fallbackResponse.assistantMessage ?: "I could not finish this response without tool support."
                if (enforceShortSentence) enforceSingleShortSentence(content) else content
            }
        }
    }

    private fun createToolContext(): ToolContext {
        val manager = mcpServerManager ?: return ToolContext(emptyList(), emptyMap())
        val tools = runCatching {
            manager.refreshAll()
            manager.getAvailableTools()
        }.getOrDefault(emptyList())

        if (tools.isEmpty()) {
            return ToolContext(emptyList(), emptyMap())
        }

        val definitions = mutableListOf<LlmToolDefinition>()
        val byLlmName = mutableMapOf<String, McpToolDescriptor>()

        tools.forEach { tool ->
            val llmToolName = toLlmToolName(tool.serverName, tool.name)
            definitions += LlmToolDefinition(
                name = llmToolName,
                description = tool.description.ifBlank { "MCP tool '${tool.name}' from ${tool.serverName}" },
                inputSchemaJson = normalizeSchema(tool.inputSchemaJson),
            )
            byLlmName[llmToolName] = tool
        }

        return ToolContext(definitions, byLlmName)
    }

    private fun toLlmToolName(serverName: String, toolName: String): String {
        val combined = "${serverName}_${toolName}".lowercase()
        val sanitized = combined.replace(Regex("[^a-z0-9_]+"), "_").trim('_')
        return if (sanitized.length > 64) sanitized.take(64) else sanitized
    }

    private fun normalizeSchema(schemaJson: String): String {
        return runCatching {
            JsonParser.parseString(schemaJson).toString()
        }.getOrDefault("{" +
            "\"type\":\"object\"," +
            "\"properties\":{}," +
            "\"additionalProperties\":true" +
            "}")
    }

    private fun parseArguments(argumentsJson: String): Map<String, Any?> {
        if (argumentsJson.isBlank()) return emptyMap()

        return runCatching {
            val parsed = JsonParser.parseString(argumentsJson)
            if (parsed is JsonObject || parsed.isJsonObject) {
                @Suppress("UNCHECKED_CAST")
                gson.fromJson(argumentsJson, Map::class.java) as Map<String, Any?>
            } else {
                emptyMap()
            }
        }.getOrDefault(emptyMap())
    }

    private fun buildSystemMessage(additionalRules: String? = null): LlmMessage {
        val personalityDescription = personalityDescriptions[config.assistantPersonality]
            ?: personalityDescriptions.getValue(PersonalityPreset.COACH)
        val assistantName = config.assistantName
        val developerName = config.developerName

        val content = """
            You are an agent called $assistantName.
            Personality description: $personalityDescription
            Always act according to your personality!
            IMPORTANT:
            ALWAYS HAVE A TONE JUST AS YOU ARE SPEAKING TO YOUR FRIEND!!!!

            Mandatory requirements:
            1) Always refer to yourself as "$assistantName" and respond to that name.
            2) Always address the developer as "$developerName" when speaking to them.
            3) You are a Coding Buddy ($assistantName), not an AI. Never mention or refer to yourself as AI, artificial intelligence, language model, model, assistant model, or bot.
            ${additionalRules?.trim().orEmpty()}
        """.trimIndent()

        return LlmMessage(role = "system", content = content)
    }

    private fun enforceSingleShortSentence(content: String): String {
        val normalized = content
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (normalized.isBlank()) return ""

        val oneSentence = normalized
            .split(Regex("(?<=[.!?])\\s+"))
            .firstOrNull()
            ?.trim()
            .orEmpty()
            .ifBlank { normalized }

        val words = oneSentence
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        val limited = words.take(20).joinToString(" ").trim()
        if (limited.isBlank()) return ""

        return if (limited.last() in listOf('.', '!', '?')) limited else "$limited."
    }

    private data class ToolContext(
        val definitions: List<LlmToolDefinition>,
        val byLlmName: Map<String, McpToolDescriptor>,
    )

    companion object {
        private const val MAX_TOOL_LOOP_ITERATIONS = 4
    }
}
