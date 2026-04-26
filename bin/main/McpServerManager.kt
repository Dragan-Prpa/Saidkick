package com.example

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class McpServerManager(
    private val project: Project,
) {
    private val clients = ConcurrentHashMap<String, McpClient>()
    private val snapshots = ConcurrentHashMap<String, McpServerSnapshot>()
    private val toolCache = ConcurrentHashMap<String, List<McpToolDescriptor>>()

    init {
        Disposer.register(project) {
            closeAll()
        }
    }

    fun refreshAll(): List<McpServerSnapshot> {
        val config = currentConfig()
        val activeKeys = mutableSetOf<String>()

        config.mcpServers.forEach { serverConfig ->
            val key = serverKey(serverConfig)
            activeKeys += key

            if (!serverConfig.enabled) {
                snapshots[key] = McpServerSnapshot(
                    name = serverConfig.name,
                    transport = serverConfig.transport,
                    state = McpConnectionState.DISCONNECTED,
                    toolCount = 0,
                )
                clients.remove(key)?.close()
                toolCache.remove(key)
                return@forEach
            }

            val client = clients.computeIfAbsent(key) { McpClient(serverConfig) }
            val snapshot = client.connect()
            snapshots[key] = snapshot
            if (snapshot.state == McpConnectionState.CONNECTED) {
                toolCache[key] = client.getCachedTools()
            } else {
                toolCache.remove(key)
            }
        }

        clients.keys.filterNot { it in activeKeys }.forEach { staleKey ->
            clients.remove(staleKey)?.close()
            snapshots.remove(staleKey)
            toolCache.remove(staleKey)
        }

        return snapshots.values.sortedBy { it.name }
    }

    fun getSnapshots(): List<McpServerSnapshot> {
        if (snapshots.isEmpty()) {
            return refreshAll()
        }
        return snapshots.values.sortedBy { it.name }
    }

    fun getAvailableTools(): List<McpToolDescriptor> {
        if (toolCache.isEmpty()) {
            refreshAll()
        }
        return toolCache.values.flatten().sortedWith(compareBy<McpToolDescriptor> { it.serverName }.thenBy { it.name })
    }

    fun findTool(toolName: String): List<McpToolDescriptor> {
        val normalized = toolName.trim()
        if (normalized.isBlank()) return emptyList()
        return getAvailableTools().filter { it.name.equals(normalized, ignoreCase = true) }
    }

    fun callTool(serverName: String, toolName: String, arguments: Map<String, Any?>): McpToolCallResult {
        val normalizedServerName = serverName.trim()
        val key = clients.keys.firstOrNull { it.equals(normalizedServerName, ignoreCase = true) }
            ?: currentConfig().mcpServers.firstOrNull { it.name.equals(normalizedServerName, ignoreCase = true) }
                ?.let(::serverKey)
            ?: return McpToolCallResult(normalizedServerName, toolName, false, "Unknown MCP server: $serverName")

        val client = clients[key] ?: return McpToolCallResult(normalizedServerName, toolName, false, "MCP server is not connected: $serverName")
        return runCatching {
            client.callTool(toolName, arguments)
        }.getOrElse { error ->
            McpToolCallResult(
                serverName = normalizedServerName,
                toolName = toolName,
                success = false,
                content = error.message ?: error.javaClass.simpleName,
                rawJson = null,
            )
        }
    }

    fun describeStatus(): String {
        val snapshots = getSnapshots()
        if (snapshots.isEmpty()) {
            return "No MCP servers configured."
        }

        val connected = snapshots.count { it.state == McpConnectionState.CONNECTED }
        val toolCount = snapshots.sumOf { it.toolCount }
        val errorCount = snapshots.count { it.state == McpConnectionState.ERROR }
        return buildString {
            append(connected)
            append("/")
            append(snapshots.size)
            append(" connected, ")
            append(toolCount)
            append(" tools")
            if (errorCount > 0) {
                append(", ")
                append(errorCount)
                append(" errors")
            }
        }
    }

    fun closeAll() {
        clients.values.forEach { it.close() }
        clients.clear()
        snapshots.clear()
        toolCache.clear()
    }

    private fun currentConfig(): AssistantConfig = AssistantConfig.fromEnv(project)

    private fun serverKey(server: McpServerConfig): String {
        return server.name.trim().lowercase()
    }
}