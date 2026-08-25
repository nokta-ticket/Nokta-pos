package com.nokta.pos.payment.cielo

import com.nokta.pos.payment.domain.PaymentResult
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ponte in-process entre CieloPaymentResponseActivity (recebe o deep link de
 * callback) e CieloDeepLinkPaymentProvider (está com uma coroutine suspensa
 * esperando o resultado). Não é usado para nada além disso — não confundir
 * com um event bus genérico; a única razão de existir é que o resultado do
 * pagamento chega numa Activity separada (contrato da Cielo), não como
 * retorno direto de função.
 */
@Singleton
class CieloResultBridge @Inject constructor() {
    private val _results = MutableSharedFlow<PaymentResult>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val results: SharedFlow<PaymentResult> = _results

    suspend fun emit(result: PaymentResult) {
        _results.emit(result)
    }
}
