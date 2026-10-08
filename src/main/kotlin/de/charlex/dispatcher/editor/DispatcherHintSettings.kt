package de.charlex.dispatcher.editor

data class DispatcherHintSettings(
    var showCalls: Boolean? = null,
    var callSiteMode: CallSiteBadgeMode? = null,
    var onlyInFunctionContainingCaret: Boolean = false,
)

enum class CallSiteBadgeMode(val displayName: String) {
    ALL("All calls"),
    DISPATCHER_CHANGES("Only calls that set a dispatcher"),
    NONE("Do not show"),
}

internal fun DispatcherHintSettings.resolvedCallSiteMode(): CallSiteBadgeMode =
    callSiteMode ?: when (showCalls) {
        true -> CallSiteBadgeMode.ALL
        false -> CallSiteBadgeMode.NONE
        null -> CallSiteBadgeMode.DISPATCHER_CHANGES
    }
