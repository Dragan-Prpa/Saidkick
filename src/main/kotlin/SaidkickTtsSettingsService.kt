package com.example

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

data class ElevenLabsVoiceOption(
    val label: String,
    val voiceId: String,
) {
    override fun toString(): String = label
}

@Service(Service.Level.APP)
@State(name = "SaidkickTtsSettings", storages = [Storage("saidkick-tts-settings.xml")])
class SaidkickTtsSettingsService : PersistentStateComponent<SaidkickTtsSettingsService.State> {
    class State {
        var selectedVoiceId: String = ""
    }

    private var state = State()

    val voiceOptions: List<ElevenLabsVoiceOption> = listOf(
        ElevenLabsVoiceOption("Adam", "pNInz6obpgDQGcFmaJgB"),
        ElevenLabsVoiceOption("Antoni", "ErXwobaYiN019PkySvjV"),
        ElevenLabsVoiceOption("Arnold", "VR6AewLTigWG4xSOukaG"),
        ElevenLabsVoiceOption("Bella", "EXAVITQu4vr4xnSDxMaL"),
        ElevenLabsVoiceOption("Charlie", "IKne3meq5aSn9XLyUdCD"),
        ElevenLabsVoiceOption("Domi", "AZnzlk1XvdvUeBnXmlld"),
        ElevenLabsVoiceOption("Elli", "MF3mGyEYCl7XYWbV9V6O"),
        ElevenLabsVoiceOption("Josh", "TxGEqnHWrfWFTfGW9XjX"),
        ElevenLabsVoiceOption("Rachel", "21m00Tcm4TlvDq8ikWAM"),
        ElevenLabsVoiceOption("Sam", "yoZ06aMxZJJ28mfd3POQ"),
    )

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    fun getSelectedVoiceId(): String? {
        return state.selectedVoiceId.takeIf { it.isNotBlank() }
    }

    fun setSelectedVoiceId(voiceId: String) {
        state.selectedVoiceId = voiceId
    }

    companion object {
        fun getInstance(): SaidkickTtsSettingsService {
            return ApplicationManager.getApplication().getService(SaidkickTtsSettingsService::class.java)
        }
    }
}
