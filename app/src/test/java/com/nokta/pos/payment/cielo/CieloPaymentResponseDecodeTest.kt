package com.nokta.pos.payment.cielo

import com.nokta.pos.payment.domain.PaymentResult
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressão: reproduzido no LIO Emulator 1.61.9 — pagamento aprovado, mas o
 * registro no backend falhava com "amountCents must be a positive number".
 * O callback real da Cielo manda `paidAmount: 0` no nível topo do JSON
 * mesmo com o pagamento aprovado; o valor real só vem em
 * `payments[0].amount`. decodeCieloResponseJson deve preferir sempre o
 * valor da transação individual.
 */
class CieloPaymentResponseDecodeTest {

    private val json = Json { ignoreUnknownKeys = true }

    // JSON real capturado do callback do LIO Emulator 1.61.9 (log de debug
    // em produção, 2026-09-02) — paidAmount=0, payments[0].amount=1800.
    private val realEmulatorCallbackJson = """
        {"createdAt":"Sep 2, 2026 9:37:38 PM","id":"0e91bfd2-2b39-4721-8b80-090b66b3a693",
        "items":[{"description":"","details":"","id":"435c4d7a-3f6a-4adc-8c80-2c306462e3ed",
        "name":"Comanda #0","quantity":1,"reference":"","sku":"0","unitOfMeasure":"unidade","unitPrice":1800}],
        "notes":"","number":"","paidAmount":0,
        "payments":[{"accessKey":"9d973d9017f34043aa733223b929dcd0","amount":1800,
        "applicationName":"CieloLIO","authCode":"SOIV0h","brand":"mock_brand","cieloCode":"Cfr7ui",
        "description":"","discountedAmount":0,"externalId":"mock_externalId",
        "id":"c0057425-221f-4dcc-8398-215ba54b782e","installments":1,"mask":"mock_mask",
        "merchantCode":"null","primaryCode":"1000","requestDate":"1534787576000",
        "secondaryCode":"1","terminal":"mock_terminal"}],
        "pendingAmount":0,"price":1800,"reference":"6e681ba8-deee-4361-83bb-c990f73f23db",
        "status":"PAID","type":"PAYMENT","updatedAt":"Sep 2, 2026 9:37:38 PM"}
    """.trimIndent()

    @Test
    fun `paidAmount zerado usa payments0 amount como valor real aprovado`() {
        val result = decodeCieloResponseJson(json, realEmulatorCallbackJson, "attempt-1")
        assertTrue(result is PaymentResult.Approved)
        assertEquals(1800L, (result as PaymentResult.Approved).amount.cents)
    }

    @Test
    fun `paidAmount coerente com payments0 amount continua funcionando`() {
        val body = """{"paidAmount":900,"payments":[{"amount":900,"cieloCode":"abc"}]}"""
        val result = decodeCieloResponseJson(json, body, "attempt-2")
        assertTrue(result is PaymentResult.Approved)
        assertEquals(900L, (result as PaymentResult.Approved).amount.cents)
    }

    @Test
    fun `payments0 amount zerado cai para paidAmount como fallback`() {
        val body = """{"paidAmount":700,"payments":[{"amount":0,"cieloCode":"abc"}]}"""
        val result = decodeCieloResponseJson(json, body, "attempt-3")
        assertTrue(result is PaymentResult.Approved)
        assertEquals(700L, (result as PaymentResult.Approved).amount.cents)
    }

    @Test
    fun `resposta sem payments nunca vira aprovado silenciosamente`() {
        val body = """{"paidAmount":900,"payments":[]}"""
        val result = decodeCieloResponseJson(json, body, "attempt-4")
        assertTrue(result is PaymentResult.Failed)
    }
}
