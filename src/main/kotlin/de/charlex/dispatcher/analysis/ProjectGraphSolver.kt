package de.charlex.dispatcher.analysis

import com.intellij.openapi.progress.ProgressManager
import de.charlex.dispatcher.model.Dispatcher
import de.charlex.dispatcher.model.DispatcherSet
import de.charlex.dispatcher.model.EffectSummary
import de.charlex.dispatcher.model.PathRelation

internal class ProjectGraphSolver {
    fun solve(
        environment: String,
        files: Map<String, FileGraph>,
        previous: CachedProjectAnalysis?,
        changedFiles: Set<String>,
    ): CachedProjectAnalysis {
        val bodies = files.values.flatMap { it.functions }.associateBy { it.key }
        val calls = files.values.flatMap { it.calls }
        val oldCalls = previous?.files?.values.orEmpty().flatMap { it.calls }
        val edges = (calls + oldCalls).filter { it.owner != null && it.target != null }
        val seeds = (bodies.keys + previous?.summaries.orEmpty().keys).filterTo(linkedSetOf()) { it.file in changedFiles }
        val effectAffected = closure(seeds, edges.map { it.target!! to it.owner!! }).intersect(bodies.keys)
        val incomingSeeds = seeds + (calls + oldCalls).filter { it.file in changedFiles }.mapNotNull { it.target }
        val unresolved = files.values.flatMap { it.unresolvedNames }.toSet()
        val oldUnresolved = previous?.files?.values.orEmpty().flatMap { it.unresolvedNames }.toSet()
        val escaped = files.values.flatMap { it.escapingTargets }.toSet()
        val oldEscaped = previous?.files?.values.orEmpty().flatMap { it.escapingTargets }.toSet()
        val evidenceChanges = bodies.values.filter { body ->
            (body.name in unresolved) != (body.name in oldUnresolved) ||
                (body.key in escaped) != (body.key in oldEscaped)
        }.map { it.key }
        val incomingAffected = closure(incomingSeeds + evidenceChanges, edges.map { it.owner!! to it.target!! }).intersect(bodies.keys)
        val summaries = bodies.keys.associateWithTo(linkedMapOf()) { key ->
            if (key in effectAffected || previous == null) EffectSummary.EMPTY else previous.summaries[key] ?: EffectSummary.EMPTY
        }
        val effectsToSolve = if (previous == null) bodies.keys else effectAffected
        fun solveEffects() {
            do {
                var changed = false
                effectsToSolve.forEach { key ->
                    ProgressManager.checkCanceled()
                    val next = summaries.getValue(key).join(evaluate(bodies.getValue(key).effect, summaries))
                    if (next != summaries[key]) { summaries[key] = next; changed = true }
                }
            } while (changed)
        }
        solveEffects()
        effectsToSolve.forEach { key ->
            val summary = summaries.getValue(key)
            if (summary.dispatchers.isEmpty) summaries[key] = EffectSummary(
                DispatcherSet.unknown("No executable dispatcher evidence was found"), summary.pathRelations, summary.selectedDispatchers,
            )
        }
        solveEffects()
        val incomingToSolve = if (previous == null) bodies.keys else incomingAffected
        val incoming = bodies.mapValuesTo(linkedMapOf()) { (key, body) ->
            if (key !in incomingToSolve) previous?.incoming?.get(key) ?: DispatcherSet.EMPTY else {
                val reasons = linkedSetOf<String>()
                if (body.openEntry) reasons += "External callers may use another dispatcher"
                if (body.name in unresolved) reasons += "An unresolved call may target this declaration"
                if (key in escaped) reasons += "A callable reference may escape the analyzed call graph"
                DispatcherSet(unknownReasons = reasons)
            }
        }
        fun solveIncoming() {
            do {
                var changed = false
                calls.forEach { call ->
                    ProgressManager.checkCanceled()
                    val target = call.target ?: return@forEach
                    if (target !in incomingToSolve) return@forEach
                    val owner = call.owner?.let(incoming::get) ?: DispatcherSet.EMPTY
                    val next = incoming.getValue(target).join(substitute(call.context, owner))
                    if (next != incoming[target]) { incoming[target] = next; changed = true }
                }
            } while (changed)
        }
        solveIncoming()
        incomingToSolve.forEach { key ->
            if (incoming.getValue(key).isEmpty) incoming[key] = DispatcherSet.unknown("No proven callers were found")
        }
        solveIncoming()
        val calledTargets = calls.mapNotNull { it.target }.toSet()
        val results = files.mapValues { (_, graph) ->
            ProgressManager.checkCanceled()
            val declarations = graph.functions.filter { it.key in calledTargets || it.name in unresolved }.associate { body ->
                val summary = EffectSummary(incoming.getValue(body.key))
                body.key.offset to BadgeResult(summary, tooltip(summary, true))
            }
            val callResults = graph.calls.associate { call ->
                val owner = call.owner?.let(incoming::get) ?: DispatcherSet.EMPTY
                val summary = evaluate(call.effect, summaries).substitute(owner).let {
                    if (Dispatcher.Inherited in it.dispatchers.known) it.substitute(DispatcherSet.unknown("Caller's context is unknown")) else it
                }
                call.offset to BadgeResult(summary, tooltip(summary, false))
            }
            val result = FileAnalysis(immutable(declarations), immutable(callResults))
            previous?.results?.get(graph.fileUrl)?.takeIf { it == result } ?: result
        }
        return CachedProjectAnalysis(environment, immutable(files), immutable(summaries), immutable(incoming), immutable(results))
    }

    private fun evaluate(effect: Effect, summaries: Map<FunctionKey, EffectSummary>): EffectSummary {
        ProgressManager.checkCanceled()
        return when (effect) {
            is Effect.Work -> effect.summary
            is Effect.ContextSelection -> evaluate(effect.effect, summaries).let {
                EffectSummary(
                    it.dispatchers,
                    it.pathRelations,
                    it.selectedDispatchers.join(effect.selectedDispatchers)
                        .join(DispatcherSet(unknownReasons = it.dispatchers.unknownReasons)),
                )
            }
            is Effect.Invoke -> summaries[effect.target]?.let {
                if (it.dispatchers.isEmpty) it else it.substitute(effect.context)
            } ?: unknown("Callee was outside the analyzed graph")
            is Effect.Group -> {
                val children = effect.effects.map { evaluate(it, summaries) }.filterNot { it.dispatchers.isEmpty && !it.setsDispatcher }
                val joined = children.fold(EffectSummary.EMPTY, EffectSummary::join)
                if (joined.dispatchers.known.size > 1 && (effect.branch || children.size > 1)) EffectSummary(
                    joined.dispatchers,
                    joined.pathRelations + if (effect.branch) PathRelation.BRANCH_ALTERNATIVES else PathRelation.CONTEXT_SWITCH,
                    joined.selectedDispatchers,
                ) else joined
            }
        }
    }

    companion object {
        fun closure(seeds: Collection<FunctionKey>, edges: List<Pair<FunctionKey, FunctionKey>>): Set<FunctionKey> {
            val links = edges.groupBy({ it.first }, { it.second })
            val result = seeds.toMutableSet()
            val queue = ArrayDeque(seeds)
            while (queue.isNotEmpty()) {
                ProgressManager.checkCanceled()
                links[queue.removeFirst()].orEmpty().forEach { if (result.add(it)) queue.addLast(it) }
            }
            return result
        }

        private fun substitute(context: DispatcherSet, incoming: DispatcherSet): DispatcherSet =
            if (Dispatcher.Inherited !in context.known) context else
                DispatcherSet(context.known - Dispatcher.Inherited, context.unknownReasons, context.origins).join(incoming)

        private fun unknown(reason: String) = EffectSummary(DispatcherSet.unknown(reason))
        private fun tooltip(summary: EffectSummary, declaration: Boolean): String = buildString {
            append(if (declaration) "Possible incoming dispatcher contexts. " else "Dispatcher contexts for callee execution. ")
            if (!declaration) {
                if (PathRelation.CONTEXT_SWITCH in summary.pathRelations) append("Includes dispatcher switches within a path. ")
                if (PathRelation.BRANCH_ALTERNATIVES in summary.pathRelations) append("Includes alternative execution paths. ")
                if (summary.dispatchers.hasUnknown) append("Coverage is unknown. ")
            }
            append(summary.dispatchers.unknownReasons.sorted().joinToString(". "))
            append(" A dispatcher badge is not a thread-safety guarantee.")
        }
    }
}

internal fun <K, V> immutable(map: Map<K, V>): Map<K, V> = java.util.Collections.unmodifiableMap(LinkedHashMap(map))
