package de.charlex.dispatcher.editor

data class DispatcherHintSettings(
    var showCalls: Boolean = false,
    var automaticAnalysis: Boolean = true,
    var callSiteMode: CallSiteBadgeMode? = null,
)

enum class CallSiteBadgeMode(val displayName: String) {
    ALL("All calls"),
    DISPATCHER_CHANGES("Only calls that set a dispatcher"),
    NONE("Do not show (default)"),
}

internal fun DispatcherHintSettings.resolvedCallSiteMode(): CallSiteBadgeMode =
    callSiteMode ?: if (showCalls) CallSiteBadgeMode.ALL else CallSiteBadgeMode.NONE
