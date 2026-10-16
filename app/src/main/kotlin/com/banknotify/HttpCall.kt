package com.banknotify

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

// Consume the body inside the callback so cancellation can interrupt body reads as well as connect.
// The block must return a consumed value, not the response or its body: both are closed here.
internal suspend fun <T> Call.awaitResponse(block: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (!continuation.isActive) return
                        block(it)
                    }
                    continuation.resume(result)
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
        })
    }
