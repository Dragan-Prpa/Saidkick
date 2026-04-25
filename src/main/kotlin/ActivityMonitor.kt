package com.example

import com.intellij.openapi.components.service
import com.intellij.openapi.Disposable
import com.intellij.AppTopics
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent

class ActivityMonitor(
    private val project: Project,
    private val config: AssistantConfig,
    private val onIdle: (String) -> Unit,
    private val onChangeBurst: (String) -> Unit,
) {
    @Volatile
    private var lastActivityTimestamp: Long = System.currentTimeMillis()

    private var idleNotified = false
    private var burstNotified = false

    fun start(parentDisposable: Disposable) {
        val activityListener = AWTEventListener { event ->
            if (event is KeyEvent || event is MouseEvent) {
                lastActivityTimestamp = System.currentTimeMillis()
                idleNotified = false
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(
            activityListener,
            AWTEvent.KEY_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK,
        )

        val counterService = project.service<SavedChangeCounterService>()

        val saveListener = object : FileDocumentManagerListener {
            override fun beforeDocumentSaving(document: com.intellij.openapi.editor.Document) {
                val now = System.currentTimeMillis()
                lastActivityTimestamp = now
                idleNotified = false

                val savedChangeCount = counterService.incrementAndGet()
                if (!burstNotified && savedChangeCount > 100) { //PRPA_COMMENT:change counter here
                    burstNotified = true
                    onChangeBurst("Developer has been coding for some time; remind them to commit.")
                }
            }
        }
        project.messageBus.connect(parentDisposable).subscribe(AppTopics.FILE_DOCUMENT_SYNC, saveListener)

        val timer = javax.swing.Timer(5_000) {
            val now = System.currentTimeMillis()
            if (counterService.get() <= 100) {    //PRPA_COMMENT:change counter var here
                burstNotified = false
            }

            val idleMillis = config.idleThresholdSeconds * 1_000L
            if (!idleNotified && now - lastActivityTimestamp >= idleMillis) {
                idleNotified = true
                onIdle("Developer has been idle for some time ask them if they need help.")
            }
        }
        timer.start()

        parentDisposable.whenDisposed {
            timer.stop()
            Toolkit.getDefaultToolkit().removeAWTEventListener(activityListener)
        }
    }
}

private fun Disposable.whenDisposed(action: () -> Unit) {
    com.intellij.openapi.util.Disposer.register(this) { action() }
}
