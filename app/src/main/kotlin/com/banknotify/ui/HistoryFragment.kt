package com.banknotify.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.banknotify.R
import com.banknotify.db.BankNotificationsDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var database: BankNotificationsDatabase
    private lateinit var container: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        database = BankNotificationsDatabase.getDatabase(requireContext())
        container = view.findViewById(R.id.history_container)

        loadHistory()
    }

    private fun loadHistory() {
        scope.launch {
            val notifications = withContext(Dispatchers.IO) {
                database.notificationDao().getRecentNotifications()
            }

            container.removeAllViews()

            if (notifications.isEmpty()) {
                val emptyView = TextView(requireContext()).apply {
                    text = "История пуста"
                    textSize = 16f
                    setPadding(0, 32, 0, 0)
                }
                container.addView(emptyView)
                return@launch
            }

            val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("ru", "RU"))

            notifications.forEach { notif ->
                val itemView = LinearLayout(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, 24)
                    }
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(0xFFF5F5F5.toInt())
                    setPadding(24, 16, 24, 16)
                }

                val statusIcon = if (notif.sentToTelegram) "[OK]" else "[!]"
                val timeStr = dateFormat.format(Date(notif.timestamp))

                val tvText = TextView(requireContext()).apply {
                    text = "$statusIcon ${notif.bankName}\n${notif.operationType}: ${notif.amount} руб.\nСчёт: ...${notif.accountNumber}\n$timeStr"
                    textSize = 14f
                    setTextColor(0xFF333333.toInt())
                }

                itemView.addView(tvText)
                container.addView(itemView)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }
}
