package de.charlex.dispatcher.analysis

import com.intellij.util.messages.Topic

interface DispatcherAnalysisListener {
    fun analysisUpdated()

    companion object {
        @Topic.ProjectLevel
        @JvmField
        val TOPIC = Topic.create("Dispatcher analysis completed", DispatcherAnalysisListener::class.java)
    }
}
