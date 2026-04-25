package com.example

enum class PersonalityPreset(private val key: String) {
    COACH("coach"),
    ARCHITECT("architect"),
    CHEERLEADER("cheerleader"),
    REVIEWER("reviewer");

    companion object {
        fun fromValue(value: String?): PersonalityPreset {
            val normalized = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.key == normalized } ?: COACH
        }
    }
}
