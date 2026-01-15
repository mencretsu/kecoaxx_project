package com.example.ngontol.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

object UiActionQueue {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val queue = Channel<suspend () -> Unit>(capacity = Channel.UNLIMITED)
    private const val DEFAULT_ACTION_GAP_MS = 250L

    init {
        scope.launch {
            for (action in queue) {
                try {
                    action()
                } catch (e: Exception) {
                    android.util.Log.e("UiActionQueue", "Action failed: ${e.message}")
                } finally {
                    // small gap to let system settle
                    delay(DEFAULT_ACTION_GAP_MS)
                }
            }
        }
    }

    /**
     * Post an action to run on Main thread but does NOT wait for completion.
     */
    fun post(action: suspend () -> Unit) {
        scope.launch {
            queue.send(action)
        }
    }

    /**
     * Post an action and wait until it's finished.
     */
    suspend fun postAndAwait(action: suspend () -> Unit) {
        val completion = CompletableDeferred<Unit>()
        queue.send {
            try {
                action()
            } finally {
                completion.complete(Unit)
            }
        }
        completion.await()
    }
}
