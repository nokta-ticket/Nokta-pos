package com.nokta.pos.payment.cielo

import com.nokta.pos.common.Money
import com.nokta.pos.payment.domain.PaymentResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Regressão de um BLOCKER encontrado na auditoria de homologação.
 *
 * `CieloResultBridge` é um `MutableSharedFlow(replay = 0)`. Quem espera o
 * resultado é `CieloDeepLinkPaymentProvider.startPayment`, que só se inscreve
 * DEPOIS de `context.startActivity(intent)` retornar.
 *
 * Se o app da Cielo devolver o callback antes dessa inscrição — cenário real
 * em PIX/erro de validação imediato, e garantido quando o processo Nokta é
 * recriado pelo callback — o `emit` cai no vazio: com `replay = 0` e nenhum
 * coletor inscrito, o valor é DESCARTADO. O provider fica esperando até o
 * timeout de 5 minutos e devolve `Unknown`, mesmo com o pagamento tendo sido
 * APROVADO de verdade na adquirente.
 *
 * Este teste reproduz exatamente essa ordem (emitir antes de coletar) e prova
 * que o resultado se perde. Ele deve passar a valer o oposto — o resultado
 * precisa sobreviver — quando o bridge for corrigido (replay/persistência).
 */
class CieloResultBridgeRaceTest {

    @Test
    fun `resultado que chega antes do coletor se inscrever nao pode ser descartado`() = runTest {
        val bridge = CieloResultBridge()
        val attemptId = "attempt-1"

        // A Cielo devolve o callback ANTES de startPayment chegar a coletar.
        bridge.emit(
            PaymentResult.Approved(
                attemptId = attemptId,
                amount = Money(5_000),
                providerTransactionId = "NSU-123",
                authorizationCode = "AUTH1",
                brand = "VISA",
                maskedCardNumber = "**** 1234",
                installments = 1,
            ),
        )

        // Agora o provider tenta ler o resultado (o que startPayment faz).
        val received = withTimeoutOrNull(200) {
            bridge.results.first { it.attemptId == attemptId }
        }

        assertNotNull(
            "Resultado APROVADO emitido antes da inscrição foi descartado (replay=0) — " +
                "o pagamento vira Unknown mesmo tendo sido cobrado do cliente",
            received,
        )
    }
}
