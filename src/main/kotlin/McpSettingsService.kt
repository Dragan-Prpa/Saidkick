package com.example

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "SaidkickMcpSettings", storages = [Storage("saidkick-mcp.xml")])
@Service(Service.Level.PROJECT)
class McpSettingsService : PersistentStateComponent<McpSettingsState> {
    private var state = McpSettingsState()

    override fun getState(): McpSettingsState = state

    override fun loadState(state: McpSettingsState) {
        this.state = state
    }

    fun getServers(): List<McpServerConfig> = state.servers.map { it.toConfig() }

    fun hasPersistedServers(): Boolean = state.servers.isNotEmpty()

    fun setServers(servers: List<McpServerConfig>) {
        state.servers = servers.map { McpServerState.fromConfig(it) }.toMutableList()
    }

    fun clearServers() {
        state.servers.clear()
    }
}

class McpSettingsState {
    var servers: MutableList<McpServerState> = mutableListOf()
}

class McpServerState {
    var name: String = ""
    var baseUrl: String = ""
    var enabled: Boolean = true
    var transport: String = McpTransport.HTTP_SSE.name
    var apiKey: String? = null
    var timeoutSeconds: Int = 20
    var messagePath: String? = null
    var ssePath: String? = null

    fun toConfig(): McpServerConfig {
        return McpServerConfig(
            name = name.ifBlank { "MCP Server" },
            baseUrl = baseUrl,
            enabled = enabled,
            transport = McpTransport.fromValue(transport),
            apiKey = apiKey?.takeIf { it.isNotBlank() },
            timeoutSeconds = timeoutSeconds.coerceAtLeast(1),
            messagePath = messagePath?.takeIf { it.isNotBlank() },
            ssePath = ssePath?.takeIf { it.isNotBlank() },
        )
    }

    companion object {
        fun fromConfig(config: McpServerConfig): McpServerState {
            return McpServerState().apply {
                name = config.name
                baseUrl = config.baseUrl
                enabled = config.enabled
                transport = config.transport.name
                apiKey = config.apiKey
                timeoutSeconds = config.timeoutSeconds
                messagePath = config.messagePath
                ssePath = config.ssePath
            }
        }
    }
}