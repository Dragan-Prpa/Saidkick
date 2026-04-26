package com.example

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager

class ExplainSelectionAction : AnAction("Explain with Saidkick") {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectedText = editor.selectionModel.selectedText?.trim().orEmpty()
        if (selectedText.isBlank()) return

        val prompt = """
            Explain the following selected code. Keep the explanation clear and practical:

            ```
            $selectedText
            ```
        """.trimIndent()

        ToolWindowManager.getInstance(project).getToolWindow("MyToolWindow")?.show()
        MyToolWindowFactory.getWindow(project)?.submitExternalPrompt(prompt)
    }

    override fun update(e: AnActionEvent) {
        val hasSelection = e.getData(CommonDataKeys.EDITOR)
            ?.selectionModel
            ?.hasSelection() == true
        e.presentation.isEnabledAndVisible = hasSelection
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
