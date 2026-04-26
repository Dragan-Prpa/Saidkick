package com.example

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.exists

data class AssistantConfig(
    val assistantName: String,
    val developerName: String,
    val assistantPersonality: PersonalityPreset,
    val requiresIdentitySetup: Boolean,
    val llmBaseUrl: String,
    val llmApiKey: String?,
    val llmModel: String,
    val llmTimeoutSeconds: Int,
    val idleThresholdSeconds: Int,
    val mcpServers: List<McpServerConfig>,
) {
    companion object {
        fun fromEnv(project: Project): AssistantConfig {
            val projectEnv = DotEnvLoader.loadProjectEnv(project)
            val llmEnv = DotEnvLoader.loadPluginEnv()
            val persistedServers = runCatching { project.service<McpSettingsService>().getServers() }
                .getOrDefault(emptyList())
            val envServers = DotEnvLoader.loadMcpServers(llmEnv)
            val mcpServers = if (persistedServers.isNotEmpty()) persistedServers else envServers

            val assistantName = projectEnv["ASSISTANT_NAME"]
                .orEmpty()
                .ifBlank { "Saidkick" }
            val developerName = projectEnv["DEVELOPER_NAME"]
                .orEmpty()
                .ifBlank { "Developer" }
            val assistantPersonality = PersonalityPreset.fromValue(projectEnv["ASSISTANT_PERSONALITY"])
            val requiresIdentitySetup = isMissing(projectEnv, "ASSISTANT_NAME") ||
                isMissing(projectEnv, "DEVELOPER_NAME") ||
                isMissing(projectEnv, "ASSISTANT_PERSONALITY")

            val llmBaseUrl = llmEnv["LLM_BASE_URL"]
                .orEmpty()
                .ifBlank { "https://api.openai.com/v1" }
            val llmApiKey = llmEnv["LLM_API_KEY"]
                ?.takeIf { it.isNotBlank() }
            val llmModel = llmEnv["LLM_MODEL"]
                .orEmpty()
                .ifBlank { "gpt-4o-mini" }
            val llmTimeoutSeconds = llmEnv["LLM_TIMEOUT_SECONDS"]
                ?.toIntOrNull()
                ?: 20
            val idleThresholdSeconds = projectEnv["INACTIVITY_PERIOD"]?.toIntOrNull()
                ?: projectEnv["IDLE_THRESHOLD_SECONDS"]?.toIntOrNull()
                ?: 300

            return AssistantConfig(
                assistantName = assistantName,
                developerName = developerName,
                assistantPersonality = assistantPersonality,
                requiresIdentitySetup = requiresIdentitySetup,
                llmBaseUrl = llmBaseUrl,
                llmApiKey = llmApiKey,
                llmModel = llmModel,
                llmTimeoutSeconds = llmTimeoutSeconds,
                idleThresholdSeconds = idleThresholdSeconds,
                mcpServers = mcpServers,
            )
        }

        private fun isMissing(values: Map<String, String>, key: String): Boolean {
            return values[key].isNullOrBlank()
        }
    }
}

private object DotEnvLoader {
    private val mcpServerPrefixPattern = Regex("^MCP_SERVER_(\\d+)_([A-Z0-9_]+)$")

    fun loadPluginEnv(): Map<String, String> {
        val explicitEnvPath = System.getProperty("saidkick.plugin.env")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { Path.of(it) }
        if (explicitEnvPath != null && explicitEnvPath.exists()) {
            return parseFileOnly(explicitEnvPath)
        }

        val candidateRoots = linkedSetOf<Path>()

        val workingDir = System.getProperty("user.dir")
        if (!workingDir.isNullOrBlank()) {
            collectAncestors(Path.of(workingDir), candidateRoots)
        }

        val codeSourcePath = runCatching {
            Path.of(AssistantConfig::class.java.protectionDomain.codeSource.location.toURI())
        }.getOrNull()
        if (codeSourcePath != null) {
            collectAncestors(codeSourcePath, candidateRoots)
        }

        val preferredEnvPath = candidateRoots
            .asSequence()
            .map { it.resolve(".env") }
            .firstOrNull { it.exists() && isLikelyPluginRoot(it.parent) }

        return if (preferredEnvPath != null) parseFileOnly(preferredEnvPath) else emptyMap()
    }

    fun loadProjectEnv(project: Project): Map<String, String> {
        val basePath = project.basePath ?: return emptyMap()
        return parseFileOnly(Path.of(basePath, ".env"))
    }

    fun loadMcpServers(values: Map<String, String>): List<McpServerConfig> {
        val mcpEnabled = values["MCP_ENABLED"]?.toBooleanStrictOrNull() ?: true
        if (!mcpEnabled) return emptyList()

        val indexedServers = values.keys
            .mapNotNull { key -> mcpServerPrefixPattern.matchEntire(key)?.groupValues?.get(1)?.toIntOrNull() }
            .distinct()
            .sorted()
            .mapNotNull { index -> parseServer(values, "MCP_SERVER_${index}_") }

        if (indexedServers.isNotEmpty()) return indexedServers

        return parseServer(values, "MCP_SERVER_")?.let(::listOf) ?: emptyList()
    }

    private fun parseFileOnly(envPath: Path): Map<String, String> {
        if (!envPath.exists()) return emptyMap()

        return Files.readAllLines(envPath)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") && it.contains('=') }
            .associate { line ->
                val separatorIndex = line.indexOf('=')
                val key = line.substring(0, separatorIndex).trim()
                val value = line.substring(separatorIndex + 1).trim().removeSurrounding("\"")
                key to value
            }
    }

    private fun collectAncestors(start: Path, out: MutableSet<Path>) {
        var current: Path? = start.absolute().normalize()
        while (current != null) {
            out.add(current)
            current = current.parent
        }
    }

    private fun parseServer(values: Map<String, String>, prefix: String): McpServerConfig? {
        val baseUrl = values["${prefix}BASE_URL"]?.trim().orEmpty()
        if (baseUrl.isBlank()) return null

        val name = values["${prefix}NAME"]?.trim().orEmpty().ifBlank {
            inferServerName(baseUrl)
        }

        val enabled = values["${prefix}ENABLED"]?.toBooleanStrictOrNull() ?: true
        val transport = McpTransport.fromValue(values["${prefix}TRANSPORT"])
        val apiKey = values["${prefix}API_KEY"]?.trim()?.takeIf { it.isNotBlank() }
            ?: values["${prefix}TOKEN"]?.trim()?.takeIf { it.isNotBlank() }
        val timeoutSeconds = values["${prefix}TIMEOUT_SECONDS"]?.toIntOrNull() ?: 20
        val messagePath = values["${prefix}MESSAGE_PATH"]?.trim()?.takeIf { it.isNotBlank() }
        val ssePath = values["${prefix}SSE_PATH"]?.trim()?.takeIf { it.isNotBlank() }

        return McpServerConfig(
            name = name,
            baseUrl = baseUrl,
            enabled = enabled,
            transport = transport,
            apiKey = apiKey,
            timeoutSeconds = timeoutSeconds.coerceAtLeast(1),
            messagePath = messagePath,
            ssePath = ssePath,
        )
    }

    private fun inferServerName(baseUrl: String): String {
        return runCatching {
            val uri = java.net.URI.create(baseUrl)
            uri.host?.takeIf { it.isNotBlank() } ?: baseUrl
        }.getOrDefault(baseUrl)
    }

    private fun isLikelyPluginRoot(path: Path?): Boolean {
        if (path == null) return false
        return path.resolve("build.gradle.kts").exists() || path.resolve("settings.gradle.kts").exists()
    }
}
