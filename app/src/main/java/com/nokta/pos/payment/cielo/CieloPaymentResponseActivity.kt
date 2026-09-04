package com.nokta.pos.payment.cielo

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.nokta.pos.payment.domain.PaymentResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Contrato de retorno do pagamento Cielo (manual oficial, "Recuperando dados
 * do pagamento"): a Cielo Smart chama de volta `order://response` com o
 * resultado em Base64(JSON) na query string. Esta Activity só decodifica e
 * publica o resultado em CieloResultBridge — toda a interpretação de
 * negócio (aprovado/recusado/etc.) fica em CieloDeepLinkPaymentProvider, que
 * está com uma coroutine suspensa aguardando este evento.
 *
 * android:launchMode="singleTask" + finish() imediato: esta Activity nunca
 * fica na pilha visível — é só um receptor transitório de Intent.
 */
@AndroidEntryPoint
class CieloPaymentResponseActivity : ComponentActivity() {

    @Inject lateinit var resultBridge: CieloResultBridge
    @Inject lateinit var pendingAttemptStore: PendingCieloAttemptStore

    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        finish()
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return

        // O app da Cielo devolveu o resultado: o serviço em primeiro plano
        // que segurava o processo vivo durante a cobrança já cumpriu o papel
        // (ver CieloPaymentForegroundService). Parado aqui, e não só no
        // provider, porque o callback pode chegar com o processo recriado —
        // caso em que não existe mais nenhum `startPayment` suspenso para
        // fazer essa limpeza.
        CieloPaymentForegroundService.stop(this)

        lifecycleScope.launch {
            val attempt = pendingAttemptStore.current()
            if (attempt == null) {
                // Callback chegou sem tentativa pendente conhecida (app reiniciado
                // e o estado local foi perdido, ou callback duplicado) — nada a
                // fazer aqui além de não travar; a UI trata via recoverPendingAttempt.
                return@launch
            }

            val result = decodeResult(uri, attempt.attemptId)

            // Aprovação é gravada em disco ANTES de qualquer tentativa de
            // entregá-la: se o processo morrer no instante seguinte, a prova
            // de que o cliente foi cobrado sobrevive e o registro é retomado
            // ao reabrir o app (ver HomeViewModel/ApprovedCieloResult) — em
            // vez de virar `Unknown` e o dinheiro sumir do sistema.
            if (result is PaymentResult.Approved) {
                pendingAttemptStore.saveApprovedResult(
                    ApprovedCieloResult(
                        attemptId = result.attemptId,
                        tabId = attempt.tabId,
                        amountCents = result.amount.cents,
                        providerTransactionId = result.providerTransactionId,
                        method = attempt.method,
                        approvedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            }

            resultBridge.emit(result)
        }
    }

    private fun decodeResult(uri: android.net.Uri, attemptId: String): PaymentResult {
        val encoded = uri.getQueryParameter("response")
            ?: return PaymentResult.Failed(attemptId, "Callback da Cielo sem parâmetro 'response'.")

        val decodedBytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }
            .getOrElse { return PaymentResult.Failed(attemptId, "Falha ao decodificar resposta da Cielo (Base64 inválido).") }
        val decodedJson = String(decodedBytes, Charsets.UTF_8)

        return decodeCieloResponseJson(json, decodedJson, attemptId)
    }
}

/**
 * Extraído de [CieloPaymentResponseActivity.decodeResult] para ser
 * testável sem Android (o único código Android-specific dali é ler o
 * parâmetro da URI e o Base64.decode). A Cielo usa o MESMO formato
 * Base64(JSON) tanto para sucesso quanto para erro/cancelamento — só o
 * shape do JSON interno muda (payments[] vs. code/reason). Tenta erro
 * primeiro porque tem menos campos obrigatórios (mais fácil de descartar
 * por engano se a ordem fosse invertida).
 */
internal fun decodeCieloResponseJson(json: Json, decodedJson: String, attemptId: String): PaymentResult {
    val errorBody = runCatching { json.decodeFromString<CieloErrorBody>(decodedJson) }.getOrNull()
    if (errorBody != null) {
        return when (errorBody.code) {
            CieloErrorCode.CANCELLED_BY_USER -> PaymentResult.Cancelled(attemptId, errorBody.reason)
            else -> PaymentResult.Declined(attemptId, errorBody.reason)
        }
    }

    val successBody = runCatching { json.decodeFromString<CieloPaymentResponseBody>(decodedJson) }.getOrNull()
        ?: return PaymentResult.Failed(attemptId, "Resposta da Cielo em formato inesperado.")

    val payment = successBody.payments.firstOrNull()
        ?: return PaymentResult.Failed(attemptId, "Resposta da Cielo sem dados de pagamento.")

    // `paidAmount` (nível topo do JSON) e `payments[0].amount` deveriam
    // trazer o mesmo valor, mas o LIO Emulator observado (1.61.9) sempre
    // manda paidAmount=0 mesmo com o pagamento aprovado de verdade — o
    // valor real só vem em payments[0].amount. Preferir sempre o valor
    // da transação individual (é o que a Cielo de fato capturou do
    // cartão); paidAmount só entra como fallback se, por algum motivo,
    // o valor do pagamento em si vier zerado/ausente.
    val approvedAmountCents = payment.amount.takeIf { it > 0 } ?: successBody.paidAmount

    return PaymentResult.Approved(
        attemptId = attemptId,
        amount = com.nokta.pos.common.Money(approvedAmountCents),
        providerTransactionId = payment.cieloCode ?: successBody.id ?: attemptId,
        authorizationCode = payment.authCode,
        brand = payment.brand,
        maskedCardNumber = payment.mask,
        installments = payment.installments,
    )
}
