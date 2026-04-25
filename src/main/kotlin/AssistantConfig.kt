package com.example

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
    val changeBurstThreshold: Int,
    val changeBurstWindowSeconds: Int,
) {
    companion object {
        fun fromEnv(project: Project): AssistantConfig {
            val projectEnv = DotEnvLoader.loadProjectEnv(project)
            val projectFileEnv = DotEnvLoader.loadProjectEnvFileOnly(project)
            val pluginEnv = DotEnvLoader.loadPluginEnv()
            val llmEnv = projectEnv + pluginEnv

            val assistantName = projectEnv["ASSISTANT_NAME"]
                .orEmpty()
                .ifBlank { "Saidkick" }
            val developerName = projectEnv["DEVELOPER_NAME"]
                .orEmpty()
                .ifBlank { "Developer" }
            val assistantPersonality = PersonalityPreset.fromValue(projectEnv["ASSISTANT_PERSONALITY"])
            val requiresIdentitySetup = isMissing(projectFileEnv, "ASSISTANT_NAME") ||
                isMissing(projectFileEnv, "DEVELOPER_NAME") ||
                isMissing(projectFileEnv, "ASSISTANT_PERSONALITY")

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
            val changeBurstThreshold = projectEnv["CHANGE_BURST_THRESHOLD"]?.toIntOrNull() ?: 20
            val changeBurstWindowSeconds = projectEnv["CHANGE_BURST_WINDOW_SECONDS"]?.toIntOrNull() ?: 60

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
                changeBurstThreshold = changeBurstThreshold,
                changeBurstWindowSeconds = changeBurstWindowSeconds,
            )
        }

        private fun isMissing(values: Map<String, String>, key: String): Boolean {
            return values[key].isNullOrBlank()
        }
    }
}

private object DotEnvLoader {
    fun loadPluginEnv(): Map<String, String> {
        val explicitEnvPath = System.getProperty("saidkick.plugin.env")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { Path.of(it) }
        if (explicitEnvPath != null && explicitEnvPath.exists()) {
            return loadFromPath(explicitEnvPath)
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

        return if (preferredEnvPath != null) loadFromPath(preferredEnvPath) else System.getenv()
    }

    fun loadProjectEnv(project: Project): Map<String, String> {
        val basePath = project.basePath ?: return System.getenv()
        return loadFromPath(Path.of(basePath, ".env"))
    }

    fun loadProjectEnvFileOnly(project: Project): Map<String, String> {
        val basePath = project.basePath ?: return emptyMap()
        return parseFileOnly(Path.of(basePath, ".env"))
    }

    private fun loadFromPath(envPath: Path): Map<String, String> {
        val systemEnv = System.getenv()
        if (!envPath.exists()) return systemEnv

        val fileValues = parseFileOnly(envPath)

        return systemEnv + fileValues
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

    private fun isLikelyPluginRoot(path: Path?): Boolean {
        if (path == null) return false
        return path.resolve("build.gradle.kts").exists() || path.resolve("settings.gradle.kts").exists()
    }
}
