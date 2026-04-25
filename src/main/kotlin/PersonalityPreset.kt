package com.example

enum class PersonalityPreset(private val key: String) {
    COACH("coach"),
    ARCHITECT("architect"),
    CHEERLEADER("cheerleader"),
    REVIEWER("reviewer");

    fun stylePrefix(): String = when (this) {
        COACH -> "[Coach]"
        ARCHITECT -> "[Architect]"
        CHEERLEADER -> "[Cheerleader]"
        REVIEWER -> "[Reviewer]"
    }

    companion object {
        fun fromValue(value: String?): PersonalityPreset {
            val normalized = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.key == normalized } ?: COACH
        }
    }
}
