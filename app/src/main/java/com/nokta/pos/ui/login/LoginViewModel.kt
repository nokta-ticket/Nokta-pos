package com.nokta.pos.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nokta.pos.auth.AuthRepository
import com.nokta.pos.auth.LoginOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val email: String = "",
    val senha: String = "",
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val requires2fa: Boolean = false,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state

    fun onEmailChanged(v: String) { _state.value = _state.value.copy(email = v, error = null) }
    fun onSenhaChanged(v: String) { _state.value = _state.value.copy(senha = v, error = null) }

    fun submit(onSuccess: () -> Unit) {
        val s = _state.value
        if (s.email.isBlank() || s.senha.isBlank()) {
            _state.value = s.copy(error = "Informe e-mail e senha.")
            return
        }
        _state.value = s.copy(isSubmitting = true, error = null)
        viewModelScope.launch {
            when (val outcome = authRepository.login(s.email, s.senha)) {
                is LoginOutcome.Success -> {
                    _state.value = _state.value.copy(isSubmitting = false)
                    onSuccess()
                }
                is LoginOutcome.Requires2fa -> {
                    // MVP: 2FA não é um fluxo comum para operador de turno em
                    // terminal compartilhado — orientamos desativar 2FA nessa
                    // conta específica em vez de implementar um segundo fluxo
                    // de código aqui (ver docs/pos-mvp-architecture.md, "fora
                    // de escopo do MVP").
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        error = "Esta conta tem verificação em duas etapas ativa — desative-a para usar o terminal, ou use outra conta.",
                    )
                }
                is LoginOutcome.Failed -> {
                    _state.value = _state.value.copy(isSubmitting = false, error = outcome.message)
                }
            }
        }
    }
}
