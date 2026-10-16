package com.banknotify

import android.app.Application
import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class BankNotifyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RecoveryWork.start(this)
        HealthcheckWorker.configure(this)
    }
}

object RecoveryWork {
    // Intake persistence must outlive the listener instance, so onDestroy cannot cancel it.
    val intakeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context) {
        ListenerRecovery.schedule(context)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("bank-notify-recovery", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RecoveryWorker>(15, TimeUnit.MINUTES).build())
        intakeScope.launch {
            try { DeliveryQueue.recover(context.applicationContext) }
            catch (_: Exception) { AppLog.w("Queue", "Восстановление очереди будет повторено") }
        }
    }
}

class RecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        ListenerRecovery.schedule(applicationContext)
        return try {
            DeliveryQueue.recover(applicationContext)
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}
