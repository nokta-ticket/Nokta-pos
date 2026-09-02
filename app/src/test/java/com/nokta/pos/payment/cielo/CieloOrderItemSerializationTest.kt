package com.nokta.pos.payment.cielo

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressão: o app Cielo (LIO Emulator 1.61.9+) exige `unitOfMeasure`
 * presente no JSON de todo item — campo ausente derruba o pagamento com
 * "Parameter specified as non-null is null: ... unitOfMeasure" antes mesmo
 * da tela de pagamento abrir. `CieloOrderItem.unitOfMeasure` sempre teve um
 * valor correto ("unidade" por padrão) — a causa real era Json.Default
 * omitir campos iguais ao default na serialização, nunca a origem do dado
 * em si. `cieloRequestJson` (encodeDefaults = true) corrige isso de forma
 * geral: nenhum item real de produto passa por este payload hoje (só um
 * item-resumo "Comanda #tabId"), então não há caminho por produto pra
 * regredir — testar a instância real de serialização já cobre o caso geral.
 */
class CieloOrderItemSerializationTest {

    @Test
    fun `unitOfMeasure no valor default ainda aparece no JSON serializado`() {
        val item = CieloOrderItem(name = "Comanda #1", quantity = 1, sku = "1", unitPrice = 900)
        val json = cieloRequestJson.encodeToString(CieloOrderItem.serializer(), item)
        assertTrue("esperava \"unitOfMeasure\" no JSON, veio: $json", json.contains("\"unitOfMeasure\":\"unidade\""))
    }

    @Test
    fun `unitOfMeasure customizado tambem e serializado`() {
        val item = CieloOrderItem(name = "Chopp", quantity = 2, sku = "42", unitOfMeasure = "litro", unitPrice = 1200)
        val json = cieloRequestJson.encodeToString(CieloOrderItem.serializer(), item)
        assertTrue(json.contains("\"unitOfMeasure\":\"litro\""))
    }

    @Test
    fun `payload completo do pedido nunca omite unitOfMeasure de nenhum item`() {
        val body = CieloPaymentRequestBody(
            accessToken = "token",
            clientID = "client",
            reference = "attempt-1",
            installments = "1",
            items = listOf(
                CieloOrderItem(name = "Comanda #1", quantity = 1, sku = "1", unitPrice = 900),
            ),
            paymentCode = "CREDITO_AVISTA",
            value = "900",
        )
        val json = cieloRequestJson.encodeToString(CieloPaymentRequestBody.serializer(), body)
        assertTrue(json.contains("\"unitOfMeasure\""))
    }
}
