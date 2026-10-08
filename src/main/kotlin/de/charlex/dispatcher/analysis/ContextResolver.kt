package de.charlex.dispatcher.analysis

import com.intellij.openapi.progress.ProgressManager
import de.charlex.dispatcher.model.Dispatcher
import de.charlex.dispatcher.model.DispatcherSet
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression

internal data class ContextValue(val dispatchers: DispatcherSet, val replaces: Boolean) {
    fun applyTo(inherited: DispatcherSet): DispatcherSet =
        if (replaces) dispatchers else inherited.join(dispatchers)
}

internal class ContextResolver(private val api: KotlinAnalysisAdapter) {
    fun context(expression: KtExpression?): ContextValue = context(expression, mutableSetOf())

    fun isDirectDispatcher(expression: KtExpression?): Boolean {
        if (expression == null) return false
        if (expression is KtParenthesizedExpression) return isDirectDispatcher(expression.expression)
        return when (api.value(expression)?.identity) {
            "kotlinx.coroutines.Dispatchers.Main", "kotlinx.coroutines.Dispatchers.IO",
            "kotlinx.coroutines.Dispatchers.Default", "kotlinx.coroutines.Dispatchers.Unconfined" -> true
            "kotlinx.coroutines.MainCoroutineDispatcher.immediate" ->
                isDirectDispatcher((expression as? KtQualifiedExpression)?.receiverExpression)
            else -> false
        }
    }

    private fun context(expression: KtExpression?, active: MutableSet<KtExpression>): ContextValue {
        ProgressManager.checkCanceled()
        if (expression == null) return KEEP
        if (!active.add(expression)) return uncertain("Cyclic context aliases could not be resolved")
        try {
            if (expression is KtParenthesizedExpression) return context(expression.expression, active)
            if (expression is KtBinaryExpression && expression.operationToken == KtTokens.PLUS) {
                if (api.operatorIdentity(expression) !in CONTEXT_PLUS) {
                    return uncertain("Unresolved context composition")
                }
                val left = context(expression.left, active)
                val right = context(expression.right, active)
                return ContextValue(right.applyTo(left.dispatchers), left.replaces || right.replaces)
            }
            val value = api.value(expression)
            when (value?.identity) {
                "kotlinx.coroutines.Dispatchers.Main" -> return known(Dispatcher.Main, expression)
                "kotlinx.coroutines.Dispatchers.IO" -> return known(Dispatcher.IO, expression)
                "kotlinx.coroutines.Dispatchers.Default" -> return known(Dispatcher.Default, expression)
                "kotlinx.coroutines.Dispatchers.Unconfined" -> return known(Dispatcher.Unconfined, expression)
                "kotlinx.coroutines.NonCancellable", "kotlin.coroutines.EmptyCoroutineContext" -> return KEEP
                "kotlinx.coroutines.MainCoroutineDispatcher.immediate" -> {
                    val receiver = (expression as? KtQualifiedExpression)?.receiverExpression
                    return context(receiver, active).takeIf { Dispatcher.Main in it.dispatchers.known }
                        ?: uncertain("Unresolved immediate dispatcher receiver")
                }
            }
            value?.objectIdentity?.let { return known(Dispatcher.Custom(it, "Custom(${it.substringAfterLast('.')})"), expression) }
            value?.property?.let { property ->
                if (value.virtual) return uncertain("Dispatcher property can be overridden")
                if (!property.isVar && property.getter == null && !property.hasDelegate()) {
                    return property.initializer?.let { context(it, active) }
                        ?: uncertain("Dispatcher value has no known initializer")
                }
                return uncertain("Mutable, delegated, or computed dispatcher value")
            }
            val call = asCall(expression)
            if (call != null) {
                val resolved = api.call(call)
                when (resolved?.identity) {
                    "kotlinx.coroutines.CoroutineName",
                    "kotlinx.coroutines.Job", "kotlinx.coroutines.SupervisorJob" -> return KEEP
                    "kotlinx.coroutines.CoroutineDispatcher.limitedParallelism" -> {
                        return context(resolved.receiver, active)
                    }
                }
                if (resolved?.identity in CUSTOM_FACTORIES || resolved?.dispatcherFactory == true) {
                    val identity = "${call.containingFile.virtualFile?.url}:${call.textOffset}"
                    val receiver = resolved?.receiver?.let(api::value)
                    RoomDispatcherSummary.dispatcher(resolved?.identity, receiver?.identity,
                        receiver?.libraryLocation, identity)?.let { return known(it, expression) }
                    val label = resolved?.returnClass?.substringAfterLast('.') ?: "Dispatcher"
                    return known(Dispatcher.Custom(identity, "Custom($label)"), expression)
                }
            }
            return uncertain("Dispatcher or context value could not be resolved")
        } finally {
            active.remove(expression)
        }
    }

    fun scope(expression: KtExpression?): ContextValue = scope(expression, mutableSetOf())

    private fun scope(expression: KtExpression?, active: MutableSet<KtExpression>): ContextValue {
        ProgressManager.checkCanceled()
        if (expression == null) return uncertain("Scope provenance is unknown")
        if (!active.add(expression)) return uncertain("Cyclic scope aliases could not be resolved")
        try {
            if (expression is KtParenthesizedExpression) return scope(expression.expression, active)
            val value = api.value(expression)
            if (value?.identity == "kotlinx.coroutines.GlobalScope") return KEEP
            value?.property?.let { property ->
                if (value.virtual) return uncertain("Scope property can be overridden")
                if (!property.isVar && property.getter == null && !property.hasDelegate()) {
                    return scope(property.initializer, active)
                }
            }
            val call = asCall(expression) ?: return uncertain("Scope provenance is unknown")
            val resolved = api.call(call) ?: return uncertain("Scope creation could not be resolved")
            return when (resolved.identity) {
                "kotlinx.coroutines.MainScope" -> known(Dispatcher.Main, expression)
                "kotlinx.coroutines.CoroutineScope" -> context(resolved.arguments["context"], active)
                else -> uncertain("Scope may supply a custom dispatcher")
            }
        } finally {
            active.remove(expression)
        }
    }

    companion object {
        val KEEP = ContextValue(DispatcherSet.EMPTY, replaces = false)
        private val CONTEXT_PLUS = setOf(
            "kotlin.coroutines.CoroutineContext.plus",
            "kotlin.coroutines.CoroutineContext.Element.plus",
            "kotlin.coroutines.AbstractCoroutineContextElement.plus",
            "kotlinx.coroutines.CoroutineDispatcher.plus",
            "kotlinx.coroutines.NonCancellable.plus",
            "kotlinx.coroutines.Job.plus",
        )
        private val CUSTOM_FACTORIES = setOf(
            "kotlinx.coroutines.newSingleThreadContext",
            "kotlinx.coroutines.newFixedThreadPoolContext",
            "kotlinx.coroutines.asCoroutineDispatcher",
        )

        fun asCall(expression: KtExpression): KtCallExpression? = when (expression) {
            is KtCallExpression -> expression
            is KtQualifiedExpression -> expression.selectorExpression as? KtCallExpression
            else -> null
        }

        private fun known(dispatcher: Dispatcher, expression: KtExpression) =
            ContextValue(SourceOrigins.dispatchers(dispatcher, expression), replaces = true)
        fun uncertain(reason: String) = ContextValue(DispatcherSet.unknown(reason), replaces = false)
    }
}
