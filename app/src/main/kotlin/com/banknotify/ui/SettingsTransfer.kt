package com.banknotify.ui

import android.net.Uri
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.banknotify.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** File chooser grants access only to the document selected by the user. */
class SettingsTransfer(private val activity: AppCompatActivity) {
    private val importModel by lazy { ViewModelProvider(activity)[SettingsImportViewModel::class.java] }

    init {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                importModel.state.collect { state ->
                    when (state) {
                        SettingsImportViewModel.State.SUCCESS -> {
                            importModel.consumeResult()
                            Toast.makeText(activity, "Настройки импортированы", Toast.LENGTH_LONG).show()
                            // Refresh whichever Activity now owns the retained operation.
                            activity.recreate()
                        }
                        SettingsImportViewModel.State.FAILURE -> {
                            importModel.consumeResult()
                            Toast.makeText(activity, "Импорт не выполнен: проверьте файл и пароль", Toast.LENGTH_LONG).show()
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    private val exportFile = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { askPassword(true) { password -> export(it, password) } }
    }
    private val importFile = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { askPassword(false) { password -> import(it, password) } }
    }

    fun export() = exportFile.launch("BankNotify-settings.json")
    fun import() = importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))

    private fun askPassword(export: Boolean, action: (CharArray) -> Unit) {
        val field = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = if (export) "Новый пароль, не менее 8 символов" else "Пароль файла"
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (export) "Пароль экспорта" else "Импорт настроек")
            .setMessage(if (export) "В файл войдут сохранённые настройки, токен Telegram и пароли прокси. Запомните пароль файла."
                else "Настройки из файла заменят текущие. История и очередь оплат сохранятся. Системные разрешения выдаются отдельно.")
            .setView(field).setNegativeButton("Отмена", null).setPositiveButton("Продолжить", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (export && field.text.length < 8) field.error = "Не менее 8 символов"
                else {
                    val password = field.text.toString().toCharArray()
                    field.text.clear()
                    dialog.dismiss()
                    action(password)
                }
            }
        }
        dialog.show()
    }

    private fun export(uri: Uri, password: CharArray) {
        activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val values = AppSettings.preferences(activity).all.filterKeys(SettingsBackup::isSetting)
                        .mapValues { requireNotNull(it.value) }
                    val data = SettingsBackup.encode(values, password)
                    requireNotNull(activity.contentResolver.openOutputStream(uri, "wt")).use { it.write(data) }
                }
                Toast.makeText(activity, "Настройки экспортированы", Toast.LENGTH_LONG).show()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { Toast.makeText(activity, "Не удалось экспортировать настройки", Toast.LENGTH_LONG).show() }
            finally { password.fill('\u0000') }
        }
    }

    private fun import(uri: Uri, password: CharArray) {
        importModel.import(uri, password)
    }
}
