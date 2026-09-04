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
 *
 * `replay = 1` é OBRIGATÓRIO aqui, nunca 0: quem espera o resultado só se
 * inscreve DEPOIS de `context.startActivity(...)` retornar, e a Cielo pode
 * devolver o callback antes disso (PIX aprovado na hora, erro de validação
 * imediato, ou o próprio callback recriando o processo). Com `replay = 0` e
 * nenhum coletor inscrito no instante do `emit`, o resultado era DESCARTADO
 * em silêncio: o provider ficava suspenso até o timeout de 5 minutos e
 * devolvia `Unknown` mesmo com o cartão já tendo sido cobrado do cliente.
 *
 * O replay guarda só o último resultado, e o coletor filtra por `attemptId`
 * (ver CieloDeepLinkPaymentProvider) — um resultado antigo de outra tentativa
 * nunca é confundido com o da cobrança atual.
 */
@Singleton
class CieloResultBridge @Inject constructor() {
    private val _results = MutableSharedFlow<PaymentResult>(
        replay = 1,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val results: SharedFlow<PaymentResult> = _results

    suspend fun emit(result: PaymentResult) {
        _results.emit(result)
    }
}
