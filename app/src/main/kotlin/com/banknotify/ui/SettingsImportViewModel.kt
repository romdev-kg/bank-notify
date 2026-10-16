package com.banknotify.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.banknotify.AppSettings
import com.banknotify.HealthcheckWorker
import com.banknotify.RecoveryWork
import com.banknotify.SettingsBackup
import com.banknotify.SettingsImport
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns the import across rotation; never keeps an Activity or its document dialog. */
class SettingsImportViewModel(application: Application) : AndroidViewModel(application) {
    enum class State { IDLE, RUNNING, SUCCESS, FAILURE }

    private val mutableState = MutableStateFlow(State.IDLE)
    val state = mutableState.asStateFlow()

    fun import(uri: Uri, password: CharArray) {
        if (mutableState.value == State.RUNNING) {
            password.fill('\u0000')
            return
        }
        mutableState.value = State.RUNNING
        val context = getApplication<Application>()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    SettingsImport.run(read = {
                        requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val size = input.read(buffer)
                                if (size < 0) break
                                require(output.size() + size <= SettingsBackup.MAX_BYTES)
                                output.write(buffer, 0, size)
                            }
                            output.toByteArray()
                        }
                    }, password = password, preferences = { AppSettings.preferences(context) }) {
                        HealthcheckWorker.configure(context)
                        RecoveryWork.start(context)
                    }
                }
                mutableState.value = State.SUCCESS
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.value = State.FAILURE
            } finally {
                password.fill('\u0000')
            }
        }
    }

    fun consumeResult() {
        if (mutableState.value == State.SUCCESS || mutableState.value == State.FAILURE) {
            mutableState.value = State.IDLE
        }
    }
}
