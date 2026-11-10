package com.banknotify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Never receives broadcasts; ListenerRecovery flips its enabled state to emit PACKAGE_CHANGED. */
class ListenerRebindTrigger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
