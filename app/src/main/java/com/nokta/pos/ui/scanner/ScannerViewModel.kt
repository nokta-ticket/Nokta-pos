package com.nokta.pos.ui.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nokta.pos.auth.AuthRepository
import com.nokta.pos.comanda.data.TabRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ScannerUiState(
    val manualCode: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val tabRepository: TabRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ScannerUiState())
    val state: StateFlow<ScannerUiState> = _state

    private var isResolving = false

    fun onManualCodeChanged(code: String) {
        _state.value = _state.value.copy(manualCode = code, error = null)
    }

    /** Chamado tanto pela leitura de câmera (código decodificado) quanto pelo botão de entrada manual. */
    fun resolveTab(code: String, onResolved: (String) -> Unit) {
        if (isResolving || code.isBlank()) return
        isResolving = true
        _state.value = _state.value.copy(isLoading = true, error = null)

        val organizationId = authRepository.currentOrganizationId()
        val locationId = authRepository.currentLocationId()
        if (organizationId == null || locationId == null) {
            _state.value = _state.value.copy(isLoading = false, error = "Sessão inválida — faça login novamente.")
            isResolving = false
            return
        }

        viewModelScope.launch {
            runCatching { tabRepository.getTabByPublicCode(organizationId, locationId, code) }
                .onSuccess { tab ->
                    _state.value = _state.value.copy(isLoading = false)
                    onResolved(tab.localId)
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(isLoading = false, error = e.message ?: "Comanda não encontrada.")
                    isResolving = false
                }
        }
    }
}
