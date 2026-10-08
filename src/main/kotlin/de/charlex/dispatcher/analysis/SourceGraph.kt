package de.charlex.dispatcher.analysis

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import de.charlex.dispatcher.model.Dispatcher
import de.charlex.dispatcher.model.DispatcherSet
import de.charlex.dispatcher.model.EffectSummary
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*

internal data class FunctionBody(
    val key: FunctionKey,
    val declaration: KtNamedFunction,
    val effect: Effect,
    val openEntry: Boolean,
)

internal class AnalysisProgress {
    fun consume() {
        ProgressManager.checkCanceled()
    }
}

internal class SourceGraph(
    private val api: KotlinAnalysisAdapter,
    private val functions: Set<FunctionKey>,
    private val progress: AnalysisProgress,
) {
    private val contexts = ContextResolver(api)
    val calls = mutableListOf<SourceCall>()
    val unresolvedNames = mutableSetOf<String>()
    val escapingTargets = mutableSetOf<FunctionKey>()

    fun body(function: KtNamedFunction): FunctionBody {
        val key = key(function)
        val suspend = function.hasModifier(KtTokens.SUSPEND_KEYWORD)
        val inherited = if (suspend) DispatcherSet.of(Dispatcher.Inherited)
            else DispatcherSet.unknown("Non-suspend entry context is unknown")
        val rawEffect = if (function.bodyExpression == null) unknown("Function body is unavailable")
            else ensureExecution(walk(function.bodyExpression, inherited, null, if (suspend) key else null), inherited)
        val effect = if (PsiTreeUtil.hasErrorElements(function)) group(listOf(rawEffect, unknown("Incomplete Kotlin code")))
            else rawEffect
        return FunctionBody(
            key,
            function,
            effect,
            !function.hasModifier(KtTokens.PRIVATE_KEYWORD) && !function.isLocal,
        )
    }

    fun topLevel(element: PsiElement) {
        walk(element, DispatcherSet.unknown("Initialization context is unknown"), null, null)
    }

    private fun walk(
        element: PsiElement?,
        context: DispatcherSet,
        scope: DispatcherSet?,
        owner: FunctionKey?,
    ): Effect {
        if (element == null) return EMPTY
        progress.consume()
        return when (element) {
            is KtNamedFunction, is KtClassOrObject -> EMPTY
            is KtCallableReferenceExpression -> {
                api.referencedFunction(element)?.let { escapingTargets += key(it) }
                EMPTY
            }
            is KtLambdaExpression -> {
                walk(element.bodyExpression, DispatcherSet.unknown("Deferred lambda invocation context is unknown"), null, null)
                EMPTY
            }
            is KtBlockExpression -> group(element.statements.map { walk(it, context, scope, owner) })
            is KtCallExpression -> call(element, context, scope, owner)
            is KtQualifiedExpression -> group(listOf(
                walk(element.receiverExpression, context, scope, owner),
                walk(element.selectorExpression, context, scope, owner),
            ))
            is KtReturnExpression -> walk(element.returnedExpression, context, scope, owner)
            is KtIfExpression -> group(listOf(
                condition(element.condition, context, scope, owner),
                Effect.Group(listOf(
                    walk(element.then, context, scope, owner),
                    walk(element.`else`, context, scope, owner),
                ), branch = true),
            ))
            is KtWhenExpression -> group(listOf(
                condition(element.subjectExpression, context, scope, owner),
                Effect.Group(element.entries.map { entry ->
                    group(entry.conditions.map { walk(it, context, scope, owner) } +
                        walk(entry.expression, context, scope, owner))
                }, branch = true),
            ))
            is KtTryExpression -> group(listOf(
                Effect.Group(listOf(walk(element.tryBlock, context, scope, owner)) +
                    element.catchClauses.map { walk(it.catchBody, context, scope, owner) }, branch = true),
                walk(element.finallyBlock?.finalExpression, context, scope, owner),
            ))
            is KtLoopExpression -> group(listOf(work(context)) +
                element.children.map { walk(it, context, scope, owner) })
            is KtProperty -> walk(element.initializer, context, scope, owner)
            is KtBinaryExpression, is KtUnaryExpression, is KtThrowExpression, is KtArrayAccessExpression ->
                group(listOf(work(context)) + element.children.map { walk(it, context, scope, owner) })
            is KtNameReferenceExpression -> {
                val property = api.value(element)?.property
                if (property?.getter != null || property?.hasDelegate() == true) {
                    if ((property.containingFile as? KtFile)?.isCompiled == false) {
                        group(listOf(work(context), unknown("Property accessor effects are not modeled")))
                    } else work(context)
                } else EMPTY
            }
            is KtConstantExpression, is KtThisExpression, is KtTypeReference -> EMPTY
            else -> group(element.children.map { walk(it, context, scope, owner) })
        }
    }

    private fun condition(
        expression: KtExpression?,
        context: DispatcherSet,
        scope: DispatcherSet?,
        owner: FunctionKey?,
    ): Effect = if (expression == null || expression is KtConstantExpression) EMPTY
        else group(listOf(work(context), walk(expression, context, scope, owner)))

    private fun call(
        expression: KtCallExpression,
        context: DispatcherSet,
        scope: DispatcherSet?,
        owner: FunctionKey?,
    ): Effect {
        val resolved = api.call(expression)
        if (resolved?.expect == true) return unknown("Expect declarations are outside the analysis scope")
        val id = resolved?.identity
        val args = resolved?.arguments.orEmpty()
        val block = (args["block"] as? KtLambdaExpression)
            ?: expression.lambdaArguments.firstOrNull()?.getLambdaExpression()
        val ordinaryArguments = expression.valueArguments.mapNotNull { it.getArgumentExpression() }
            .filterNot { it is KtLambdaExpression }
        val argumentEffects = ordinaryArguments.map { argument ->
            if (argument == args["context"]) contextEvaluation(argument, context, scope, owner)
            else walk(argument, context, scope, owner)
        }
        if (id == "kotlinx.coroutines.withContext") {
            val switched = contexts.context(args["context"]).applyTo(context)
            val effect = if (block == null) unknown("withContext body is not an immediate lambda")
                else ensureExecution(walk(block.bodyExpression, switched, switched, owner), switched)
            record(expression, owner, null, context, effect)
            return group(argumentEffects + effect)
        }
        if (id in SCOPES) {
            val effect = if (block == null) unknown("Scope body is not an immediate lambda")
                else walk(block.bodyExpression, context, context, owner)
            record(expression, owner, null, context, effect)
            return group(argumentEffects + effect)
        }
        if (id in BUILDERS) {
            val base = if (resolved?.receiver == null && scope != null) ContextValue(scope, true)
                else contexts.scope(resolved?.receiver)
            val explicit = contexts.context(args["context"])
            val merged = explicit.applyTo(base.dispatchers)
            var childContext = if (merged.isEmpty) DispatcherSet.of(Dispatcher.Default) else merged
            val start = args["start"]
            if (start != null && api.value(start)?.identity != "kotlinx.coroutines.CoroutineStart.DEFAULT") {
                childContext = childContext.join(DispatcherSet.unknown("Unsupported coroutine start mode"))
            }
            if (block != null) walk(block.bodyExpression, childContext, childContext, owner)
            return group(argumentEffects + work(context))
        }
        if (id == "kotlinx.coroutines.runBlocking") {
            val blocked = contexts.context(args["context"])
                .applyTo(DispatcherSet.unknown("runBlocking event-loop context is not modeled"))
            val effect = if (block == null) unknown("runBlocking body is not an immediate lambda")
                else walk(block.bodyExpression, blocked, blocked, owner)
            return group(argumentEffects + effect)
        }

        val lambdaEffects = expression.valueArguments.mapNotNull { it.getArgumentExpression() as? KtLambdaExpression }
            .plus(expression.lambdaArguments.mapNotNull { it.getLambdaExpression() }).distinct().map { lambda ->
                if (resolved?.inline == true && id in EAGER_INLINE) walk(lambda.bodyExpression, context, scope, owner)
                else {
                    walk(lambda.bodyExpression, DispatcherSet.unknown("Callback invocation context is unknown"), null, null)
                    unknown("Callback execution effects are not modeled")
                }
            }
        if (resolved == null) {
            expression.calleeExpression?.text?.let(unresolvedNames::add)
            return group(argumentEffects + lambdaEffects + unknown("Call could not be resolved"))
        }
        if (!resolved.suspend) {
            val unsupportedCallback = resolved.higherOrder && id !in EAGER_INLINE
            val uncertainty = when {
                unsupportedCallback -> unknown("Callback execution effects are not modeled")
                resolved.sourceDefined -> unknown("Non-suspend source helper effects are not modeled")
                else -> EMPTY
            }
            return group(argumentEffects + lambdaEffects + work(context) + uncertainty)
        }
        val target = resolved.target?.let(::key)
        val effect = when {
            resolved.virtual -> unknown("Virtual suspend target may have another implementation")
            target != null && target in functions -> Effect.Invoke(target, context)
            id in INHERITED_LIBRARY_CALLS -> work(context)
            else -> unknown("External suspend implementation has no verified summary")
        }
        val defaultUnknown = resolved.target?.valueParameters?.any {
            val default = it.defaultValue
            default != null && it.name !in args && default !is KtConstantExpression &&
                (default !is KtStringTemplateExpression || default.hasInterpolation())
        } == true
        val callEffect = if (defaultUnknown) group(listOf(effect, unknown("Default argument effects are not modeled"))) else effect
        record(expression, owner, target, context, callEffect)
        return group(argumentEffects + lambdaEffects + callEffect)
    }

    private fun contextEvaluation(
        argument: KtExpression,
        context: DispatcherSet,
        scope: DispatcherSet?,
        owner: FunctionKey?,
    ): Effect {
        val resolved = contexts.context(argument)
        if (!resolved.dispatchers.hasUnknown && !containsCall(argument)) return EMPTY
        return walk(argument, context, scope, owner)
    }

    private fun containsCall(element: PsiElement): Boolean {
        progress.consume()
        return element is KtCallExpression || element.children.any(::containsCall)
    }

    private fun record(
        expression: KtCallExpression,
        owner: FunctionKey?,
        target: FunctionKey?,
        context: DispatcherSet,
        effect: Effect,
    ) {
        calls += SourceCall(expression.containingFile.virtualFile?.url ?: expression.containingFile.name,
            expression.textRange.endOffset, owner, target, context, effect)
    }

    companion object {
        fun key(function: KtNamedFunction) = FunctionKey(
            function.containingFile.virtualFile?.url ?: function.containingFile.name,
            function.textRange.startOffset,
        )
        fun isSupported(function: KtNamedFunction): Boolean =
            function.hasModifier(KtTokens.SUSPEND_KEYWORD) &&
                generateSequence(function as PsiElement?) { it.parent }
                    .filterIsInstance<KtModifierListOwner>().none { it.hasModifier(KtTokens.EXPECT_KEYWORD) }
        private val EMPTY = Effect.Work(EffectSummary.EMPTY)
        private fun work(context: DispatcherSet) = Effect.Work(EffectSummary(context))
        private fun unknown(reason: String) = work(DispatcherSet.unknown(reason))
        private fun group(effects: List<Effect>): Effect = Effect.Group(effects)
        private fun ensureExecution(effect: Effect, context: DispatcherSet): Effect =
            if (isEmpty(effect)) work(context) else effect
        private fun isEmpty(effect: Effect): Boolean = when (effect) {
            is Effect.Work -> effect.summary.dispatchers.isEmpty
            is Effect.Invoke -> false
            is Effect.Group -> effect.effects.all(::isEmpty)
        }
        private val SCOPES = setOf("kotlinx.coroutines.coroutineScope", "kotlinx.coroutines.supervisorScope")
        private val BUILDERS = setOf("kotlinx.coroutines.launch", "kotlinx.coroutines.async")
        private val EAGER_INLINE = setOf("kotlin.run", "kotlin.let", "kotlin.also", "kotlin.apply", "kotlin.with", "kotlin.use", "kotlin.io.use")
        private val INHERITED_LIBRARY_CALLS = setOf("kotlinx.coroutines.delay", "kotlinx.coroutines.yield", "kotlinx.coroutines.ensureActive")
    }
}
