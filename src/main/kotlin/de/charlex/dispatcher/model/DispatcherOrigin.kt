package de.charlex.dispatcher.model

data class DispatcherOrigin(
    val fileUrl: String,
    val offset: Int,
    val line: Int,
    val description: String,
) {
    init {
        require(fileUrl.isNotBlank()) { "Origin file URL must not be blank" }
        require(offset >= 0) { "Origin offset must not be negative" }
        require(line >= 1) { "Origin line must be 1-based" }
        require(description.isNotBlank()) { "Origin description must not be blank" }
    }
}
