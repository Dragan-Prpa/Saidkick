package com.example

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane

class McpSettingsConfigurable(
    private val project: Project,
) : SearchableConfigurable {
    private var rootPanel: JPanel? = null
    private var serverListModel: DefaultListModel<String>? = null
    private var serverList: JBList<String>? = null

    private var nameField: JBTextField? = null
    private var baseUrlField: JBTextField? = null
    private var apiKeyField: JBTextField? = null
    private var timeoutField: JBTextField? = null
    private var messagePathField: JBTextField? = null
    private var ssePathField: JBTextField? = null
    private var enabledCheckBox: JBCheckBox? = null
    private var httpPostTransportCheckBox: JBCheckBox? = null

    private val servers = mutableListOf<McpServerConfig>()

    override fun getId(): String = "com.example.Saidkick.McpSettings"

    override fun getDisplayName(): String = "Saidkick MCP"

    override fun createComponent(): JComponent {
        if (rootPanel != null) {
            return rootPanel as JPanel
        }

        val listModel = DefaultListModel<String>()
        serverListModel = listModel
        serverList = JBList(listModel)

        nameField = JBTextField()
        baseUrlField = JBTextField()
        apiKeyField = JBTextField()
        timeoutField = JBTextField("20")
        messagePathField = JBTextField("/mcp")
        ssePathField = JBTextField("/sse")
        enabledCheckBox = JBCheckBox("Enabled", true)
        httpPostTransportCheckBox = JBCheckBox("Use HTTP POST transport (unchecked = HTTP/SSE)", false)

        val addButton = JButton("Add")
        val removeButton = JButton("Remove")
        val saveButton = JButton("Save Entry")
        val testButton = JButton("Test Connection")

        serverList?.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                loadSelectedServerIntoForm()
            }
        }

        addButton.addActionListener {
            val index = servers.size + 1
            servers += McpServerConfig(
                name = "Server $index",
                baseUrl = "http://localhost:3000",
            )
            refreshServerList()
            serverList?.selectedIndex = servers.lastIndex
            loadSelectedServerIntoForm()
        }

        removeButton.addActionListener {
            val selected = serverList?.selectedIndex ?: -1
            if (selected !in servers.indices) return@addActionListener
            servers.removeAt(selected)
            refreshServerList()
            val next = selected.coerceAtMost(servers.lastIndex)
            if (next >= 0) {
                serverList?.selectedIndex = next
                loadSelectedServerIntoForm()
            } else {
                clearForm()
            }
        }

        saveButton.addActionListener {
            val selected = serverList?.selectedIndex ?: -1
            if (selected !in servers.indices) return@addActionListener

            val updated = formToConfig() ?: run {
                Messages.showWarningDialog("Server name and base URL are required.", "Invalid MCP Server")
                return@addActionListener
            }

            servers[selected] = updated
            refreshServerList()
            serverList?.selectedIndex = selected
        }

        testButton.addActionListener {
            val candidate = formToConfig() ?: run {
                Messages.showWarningDialog("Server name and base URL are required.", "Invalid MCP Server")
                return@addActionListener
            }

            ApplicationManager.getApplication().executeOnPooledThread {
                val snapshot = runCatching {
                    McpClient(candidate).use { client ->
                        client.connect()
                    }
                }.getOrElse { error ->
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            "Connection test failed: ${error.message ?: "unknown error"}",
                            "MCP Connection Test",
                        )
                    }
                    return@executeOnPooledThread
                }

                ApplicationManager.getApplication().invokeLater {
                    val message = if (snapshot.state == McpConnectionState.CONNECTED) {
                        "Connected successfully. Tools discovered: ${snapshot.toolCount}."
                    } else {
                        "Connection completed with state ${snapshot.state}. ${snapshot.lastError ?: "No detailed error."}"
                    }
                    Messages.showInfoMessage(message, "MCP Connection Test")
                }
            }
        }

        val listButtons = JPanel(GridLayout(1, 2, 8, 0)).apply {
            add(addButton)
            add(removeButton)
        }

        val leftPanel = JPanel(BorderLayout(0, 8)).apply {
            add(JLabel("Servers"), BorderLayout.NORTH)
            add(JScrollPane(serverList), BorderLayout.CENTER)
            add(listButtons, BorderLayout.SOUTH)
        }

        val form = JPanel(GridLayout(0, 2, 8, 8)).apply {
            add(JLabel("Name"))
            add(nameField)
            add(JLabel("Base URL"))
            add(baseUrlField)
            add(JLabel("API Key"))
            add(apiKeyField)
            add(JLabel("Timeout (seconds)"))
            add(timeoutField)
            add(JLabel("Message path"))
            add(messagePathField)
            add(JLabel("SSE path"))
            add(ssePathField)
            add(JLabel("Status"))
            add(enabledCheckBox)
            add(JLabel("Transport"))
            add(httpPostTransportCheckBox)
        }

        val formButtons = JPanel(GridLayout(1, 2, 8, 0)).apply {
            add(saveButton)
            add(testButton)
        }

        val rightPanel = JPanel(BorderLayout(0, 8)).apply {
            add(form, BorderLayout.CENTER)
            add(formButtons, BorderLayout.SOUTH)
        }

        rootPanel = JPanel(BorderLayout(12, 0)).apply {
            add(leftPanel, BorderLayout.WEST)
            add(rightPanel, BorderLayout.CENTER)
        }

        reset()
        return rootPanel as JPanel
    }

    override fun isModified(): Boolean {
        val settingsService = resolveSettingsService() ?: return false
        val persisted = settingsService.getServers()
        return persisted != servers
    }

    override fun apply() {
        val settingsService = resolveSettingsService() ?: return
        val selected = serverList?.selectedIndex ?: -1
        if (selected in servers.indices) {
            formToConfig()?.let { servers[selected] = it }
        }
        settingsService.setServers(servers)
    }

    override fun reset() {
        val settingsService = resolveSettingsService()
        val loaded = settingsService?.getServers().orEmpty()
        servers.clear()
        servers += loaded
        refreshServerList()
        if (servers.isNotEmpty()) {
            serverList?.selectedIndex = 0
            loadSelectedServerIntoForm()
        } else {
            clearForm()
        }
    }

    override fun disposeUIResources() {
        rootPanel = null
        serverListModel = null
        serverList = null
        nameField = null
        baseUrlField = null
        apiKeyField = null
        timeoutField = null
        messagePathField = null
        ssePathField = null
        enabledCheckBox = null
        httpPostTransportCheckBox = null
    }

    private fun resolveSettingsService(): McpSettingsService? {
        return project.service<McpSettingsService>()
    }

    private fun refreshServerList() {
        val model = serverListModel ?: return
        model.clear()
        servers.forEach { model.addElement(it.name) }
    }

    private fun loadSelectedServerIntoForm() {
        val selected = serverList?.selectedIndex ?: -1
        if (selected !in servers.indices) return

        val server = servers[selected]
        nameField?.text = server.name
        baseUrlField?.text = server.baseUrl
        apiKeyField?.text = server.apiKey.orEmpty()
        timeoutField?.text = server.timeoutSeconds.toString()
        messagePathField?.text = server.messagePath.orEmpty()
        ssePathField?.text = server.ssePath.orEmpty()
        enabledCheckBox?.isSelected = server.enabled
        httpPostTransportCheckBox?.isSelected = server.transport == McpTransport.HTTP_POST
    }

    private fun clearForm() {
        nameField?.text = ""
        baseUrlField?.text = ""
        apiKeyField?.text = ""
        timeoutField?.text = "20"
        messagePathField?.text = "/mcp"
        ssePathField?.text = "/sse"
        enabledCheckBox?.isSelected = true
        httpPostTransportCheckBox?.isSelected = false
    }

    private fun formToConfig(): McpServerConfig? {
        val name = nameField?.text?.trim().orEmpty()
        val baseUrl = baseUrlField?.text?.trim().orEmpty()
        if (name.isBlank() || baseUrl.isBlank()) return null

        val timeoutSeconds = timeoutField?.text?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: 20
        val apiKey = apiKeyField?.text?.trim()?.takeIf { it.isNotBlank() }
        val messagePath = messagePathField?.text?.trim()?.takeIf { it.isNotBlank() }
        val ssePath = ssePathField?.text?.trim()?.takeIf { it.isNotBlank() }
        val transport = if (httpPostTransportCheckBox?.isSelected == true) {
            McpTransport.HTTP_POST
        } else {
            McpTransport.HTTP_SSE
        }

        return McpServerConfig(
            name = name,
            baseUrl = baseUrl,
            enabled = enabledCheckBox?.isSelected != false,
            transport = transport,
            apiKey = apiKey,
            timeoutSeconds = timeoutSeconds,
            messagePath = messagePath,
            ssePath = ssePath,
        )
    }
}