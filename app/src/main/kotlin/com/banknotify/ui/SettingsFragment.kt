package com.banknotify.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.banknotify.R
import com.banknotify.TelegramSender
import com.banknotify.db.BankNotificationsDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val etToken = view.findViewById<EditText>(R.id.et_telegram_token)
        val etChatId = view.findViewById<EditText>(R.id.et_chat_id)
        val btnSave = view.findViewById<Button>(R.id.btn_save_settings)
        val btnTest = view.findViewById<Button>(R.id.btn_test)

        val prefs = EncryptedSharedPreferences.create(
            requireContext(),
            "bank_notify",
            MasterKey.Builder(requireContext())
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        etToken.setText(prefs.getString("telegram_token", ""))
        etChatId.setText(prefs.getString("telegram_chat_id", ""))

        btnSave.setOnClickListener {
            val token = etToken.text.toString().trim()
            val chatId = etChatId.text.toString().trim()

            if (token.isNotEmpty() && chatId.isNotEmpty()) {
                prefs.edit()
                    .putString("telegram_token", token)
                    .putString("telegram_chat_id", chatId)
                    .apply()
                Toast.makeText(requireContext(), "Сохранено!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Заполните все поля!", Toast.LENGTH_SHORT).show()
            }
        }

        btnTest.setOnClickListener {
            val token = etToken.text.toString().trim()
            val chatId = etChatId.text.toString().trim()

            if (token.isEmpty() || chatId.isEmpty()) {
                Toast.makeText(requireContext(), "Сначала заполните и сохраните настройки", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnTest.isEnabled = false
            val database = BankNotificationsDatabase.getDatabase(requireContext())
            val sender = TelegramSender(requireContext(), database.notificationDao())

            scope.launch {
                val success = withContext(Dispatchers.IO) {
                    sender.sendTest()
                }

                btnTest.isEnabled = true
                if (success) {
                    Toast.makeText(requireContext(), "Тестовое сообщение отправлено!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Ошибка отправки. Проверьте токен и Chat ID", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }
}
