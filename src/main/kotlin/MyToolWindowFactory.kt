package com.example

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JButton
import javax.swing.JPanel

class MyToolWindowFactory : ToolWindowFactory {
    companion object {
        private val windowsByProject = ConcurrentHashMap<Project, MyToolWindow>()

        fun getWindow(project: Project): MyToolWindow? = windowsByProject[project]
    }

    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val myToolWindow = MyToolWindow(project)
        windowsByProject[project] = myToolWindow
        val content = ContentFactory.getInstance().createContent(myToolWindow.getContent(), null, false)
        val disposable = Disposer.newDisposable("SaidkickToolWindow")
        content.setDisposer(disposable)
        Disposer.register(disposable) {
            windowsByProject.remove(project)
        }
        myToolWindow.start(disposable)
        toolWindow.contentManager.addContent(content)
    }

    class MyToolWindow(private val project: Project) {
        private val config = AssistantConfig.fromEnv(project)
        private val assistant = SaidkickAssistant(project = project, config = config)
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
                onIdle = { postProactiveReply(it) },
                onChangeBurst = { postProactiveReply(it) },
            )
            activityMonitor.start(parentDisposable)

            if (config.requiresIdentitySetup) {
                pushAssistantMessage(identitySetupMessage())
            } else {
                pushAssistantMessage("Hello ${config.developerName}, ${config.assistantName} here. Ready to do some coding?")
            }
        }

        private fun postProactiveReply(internalPrompt: String) {
            val reply = assistant.respondToInternalPrompt(internalPrompt)
            if (reply.isNotBlank()) {
                pushAssistantMessage(reply)
                SaidkickNotifications.info(project, config.assistantName, reply)
            }
        }



        private fun sendInput() {
            val text = inputField.text.trim()
            if (text.isBlank()) return

            inputField.text = ""
            submitPrompt(text)
        }

        fun submitExternalPrompt(prompt: String) {
            submitPrompt(prompt.trim())
        }

        fun generateWholeProjectDocumentation() {
            val configuredOutputPath = config.docsOutputPath
            if (configuredOutputPath.isNullOrBlank()) {
                val message = "DOCS_OUTPUT_PATH is not set in the opened project's .env."
                pushAssistantMessage(message)
                SaidkickNotifications.warning(project, config.assistantName, message)
                return
            }

            val prompt = """
                Generate Markdown documentation for the whole project from the provided context.
                Include sections: Overview, Project Structure, Core Components, Configuration, and Usage Notes.
                Keep it practical and concise.
            """.trimIndent()

            val generatedDocumentation = assistant.respondTo(prompt)
            if (generatedDocumentation.startsWith("[Error]")) {
                pushAssistantMessage(generatedDocumentation)
                SaidkickNotifications.error(project, config.assistantName, generatedDocumentation)
                return
            }

            val outputPath = resolveOutputPath(configuredOutputPath)
            runCatching {
                outputPath.parent?.let { Files.createDirectories(it) }
                Files.writeString(outputPath, generatedDocumentation)
            }.onSuccess {
                val successMessage = "Generated project documentation at ${outputPath.toAbsolutePath()}"
                pushAssistantMessage(successMessage)
                SaidkickNotifications.info(project, config.assistantName, successMessage)
            }.onFailure { error ->
                val failureMessage = "Failed to write documentation: ${error.message.orEmpty()}"
                pushAssistantMessage(failureMessage)
                SaidkickNotifications.error(project, config.assistantName, failureMessage)
            }
        }

        private fun submitPrompt(text: String) {
            if (text.isBlank()) return

            pushUserMessage(text)
            if (isDocsGenerationCommand(text)) {
                pushAssistantMessage("Got it — generating whole-project documentation now.")
                generateWholeProjectDocumentation()
                return
            }

            val reply = assistant.respondTo(text)
            pushAssistantMessage(reply)
        }

        private fun isDocsGenerationCommand(text: String): Boolean {
            val normalized = text
                .trim()
                .lowercase(Locale.getDefault())

            if (normalized.startsWith("/generate-docs") || normalized.startsWith("/docs")) {
                return true
            }

            val hasGenerateVerb = listOf("generate", "create", "write", "make")
                .any { normalized.contains(it) }
            val hasDocsNoun = listOf("documentation", "docs", "readme")
                .any { normalized.contains(it) }
            val hasProjectScope = listOf("whole project", "entire project", "project")
                .any { normalized.contains(it) }
            val directDocsIntent = listOf(
                "generate documentation",
                "generate docs",
                "create documentation",
                "create docs",
                "write documentation",
                "write docs",
                "make documentation",
                "make docs",
            ).any { normalized.contains(it) }

            return directDocsIntent || (hasGenerateVerb && hasDocsNoun && hasProjectScope)
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

        private fun resolveOutputPath(configuredOutputPath: String): Path {
            val candidate = Path.of(configuredOutputPath)
            if (candidate.isAbsolute) return candidate
            val basePath = project.basePath ?: return candidate
            return Path.of(basePath).resolve(candidate).normalize()
        }

        private fun appendLine(line: String) {
            if (conversationArea.text.isNotBlank()) {
                conversationArea.append("\n\n")
            }
            conversationArea.append(line)
        }

        fun getContent(): JBPanel<JBPanel<*>> = content
    }
}
