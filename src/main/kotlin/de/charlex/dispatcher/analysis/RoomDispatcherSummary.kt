package de.charlex.dispatcher.analysis

import de.charlex.dispatcher.model.Dispatcher

internal object RoomDispatcherSummary {
    private val versions = setOf("2.7.2", "2.8.4")
    private val artifactVersion = Regex("room-runtime(?:-android|-jvm)?[/-](2\\.\\d+\\.\\d+)(?:[/.!-])")

    fun dispatcher(
        factoryIdentity: String?,
        receiverIdentity: String?,
        libraryLocation: String?,
        instanceIdentity: String,
    ): Dispatcher.Custom? {
        if (factoryIdentity != "kotlinx.coroutines.asCoroutineDispatcher") return null
        if (receiverIdentity != "androidx.room.RoomDatabase.queryExecutor") return null
        val version = libraryLocation?.let { artifactVersion.find(it)?.groupValues?.get(1) }
        if (version !in versions) return null
        return Dispatcher.Custom("room-query-executor:$instanceIdentity", "Room(query executor)")
    }
}
