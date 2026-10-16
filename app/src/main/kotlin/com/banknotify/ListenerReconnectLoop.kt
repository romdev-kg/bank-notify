package com.banknotify

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class ListenerReconnectLoop(
    private val scope: CoroutineScope,
    private val shouldReconnect: () -> Boolean,
    private val reconnect: () -> Unit
) {
    companion object {
        fun nextDelay(previous: Long): Long = if (previous <= 0) 5_000L else (previous.coerceAtMost(300_000L) * 2).coerceAtMost(300_000L)
    }

    private var job: Job? = null

    // All calls are confined to the main thread by ListenerRecovery.
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var delayMillis = 5_000L
            while (shouldReconnect()) {
                reconnect()
                delay(delayMillis)
                delayMillis = nextDelay(delayMillis)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
