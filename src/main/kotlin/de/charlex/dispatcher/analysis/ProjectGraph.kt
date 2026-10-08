package de.charlex.dispatcher.analysis

import de.charlex.dispatcher.model.DispatcherSet
import de.charlex.dispatcher.model.EffectSummary

internal data class FileGraph(
    val fileUrl: String,
    val contentHash: String,
    val structureHash: String,
    val dependencies: Set<String>,
    val functions: List<FunctionBody>,
    val calls: List<SourceCall>,
    val unresolvedNames: Set<String>,
    val escapingTargets: Set<FunctionKey>,
)

internal data class CachedProjectAnalysis(
    val environmentFingerprint: String,
    val files: Map<String, FileGraph>,
    val summaries: Map<FunctionKey, EffectSummary>,
    val incoming: Map<FunctionKey, DispatcherSet>,
    val results: Map<String, FileAnalysis>,
)
