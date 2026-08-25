package com.nokta.pos.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nokta.pos.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PairingUiState(
    val code: String = "",
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val alreadyPaired: Boolean = false,
)

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PairingUiState(alreadyPaired = authRepository.isDevicePaired()))
    val state: StateFlow<PairingUiState> = _state

    fun onCodeChanged(code: String) {
        val digitsOnly = code.filter { it.isDigit() }.take(6)
        _state.value = _state.value.copy(code = digitsOnly, error = null)
    }

    fun submit(onSuccess: () -> Unit) {
        val code = _state.value.code
        if (code.length != 6) {
            _state.value = _state.value.copy(error = "Digite os 6 dígitos do código.")
            return
        }
        _state.value = _state.value.copy(isSubmitting = true, error = null)
        viewModelScope.launch {
            authRepository.redeemPairingCode(code)
                .onSuccess {
                    _state.value = _state.value.copy(isSubmitting = false)
                    onSuccess()
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        error = e.message ?: "Código inválido ou expirado.",
                    )
                }
        }
    }
}
