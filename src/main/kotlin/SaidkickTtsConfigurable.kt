package com.example

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

class SaidkickTtsConfigurable : SearchableConfigurable, Configurable.NoScroll {
    private val settings = SaidkickTtsSettingsService.getInstance()
    private var panel: JPanel? = null
    private var voiceComboBox: JComboBox<ElevenLabsVoiceOption>? = null

    override fun getId(): String = "com.example.Saidkick.settings.tts"

    override fun getDisplayName(): String = "Saidkick"

    override fun createComponent(): JComponent {
        if (panel == null) {
            val combo = JComboBox(settings.voiceOptions.toTypedArray())
            voiceComboBox = combo

            val root = JBPanel<JBPanel<*>>(BorderLayout())
            val content = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JBLabel("Assistant voice"))
                add(combo)
            }

            root.add(content, BorderLayout.NORTH)
            panel = root
        }

        reset()
        return panel!!
    }

    override fun isModified(): Boolean {
        val combo = voiceComboBox ?: return false
        val selectedId = (combo.selectedItem as? ElevenLabsVoiceOption)?.voiceId.orEmpty()
        val storedId = settings.getSelectedVoiceId().orEmpty()
        return selectedId != storedId
    }

    override fun apply() {
        val combo = voiceComboBox ?: return
        val selectedId = (combo.selectedItem as? ElevenLabsVoiceOption)?.voiceId ?: return
        settings.setSelectedVoiceId(selectedId)
    }

    override fun reset() {
        val combo = voiceComboBox ?: return
        val selectedId = settings.getSelectedVoiceId()
        val selectedOption = settings.voiceOptions.firstOrNull { it.voiceId == selectedId }
            ?: settings.voiceOptions.firstOrNull()

        if (selectedOption != null) {
            combo.selectedItem = selectedOption
        }
    }

    override fun disposeUIResources() {
        panel = null
        voiceComboBox = null
    }
}
