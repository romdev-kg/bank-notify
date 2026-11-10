package com.banknotify

internal enum class ListenerStatus(val label: String) {
    NO_ACCESS("Нет доступа к уведомлениям"),
    CONNECTING("Ожидаем подключения системы…"),
    CONNECTED("Активен"),
    STALLED("Доступ выдан, но система не подключила сервис. Выключите и снова включите доступ к уведомлениям.")
}

/** The clock must be monotonic (elapsedRealtime), not wall time. */
internal class ListenerConnectionState {
    private var disconnectedSince: Long? = null
    @Synchronized fun update(access: Boolean, connected: Boolean, now: Long): ListenerStatus {
        if (!access || connected) {
            disconnectedSince = null
            return if (access && connected) ListenerStatus.CONNECTED else ListenerStatus.NO_ACCESS
        }
        val since = disconnectedSince ?: now.also { disconnectedSince = it }
        return if (now - since >= 120_000) ListenerStatus.STALLED else ListenerStatus.CONNECTING
    }

    @Synchronized fun disconnectedFor(now: Long): Long? = disconnectedSince?.let { now - it }
}

/**
 * requestRebind() is a no-op unless the listener was unbound via requestUnbind() (AOSP
 * ManagedServices.setComponentState). After the system gives up rebinding a dead process,
 * only a PACKAGE_CHANGED broadcast makes NotificationManagerService rebind, so we toggle
 * a dummy component of our package. Clocks are elapsedRealtime; a stored value from before reboot is ignored.
 */
internal object ListenerTogglePolicy {
    const val DISCONNECTED_MS = 30_000L
    const val MIN_INTERVAL_MS = 600_000L

    fun shouldToggle(disconnectedFor: Long?, lastToggle: Long?, now: Long): Boolean {
        if (disconnectedFor == null || disconnectedFor < DISCONNECTED_MS) return false
        return lastToggle == null || lastToggle > now || now - lastToggle >= MIN_INTERVAL_MS
    }
}
