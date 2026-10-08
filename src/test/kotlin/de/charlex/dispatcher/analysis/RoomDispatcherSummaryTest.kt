package de.charlex.dispatcher.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomDispatcherSummaryTest {
    @Test fun identifiesExplicitQueryExecutorConversionForVerifiedVersions() {
        listOf("2.7.2", "2.8.4").forEach { version ->
            val dispatcher = resolve("jar:///libs/room-runtime-android-$version.jar!/androidx/room/RoomDatabase.class")
            assertEquals("Room(query executor)", dispatcher?.label)
        }
    }

    @Test fun retainsDistinctExecutorCreationIdentities() {
        assertNotEquals(resolve(LOCATION, "first"), resolve(LOCATION, "second"))
    }

    @Test fun doesNotInferDispatchersFromDaoNamesOrUnverifiedVersions() {
        assertNull(RoomDispatcherSummary.dispatcher("sample.Dao.query", "androidx.room.RoomDatabase.queryExecutor", LOCATION, "call"))
        assertNull(RoomDispatcherSummary.dispatcher("kotlinx.coroutines.asCoroutineDispatcher", "sample.Database.queryExecutor", LOCATION, "call"))
        assertNull(resolve("jar:///libs/room-runtime-android-2.9.0.jar!/androidx/room/RoomDatabase.class"))
        assertNull(resolve(null))
    }

    private fun resolve(location: String?, identity: String = "call") = RoomDispatcherSummary.dispatcher(
        "kotlinx.coroutines.asCoroutineDispatcher", "androidx.room.RoomDatabase.queryExecutor", location, identity,
    )

    companion object {
        private const val LOCATION = "jar:///libs/room-runtime-android-2.8.4.jar!/androidx/room/RoomDatabase.class"
    }
}
