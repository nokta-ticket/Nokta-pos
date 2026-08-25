package com.nokta.pos.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A fila do outbox é persistida como JSON. Se a serialização quebrar, uma
 * venda feita offline some sem ninguém perceber — por isso estes testes
 * existem: eles protegem contra perda silenciosa de operação, não contra
 * "o código compila".
 *
 * O ponto delicado é a hierarquia selada: kotlinx.serialization precisa do
 * discriminador de tipo para reconstruir a subclasse certa ao ler de volta.
 */
class OutboxSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `pedido sobrevive a ida e volta do json preservando o clientRequestId`() {
        val original: OutboxOperation = OutboxOperation.SubmitOrder(
            id = "op-1",
            organizationId = 7,
            createdAtEpochMs = 1_700_000_000_000,
            tabId = 42,
            clientRequestId = "req-abc",
            lines = listOf(
                OutboxOrderLine(
                    menuItemId = 10,
                    variantId = 11,
                    quantity = 2,
                    notes = "8 tradicionais e 2 melancia",
                    modifiers = listOf(OutboxOrderLineModifier(1, 2, 3)),
                ),
            ),
        )

        val restored = json.decodeFromString<OutboxOperation>(json.encodeToString(original))

        assertTrue(restored is OutboxOperation.SubmitOrder)
        restored as OutboxOperation.SubmitOrder
        // clientRequestId é o que impede o pedido de duplicar no reenvio.
        assertEquals("req-abc", restored.clientRequestId)
        assertEquals(42L, restored.tabId)
        assertEquals(1, restored.lines.size)
        // A observação carrega o pedido real do cliente — não pode se perder.
        assertEquals("8 tradicionais e 2 melancia", restored.lines[0].notes)
        assertEquals(3, restored.lines[0].modifiers[0].quantity)
    }

    @Test
    fun `pagamento sobrevive a ida e volta preservando a chave de idempotencia`() {
        val original: OutboxOperation = OutboxOperation.RegisterPayment(
            id = "op-2",
            organizationId = 7,
            createdAtEpochMs = 1_700_000_000_000,
            tabId = 42,
            method = "CASH",
            amountCents = 5_000,
            receivedCents = 10_000,
            idempotencyKey = "idem-xyz",
        )

        val restored = json.decodeFromString<OutboxOperation>(json.encodeToString(original))

        assertTrue(restored is OutboxOperation.RegisterPayment)
        restored as OutboxOperation.RegisterPayment
        // Sem esta chave intacta, um reenvio cobraria o cliente duas vezes.
        assertEquals("idem-xyz", restored.idempotencyKey)
        assertEquals(5_000L, restored.amountCents)
        assertEquals(10_000L, restored.receivedCents)
    }

    @Test
    fun `fila mista mantem a ordem entre pedido e pagamento`() {
        // A ordem importa: o pagamento precisa chegar DEPOIS dos itens, senão
        // seria maior que o total conhecido pelo servidor e seria recusado.
        val queue: List<OutboxOperation> = listOf(
            OutboxOperation.SubmitOrder(
                id = "op-1", organizationId = 1, createdAtEpochMs = 1, tabId = 5,
                clientRequestId = "r1", lines = emptyList(),
            ),
            OutboxOperation.RegisterPayment(
                id = "op-2", organizationId = 1, createdAtEpochMs = 2, tabId = 5,
                method = "CASH", amountCents = 1_000, idempotencyKey = "k1",
            ),
        )

        val restored = json.decodeFromString<List<OutboxOperation>>(json.encodeToString(queue))

        assertEquals(2, restored.size)
        assertTrue(restored[0] is OutboxOperation.SubmitOrder)
        assertTrue(restored[1] is OutboxOperation.RegisterPayment)
        assertEquals("op-1", restored[0].id)
        assertEquals("op-2", restored[1].id)
    }

    @Test
    fun `json desconhecido no futuro nao derruba a leitura da fila`() {
        // Uma versão futura do app pode acrescentar campos. Ler uma fila
        // gravada por essa versão não pode explodir e perder as vendas.
        val withExtraField = """
            {"type":"com.nokta.pos.sync.OutboxOperation.RegisterPayment",
             "id":"op-3","organizationId":1,"createdAtEpochMs":1,"tabId":5,
             "method":"PIX","amountCents":2500,"idempotencyKey":"k2",
             "campoQueAindaNaoExiste":"algo"}
        """.trimIndent()

        val restored = json.decodeFromString<OutboxOperation>(withExtraField)
        assertEquals(2_500L, (restored as OutboxOperation.RegisterPayment).amountCents)
    }
}
