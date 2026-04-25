package com.example

import com.intellij.openapi.components.Service
import java.util.concurrent.atomic.AtomicInteger

@Service(Service.Level.PROJECT)
class SavedChangeCounterService {
    private val savedChangesSinceCommit = AtomicInteger(0)

    fun incrementAndGet(): Int = savedChangesSinceCommit.incrementAndGet()

    fun get(): Int = savedChangesSinceCommit.get()

    fun reset() {
        savedChangesSinceCommit.set(0)
    }
}
