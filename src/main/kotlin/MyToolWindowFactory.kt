package com.example

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.Color
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

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
        private val conversationArea = JTextPane()
        private val inputField = JBTextField()
        private val sendButton = JButton(MyMessageBundle.message("toolwindow.Saidkick.send.button"))
        private val assistantTagColor = resolveColor(config.assistantColor, Color(58, 120, 246))
        private val developerTagColor = resolveColor(config.developerColor, Color(56, 142, 60))

        private val content = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            conversationArea.isEditable = false

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

        private fun submitPrompt(text: String) {
            if (text.isBlank()) return

            pushUserMessage(text)
            val reply = assistant.respondTo(text)
            pushAssistantMessage(reply)
        }

        private fun pushUserMessage(message: String) {
            appendMessage(tag = "You:", body = message, tagColor = developerTagColor)
        }

        private fun pushAssistantMessage(message: String) {
            appendMessage(tag = "${config.assistantName}:", body = message, tagColor = assistantTagColor)
        }


        private fun identitySetupMessage(): String {
            return """
                Hello Developer, I am Saidkick. I noticed your project .env is missing identity settings.
                Please create or update <project>/.env with:

                ASSISTANT_NAME=Saidkick (or your preferred assistant name)

                DEVELOPER_NAME=YourName

                ASSISTANT_COLOR=yellow (example color names: red, green, yellow, purple, brown)

                DEVELOPER_COLOR=red (example color names: red, green, yellow, purple, brown)

                ASSISTANT_PERSONALITY=coach (allowed: coach, architect, cheerleader, reviewer)

                Call these variables exactly as written.
                After saving, fully exit and re-enter the IDE so I can read the new env values.
            """.trimIndent()
        }

        private fun appendMessage(tag: String, body: String, tagColor: Color) {
            val document = conversationArea.styledDocument
            if (document.length > 0) {
                document.insertString(document.length, "\n\n", normalStyle())
            }
            document.insertString(document.length, "$tag ", tagStyle(tagColor))
            document.insertString(document.length, body, normalStyle())
            conversationArea.caretPosition = document.length
        }

        private fun normalStyle(): SimpleAttributeSet {
            return SimpleAttributeSet().apply {
                StyleConstants.setForeground(this, conversationArea.foreground)
            }
        }

        private fun tagStyle(color: Color): SimpleAttributeSet {
            return SimpleAttributeSet().apply {
                StyleConstants.setForeground(this, color)
            }
        }

        private fun resolveColor(name: String, defaultColor: Color): Color {
            return when (name.trim().lowercase()) {
                "black" -> Color.BLACK
                "blue" -> Color.BLUE
                "brown" -> Color(121, 85, 72)
                "cyan" -> Color.CYAN
                "gray", "grey" -> Color.GRAY
                "green" -> Color(56, 142, 60)
                "magenta", "purple" -> Color(123, 31, 162)
                "orange" -> Color.ORANGE
                "pink" -> Color.PINK
                "red" -> Color.RED
                "white" -> Color.WHITE
                "yellow" -> Color(249, 168, 37)
                else -> defaultColor
            }
        }

        fun getContent(): JBPanel<JBPanel<*>> = content
    }
}
