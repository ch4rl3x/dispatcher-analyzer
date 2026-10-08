@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.psi.KtExperimentalApi::class,
)

package de.charlex.dispatcher.analysis

import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression

internal data class ResolvedCall(
    val identity: String?,
    val target: KtNamedFunction?,
    val suspend: Boolean,
    val expect: Boolean,
    val inline: Boolean,
    val higherOrder: Boolean,
    val sourceDefined: Boolean,
    val virtual: Boolean,
    val arguments: Map<String, KtExpression>,
    val receiver: KtExpression?,
    val dispatcherFactory: Boolean,
    val returnClass: String?,
)

internal data class ResolvedValue(
    val identity: String?,
    val property: KtProperty?,
    val dispatcherType: Boolean,
    val objectIdentity: String?,
    val libraryLocation: String?,
    val virtual: Boolean,
)

/** All lifetime-bound Analysis API objects are consumed inside these sessions. */
internal class KotlinAnalysisAdapter {
    private val calls = HashMap<KtCallExpression, ResolvedCall?>()
    private val values = HashMap<KtExpression, ResolvedValue?>()

    fun operatorIdentity(expression: KtBinaryExpression): String? = analyze(expression) {
        (expression.operationReference.resolveSymbol() as? KaCallableSymbol)
            ?.callableId?.asSingleFqName()?.asString()
    }

    fun referencedFunction(expression: KtCallableReferenceExpression): KtNamedFunction? = analyze(expression) {
        expression.resolveSymbol()?.psi as? KtNamedFunction
    }

    fun call(expression: KtCallExpression): ResolvedCall? = calls.getOrPut(expression) {
        analyze(expression) {
            val call = expression.resolveCall() ?: return@analyze null
            val symbol = call.signature.symbol
            val named = symbol as? KaNamedFunctionSymbol
            ResolvedCall(
                identity = symbol.callableId?.asSingleFqName()?.asString()
                    ?: (symbol as? KaConstructorSymbol)?.containingClassId?.asSingleFqName()?.asString(),
                target = symbol.psi as? KtNamedFunction,
                suspend = named?.isSuspend == true,
                expect = symbol.isExpect,
                inline = named?.isInline == true,
                higherOrder = symbol.valueParameters.any { it.returnType is KaFunctionType },
                sourceDefined = (symbol.psi?.containingFile as? KtFile)?.isCompiled == false,
                virtual = symbol.modality == KaSymbolModality.OPEN ||
                    symbol.modality == KaSymbolModality.ABSTRACT,
                arguments = call.valueArgumentMapping.entries.associate { (argument, parameter) ->
                    parameter.symbol.name.asString() to argument
                },
                receiver = ((call.extensionReceiver ?: call.dispatchReceiver)
                    as? KaExplicitReceiverValue)?.expression,
                dispatcherFactory = symbol is KaConstructorSymbol &&
                    symbol.returnType.isSubtypeOf(DISPATCHER_CLASS),
                returnClass = (symbol.returnType as? KaClassType)?.classId?.asSingleFqName()?.asString(),
            )
        }
    }

    fun value(expression: KtExpression): ResolvedValue? = values.getOrPut(expression) {
        analyze(expression) {
            val symbol = when (expression) {
                is KtNameReferenceExpression -> expression.resolveSymbol()
                is KtQualifiedExpression -> expression.resolveSymbol()
                else -> null
            } ?: return@analyze null
            val callable = symbol as? KaCallableSymbol
            val classSymbol = symbol as? org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
            ResolvedValue(
                identity = callable?.callableId?.asSingleFqName()?.asString()
                    ?: classSymbol?.classId?.asSingleFqName()?.asString(),
                property = symbol.psi as? KtProperty,
                dispatcherType = callable?.returnType?.isSubtypeOf(DISPATCHER_CLASS) == true,
                objectIdentity = classSymbol?.takeIf {
                    it.classKind == org.jetbrains.kotlin.analysis.api.symbols.KaClassKind.OBJECT &&
                        it.superTypes.any { type -> type.isSubtypeOf(DISPATCHER_CLASS) }
                }?.classId?.asSingleFqName()?.asString(),
                libraryLocation = symbol.psi?.containingFile?.virtualFile?.url?.takeIf {
                    symbol.origin == KaSymbolOrigin.LIBRARY || symbol.origin == KaSymbolOrigin.JAVA_LIBRARY
                },
                virtual = callable?.modality == KaSymbolModality.OPEN || callable?.modality == KaSymbolModality.ABSTRACT,
            )
        }
    }

    companion object {
        private val DISPATCHER_CLASS = ClassId.topLevel(FqName("kotlinx.coroutines.CoroutineDispatcher"))
    }
}
