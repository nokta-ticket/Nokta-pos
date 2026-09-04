package com.nokta.pos.payment.cielo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nokta.pos.common.Money
import com.nokta.pos.payment.domain.PaymentRequest
import com.nokta.pos.payment.domain.PaymentResult
import com.nokta.pos.payment.domain.PosPaymentMethod
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regressões da auditoria de homologação, área "pagamento aprovado na Cielo
 * + falha na Nokta" (cenário G) e "nunca cobrar de novo por não ter
 * registrado a resposta".
 */
@RunWith(RobolectricTestRunner::class)
class CieloPendingGuardTest {

    private lateinit var context: Context
    private lateinit var store: PendingCieloAttemptStore
    private lateinit var provider: CieloDeepLinkPaymentProvider

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = PendingCieloAttemptStore(context)
        provider = CieloDeepLinkPaymentProvider(
            context = context,
            credentialsProvider = object : CieloCredentialsProvider {
                override suspend fun current() = CieloCredentials("client", "token", "EC")
            },
            resultBridge = CieloResultBridge(),
            pendingAttemptStore = store,
        )
    }

    @Test
    fun `nova cobranca e bloqueada enquanto existe tentativa anterior sem desfecho`() = runTest {
        // Tentativa anterior gravada e nunca resolvida (timeout/process death).
        store.save(
            PendingCieloAttempt(
                attemptId = "attempt-antigo",
                tabId = 1,
                amountCents = 5_000,
                startedAtEpochMs = System.currentTimeMillis(),
                method = PosPaymentMethod.CREDIT_CARD.name,
            ),
        )

        val result = provider.startPayment(
            PaymentRequest(
                attemptId = "attempt-novo",
                tabId = 1,
                amount = Money(5_000),
                method = PosPaymentMethod.CREDIT_CARD,
            ),
        )

        assertTrue(
            "Cobrança nova deveria ser recusada enquanto a anterior está sem resultado confirmado",
            result is PaymentResult.Failed,
        )
        // E a tentativa antiga continua intacta — nunca sobrescrita.
        assertEquals("attempt-antigo", store.current()?.attemptId)
    }

    @Test
    fun `nova cobranca e bloqueada enquanto existe aprovacao ainda nao registrada`() = runTest {
        store.saveApprovedResult(
            ApprovedCieloResult(
                attemptId = "attempt-aprovado",
                tabId = 1,
                amountCents = 5_000,
                providerTransactionId = "NSU-1",
                method = PosPaymentMethod.CREDIT_CARD.name,
                approvedAtEpochMs = System.currentTimeMillis(),
            ),
        )

        val result = provider.startPayment(
            PaymentRequest(
                attemptId = "attempt-novo",
                tabId = 1,
                amount = Money(5_000),
                method = PosPaymentMethod.CREDIT_CARD,
            ),
        )

        assertTrue(
            "Cobrança nova deveria ser recusada com uma aprovação ainda não registrada — o cliente já foi cobrado",
            result is PaymentResult.Failed,
        )
        assertNotNull(store.approvedResult())
    }

    @Test
    fun `resultado aprovado sobrevive ao process death e traz o que o registro precisa`() = runTest {
        // O que a Activity de callback grava antes de emitir.
        store.saveApprovedResult(
            ApprovedCieloResult(
                attemptId = "attempt-1",
                tabId = 42,
                amountCents = 7_350,
                providerTransactionId = "NSU-999",
                method = PosPaymentMethod.DEBIT_CARD.name,
                approvedAtEpochMs = 1_700_000_000_000,
            ),
        )

        // Simula reabertura do app: uma instância nova do store lê o disco.
        val recovered = PendingCieloAttemptStore(context).approvedResult()

        assertNotNull("Aprovação precisa sobreviver ao process death", recovered)
        // A idempotencyKey do registro é a MESMA da cobrança — é isso que
        // garante que retomar não gera uma segunda cobrança.
        assertEquals("attempt-1", recovered!!.attemptId)
        assertEquals(42L, recovered.tabId)
        assertEquals(7_350L, recovered.amountCents)
        assertEquals("NSU-999", recovered.providerTransactionId)
        assertEquals(PosPaymentMethod.DEBIT_CARD.name, recovered.method)
    }
}
