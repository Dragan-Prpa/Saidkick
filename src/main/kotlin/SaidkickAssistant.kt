package com.example

class SaidkickAssistant(
    private val config: AssistantConfig,
    private val llmProvider: LlmProvider = OpenAiCompatibleLlmProvider(config),
) {
    private val history = mutableListOf<LlmMessage>()

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

        val systemMessage = buildSystemMessage()

        val response = llmProvider.complete(
            LlmRequest(
                model = config.llmModel,
                messages = listOf(systemMessage) + history.toList(),
            ),
        )

        return when (response) {
            is LlmResponse.Success -> {
                history += LlmMessage(role = "assistant", content = response.content)
                response.content
            }

            is LlmResponse.Error -> "[Error] ${response.message}"
        }
    }

    fun respondToInternalPrompt(prompt: String): String {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) return ""

        val response = llmProvider.complete(
            LlmRequest(
                model = config.llmModel,
                messages = listOf(
                    buildSystemMessage(
                        additionalRules = """
                            This is an internal proactive event prompt.
                            Respond with exactly one sentence.
                            Keep the response at most 20 words.
                            Keep it as short as possible while adhering to personality.
                          
                        """.trimIndent(),
                    ),
                    LlmMessage(role = "user", content = trimmed),
                ),
            ),
        )

        return when (response) {
            is LlmResponse.Success -> enforceSingleShortSentence(response.content)
            is LlmResponse.Error -> "[Error] ${response.message}"
        }
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
}
