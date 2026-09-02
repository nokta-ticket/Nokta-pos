package com.nokta.pos.payment.cielo

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.nokta.pos.common.Money
import com.nokta.pos.payment.domain.CancelPaymentRequest
import com.nokta.pos.payment.domain.CancelPaymentResult
import com.nokta.pos.payment.domain.PaymentProvider
import com.nokta.pos.payment.domain.PaymentRequest
import com.nokta.pos.payment.domain.PaymentResult
import com.nokta.pos.payment.domain.PosPaymentMethod
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Json.Default omite qualquer campo cujo valor bate com o default declarado
 * na data class (ex.: CieloOrderItem.unitOfMeasure = "unidade") — o app
 * Cielo, do outro lado, desserializa pra uma classe SEM default nesse
 * parâmetro, então "campo ausente no JSON" vira
 * "Parameter specified as non-null is null: ... unitOfMeasure" mesmo com o
 * valor certo já presente no código Kotlin (reproduzido no LIO Emulator
 * 1.61.9). `internal` (não private) para o teste de serialização exercitar
 * exatamente a instância usada em produção. Só o payload de SAÍDA pra Cielo
 * usa isto — decodificar a resposta dela (CieloPaymentResponseBody/
 * CieloPaymentErrorBody) continua no Json.Default de sempre.
 */
internal val cieloRequestJson = Json { encodeDefaults = true }

/**
 * Único ponto do app que fala com o app Cielo Smart via deep link (Intent).
 * Todo o resto do app (Checkout, ViewModels) conhece só a interface
 * PaymentProvider — trocar de adquirente no futuro nunca toca fora deste
 * arquivo (ver seção 4 do PRD).
 *
 * Fluxo (manual oficial "Integração via Deep Link"):
 *  1. Monta o JSON de requisição, codifica em Base64.
 *  2. Dispara Intent ACTION_VIEW para lio://payment?request=...&urlCallback=order://response
 *  3. Cielo Smart processa o pagamento localmente no terminal.
 *  4. Cielo Smart devolve o resultado chamando de volta order://response —
 *     capturado por CieloPaymentResponseActivity, que decodifica e publica
 *     em CieloResultBridge.
 *  5. Esta classe está suspensa aguardando exatamente esse resultado.
 *
 * "Intent disparado com sucesso" NUNCA é tratado como pagamento aprovado
 * (seção 20 do PRD) — só o retorno decodificado do callback oficial decide.
 */
@Singleton
class CieloDeepLinkPaymentProvider @Inject constructor(
    private val context: Context,
    private val credentialsProvider: CieloCredentialsProvider,
    private val resultBridge: CieloResultBridge,
    private val pendingAttemptStore: PendingCieloAttemptStore,
) : PaymentProvider {

    override suspend fun startPayment(request: PaymentRequest): PaymentResult {
        val credentials = credentialsProvider.current()
            ?: return PaymentResult.Failed(request.attemptId, "Terminal não configurado para pagamento por cartão. Reabra o pareamento.")

        pendingAttemptStore.save(
            PendingCieloAttempt(
                attemptId = request.attemptId,
                tabId = request.tabId,
                amountCents = request.amount.cents,
                startedAtEpochMs = System.currentTimeMillis(),
            ),
        )

        val paymentCode = paymentCodeFor(request.method, request.installments)
        val body = CieloPaymentRequestBody(
            accessToken = credentials.accessToken,
            clientID = credentials.clientId,
            reference = request.attemptId,
            merchantCode = credentials.merchantCode,
            installments = request.installments.coerceAtLeast(0).toString(),
            items = listOf(
                CieloOrderItem(
                    name = "Comanda #${request.tabId}",
                    quantity = 1,
                    sku = request.tabId.toString(),
                    unitPrice = request.amount.cents,
                ),
            ),
            paymentCode = paymentCode.name,
            value = request.amount.cents.toString(),
        )

        val encoded = Base64.encodeToString(
            cieloRequestJson.encodeToString(body).toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        val uri = Uri.parse("lio://payment?request=$encoded&urlCallback=order://response")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            pendingAttemptStore.clear()
            return PaymentResult.Failed(request.attemptId, "Aplicativo da Cielo não encontrado neste terminal.")
        }

        // Timeout generoso (o operador pode demorar inserindo/aproximando o
        // cartão) — não é um timeout de rede, é "o app Cielo nunca voltou".
        // Ao expirar, o resultado vira Unknown (nunca Failed/Declined
        // silenciosamente) e fica pendente de recuperação manual.
        val result = withTimeoutOrNull(PAYMENT_CALLBACK_TIMEOUT_MS) {
            resultBridge.results.first { it.attemptId == request.attemptId }
        } ?: PaymentResult.Unknown(request.attemptId)

        if (result !is PaymentResult.Unknown) {
            pendingAttemptStore.clear()
        }
        return result
    }

    /**
     * Ao reabrir o app depois de processo morto, verifica se havia um
     * pagamento em andamento sem resultado conhecido — a UI deve bloquear
     * nova cobrança para a mesma comanda até isto ser resolvido (seção 24 e
     * 47 do PRD). Este MVP não implementa consulta ativa de status contra a
     * Cielo (não documentado no manual como deep link síncrono) — a
     * resolução é manual: o operador confirma com o cliente/extrato do
     * terminal se a cobrança passou, e o gerente registra o pagamento
     * manualmente ou tenta de novo.
     */
    suspend fun recoverPendingAttempt(): PendingCieloAttempt? = pendingAttemptStore.current()

    suspend fun discardPendingAttempt() = pendingAttemptStore.clear()

    override suspend fun cancelPayment(request: CancelPaymentRequest): CancelPaymentResult {
        // O manual oficial não documenta parametrização de cancelamento via
        // deep link síncrono para o app parceiro (seção "Cancelamento" só
        // referencia o fluxo dentro do próprio app Cielo/emulador). Um
        // pagamento já aprovado na Cielo não pode ser simplesmente desfeito
        // do lado Nokta sem confirmação da adquirente (seção 26 do PRD) —
        // por isso este MVP nunca chama a Cielo aqui: CANCELAR um
        // VenuePayment já registrado é uma operação puramente gerencial no
        // backend Nokta (estorno/ajuste manual), documentada como pendência
        // em docs/cielo-smart-integration.md até a Cielo confirmar o
        // contrato de estorno via deep link.
        return CancelPaymentResult(
            attemptId = request.originalAttemptId,
            succeeded = false,
            errorMessage = "Cancelamento via Cielo não suportado neste MVP — ver docs/cielo-smart-integration.md.",
        )
    }

    private fun paymentCodeFor(method: PosPaymentMethod, installments: Int): CieloPaymentCode = when (method) {
        PosPaymentMethod.DEBIT_CARD -> CieloPaymentCode.DEBITO_AVISTA
        PosPaymentMethod.CREDIT_CARD -> if (installments > 1) CieloPaymentCode.CREDITO_PARCELADO_LOJA else CieloPaymentCode.CREDITO_AVISTA
        PosPaymentMethod.PIX -> CieloPaymentCode.PIX
        PosPaymentMethod.CASH -> error("CASH nunca passa pelo provider Cielo — registrado direto no backend.")
    }

    private companion object {
        const val PAYMENT_CALLBACK_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutos
    }
}

/**
 * `clientId`/`accessToken` identificam a NOKTA como integradora perante a
 * Cielo — globais, os mesmos para qualquer terminal de qualquer cliente.
 * `merchantCode` (EC) é o que varia por unidade: identifica QUEM recebe o
 * dinheiro daquela venda — nulo em Sandbox (a Cielo não exige) ou enquanto a
 * unidade não cadastrou o EC real no dashboard. Nunca confundir os dois —
 * ver comentário do model PaymentAcquirerConfig no backend
 * (nokta-api/prisma/schema.prisma).
 */
data class CieloCredentials(val clientId: String, val accessToken: String, val merchantCode: String?)

/**
 * As credenciais (Client-ID/Access Token da Cielo) NUNCA vêm hardcoded no
 * APK (seção 46 do PRD) — chegam do backend Nokta no momento do pareamento
 * do terminal (VenueDevice) e ficam só no armazenamento seguro do
 * dispositivo (EncryptedSharedPreferences via DeviceCredentialsStore).
 */
interface CieloCredentialsProvider {
    suspend fun current(): CieloCredentials?
}
