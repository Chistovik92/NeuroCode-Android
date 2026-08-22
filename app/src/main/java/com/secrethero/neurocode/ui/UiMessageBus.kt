package com.secrethero.neurocode.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UiMessageBus {
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun showError(error: Throwable) {
        _error.value = error.message ?: error::class.java.simpleName
    }

    fun showNotice(message: String) {
        _notice.value = message
    }

    fun clearError() {
        _error.value = null
    }

    fun clearNotice() {
        _notice.value = null
    }
}
