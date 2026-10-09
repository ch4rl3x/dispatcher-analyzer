package demo

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private fun normalizeName(value: String): String = value.trim()

private suspend fun readFromDisk() {
    delay(1)
}

private suspend fun ioWrapper() = withContext(Dispatchers.IO) {
    readFromDisk()
}

private suspend fun mixedWorkload() {
    delay(1)
    withContext(Dispatchers.IO) {
        readFromDisk()
    }
}

private suspend fun injectedDispatcherWork(dispatcher: CoroutineDispatcher) = withContext(dispatcher) {
    readFromDisk()
}

private val databaseExecutor = Executors.newSingleThreadExecutor { task ->
    Thread(task, "database-worker").apply { isDaemon = true }
}
private val databaseDispatcher = databaseExecutor.asCoroutineDispatcher()

private suspend fun databaseWork() = withContext(databaseDispatcher) {
    readFromDisk()
}

fun launchExamples(scope: CoroutineScope, injected: CoroutineDispatcher) {
    scope.launch(Dispatchers.Main) {
        normalizeName(" main ")
        externallyCallableWork()
        ioWrapper()
        mixedWorkload()
        injectedDispatcherWork(injected)
        databaseWork()
    }
    scope.launch(Dispatchers.Default) {
        normalizeName(" default ")
        ioWrapper()
    }
}

public suspend fun externallyCallableWork() {
    delay(1)
}
