package com.example

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JPanel

class MyToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val myToolWindow = MyToolWindow(project)
        val content = ContentFactory.getInstance().createContent(myToolWindow.getContent(), null, false)
        val disposable = Disposer.newDisposable("SaidkickToolWindow")
        content.setDisposer(disposable)
        myToolWindow.start(disposable)
        toolWindow.contentManager.addContent(content)
    }

    class MyToolWindow(private val project: Project) {
        private val config = AssistantConfig.fromEnv(project)
        private val assistant = SaidkickAssistant(config)
        private val conversationArea = JBTextArea()
        private val inputField = JBTextField()
        private val sendButton = JButton(MyMessageBundle.message("toolwindow.Saidkick.send.button"))

        private val content = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            conversationArea.isEditable = false
            conversationArea.lineWrap = true
            conversationArea.wrapStyleWord = true

            val center = JBScrollPane(conversationArea)
            val south = JPanel(BorderLayout()).apply {
                add(inputField, BorderLayout.CENTER)
                add(sendButton, BorderLayout.EAST)
            }

            add(center, BorderLayout.CENTER)
            add(south, BorderLayout.SOUTH)
        }

        init {
            sendButton.addActionListener { sendInput() }
            inputField.addActionListener { sendInput() }
        }

        fun start(parentDisposable: Disposable) {
            val activityMonitor = ActivityMonitor(
                project = project,
                config = config,
                onIdle = { pushAssistantMessage(assistant.respondToInternalPrompt(it)) },
                onChangeBurst = { pushAssistantMessage(assistant.respondToInternalPrompt(it)) },
            )
            activityMonitor.start(parentDisposable)

            appendLine("Saidkick runtime: ${runtimeSignature()}")
            if (config.requiresIdentitySetup) {
                pushAssistantMessage(identitySetupMessage())
            } else {
                pushAssistantMessage("Hello ${config.developerName}, I am ${config.assistantName}.")
            }
        }

        private fun sendInput() {
            val text = inputField.text.trim()
            if (text.isBlank()) return

            pushUserMessage(text)
            inputField.text = ""

            val reply = assistant.respondTo(text)
            pushAssistantMessage(reply)
        }

        private fun pushUserMessage(message: String) {
            appendLine("You: $message")
        }

        private fun pushAssistantMessage(message: String) {
            appendLine("${config.assistantName}: $message")
        }

        private fun identitySetupMessage(): String {
            return """
                Hello Developer, I am Saidkick. I noticed your project .env is missing identity settings.
                Please create or update <project>/.env with:

                ASSISTANT_NAME=Saidkick (or your preferred assistant name)

                DEVELOPER_NAME=YourName

                ASSISTANT_PERSONALITY=coach (allowed: coach, architect, cheerleader, reviewer)

                Call these variables exactly as written.
                After saving, fully exit and re-enter the IDE so I can read the new env values.
            """.trimIndent()
        }

        private fun appendLine(line: String) {
            if (conversationArea.text.isNotBlank()) {
                conversationArea.append("\n")
            }
            conversationArea.append(line)
        }

        private fun runtimeSignature(): String {
            val descriptor = PluginManagerCore.getPlugin(PluginId.getId("com.example.Saidkick"))
            val pluginVersion = descriptor?.version
                ?: MyToolWindowFactory::class.java.`package`?.implementationVersion
                ?: "dev"
            val location = runCatching {
                descriptor?.pluginPath?.fileName?.toString()
                    ?: MyToolWindowFactory::class.java.protectionDomain.codeSource.location.toString()
            }.getOrElse { "unknown-artifact" }
            return "version=$pluginVersion artifact=$location"
        }

        fun getContent(): JBPanel<JBPanel<*>> = content
    }
}
