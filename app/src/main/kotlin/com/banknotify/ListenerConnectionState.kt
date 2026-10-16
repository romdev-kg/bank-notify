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
}
