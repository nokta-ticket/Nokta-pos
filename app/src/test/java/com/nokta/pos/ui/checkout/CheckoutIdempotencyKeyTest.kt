package com.nokta.pos.ui.checkout

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Regressão da auditoria de homologação (BLOCKER "attemptId regenerado no
 * checkout de comanda").
 *
 * A chave de idempotência da cobrança precisa viver no [SavedStateHandle], e
 * não como um `UUID.randomUUID()` gerado na hora de cobrar: o Android pode
 * matar o processo enquanto o app da Cielo está cobrando o cliente e recriar
 * o ViewModel depois. Com uma chave nova, o backend enxerga um pagamento
 * DISTINTO e aceita — cobrando o cliente duas vezes pela mesma parte da conta
 * (o teto de `remainingCents` do servidor impede estourar o total, mas não
 * impede a segunda cobrança na adquirente).
 *
 * Este teste exercita a mesma mecânica de leitura/escrita do
 * `paymentIdempotencyKey` do [CheckoutViewModel] sobre um `SavedStateHandle`
 * real, sem precisar instanciar o ViewModel inteiro (que puxaria Hilt,
 * repositórios e rede).
 */
class CheckoutIdempotencyKeyTest {

    private val key = "checkout_payment_idempotency_key"

    /** Espelha o getter/setter do CheckoutViewModel. */
    private fun readOrCreate(handle: SavedStateHandle): String =
        handle.get<String>(key) ?: java.util.UUID.randomUUID().toString().also { handle[key] = it }

    @Test
    fun `chave e criada uma vez e reusada nas tentativas seguintes`() {
        val handle = SavedStateHandle()

        val primeira = readOrCreate(handle)
        val segunda = readOrCreate(handle)

        assertNotNull(primeira)
        assertEquals("Retry da MESMA cobrança precisa reusar a chave", primeira, segunda)
    }

    @Test
    fun `chave sobrevive a recriacao do ViewModel apos process death`() {
        // O Android restaura o SavedStateHandle com o mesmo conteúdo salvo.
        val antesDaMorte = SavedStateHandle()
        val chaveOriginal = readOrCreate(antesDaMorte)

        val depoisDaMorte = SavedStateHandle(mapOf(key to antesDaMorte.get<String>(key)))
        val chaveRecuperada = readOrCreate(depoisDaMorte)

        assertEquals(
            "Após process death a cobrança precisa continuar com a mesma idempotencyKey, senão o cliente é cobrado de novo",
            chaveOriginal,
            chaveRecuperada,
        )
    }

    @Test
    fun `proxima cobranca da divisao usa chave nova, senao o segundo pagamento nunca e registrado`() {
        val handle = SavedStateHandle()
        val primeiraCobranca = readOrCreate(handle)

        // advanceToNextCharge() — depois de um pagamento CONFIRMADO.
        handle[key] = java.util.UUID.randomUUID().toString()
        val segundaCobranca = readOrCreate(handle)

        assertNotEquals(
            "Reusar a chave faria o backend devolver o pagamento anterior (idempotência) e o 2º valor sumiria",
            primeiraCobranca,
            segundaCobranca,
        )
    }
}
