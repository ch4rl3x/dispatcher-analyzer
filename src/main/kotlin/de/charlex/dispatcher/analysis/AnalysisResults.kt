package de.charlex.dispatcher.analysis

import de.charlex.dispatcher.model.EffectSummary

data class BadgeResult(val summary: EffectSummary, val tooltip: String)

data class FileAnalysis(
    val declarations: Map<Int, BadgeResult> = emptyMap(),
    val calls: Map<Int, BadgeResult> = emptyMap(),
)

internal data class FunctionKey(val file: String, val offset: Int)

internal data class SourceCall(
    val file: String,
    val offset: Int,
    val owner: FunctionKey?,
    val target: FunctionKey?,
    val context: de.charlex.dispatcher.model.DispatcherSet,
    val effect: Effect,
)

internal sealed interface Effect {
    data class Work(val summary: EffectSummary) : Effect
    data class Invoke(
        val target: FunctionKey,
        val context: de.charlex.dispatcher.model.DispatcherSet,
    ) : Effect
    data class Group(val effects: List<Effect>, val branch: Boolean = false) : Effect
}
