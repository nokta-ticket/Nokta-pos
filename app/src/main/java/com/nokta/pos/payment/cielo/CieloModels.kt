package com.nokta.pos.payment.cielo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * paymentCode aceitos pela Cielo Smart (manual oficial, seção "Pagamento").
 * CREDITO_PARCELADO_LOJA = parcelado sem juros da loja (o mais comum para
 * comanda de bar/restaurante); os demais existem para paridade futura.
 */
enum class CieloPaymentCode {
    DEBITO_AVISTA,
    CREDITO_AVISTA,
    CREDITO_PARCELADO_LOJA,
    CREDITO_PARCELADO_BNCO,
    CREDITO_PARCELADO_ADM,
    PIX,
    VOUCHER_ALIMENTACAO,
    VOUCHER_REFEICAO,
    PRE_AUTORIZACAO,
}

@Serializable
data class CieloOrderItem(
    val name: String,
    val quantity: Int,
    val sku: String,
    val unitOfMeasure: String = "unidade",
    val unitPrice: Long,
)

/**
 * Corpo do request de pagamento, exatamente como documentado em
 * developercielo.github.io/manual/cielo-lio#pagamento — nomes de campo
 * literais, nunca inventados. `value` e `installments` são String porque a
 * documentação oficial os declara como string (não number) no JSON de
 * exemplo.
 */
@Serializable
data class CieloPaymentRequestBody(
    val accessToken: String,
    val clientID: String,
    val reference: String,
    val merchantCode: String? = null,
    val email: String? = null,
    val installments: String,
    val items: List<CieloOrderItem>,
    val paymentCode: String,
    val value: String,
)

@Serializable
data class CieloPaymentInfo(
    val amount: Long,
    val authCode: String? = null,
    val brand: String? = null,
    val cieloCode: String? = null,
    val installments: Int = 0,
    val mask: String? = null,
    val merchantCode: String? = null,
)

/** Resposta de sucesso decodificada do deep link de callback (order://response). */
@Serializable
data class CieloPaymentResponseBody(
    val createdAt: String? = null,
    val id: String? = null,
    val items: List<CieloOrderItem> = emptyList(),
    val paidAmount: Long,
    val payments: List<CieloPaymentInfo> = emptyList(),
)

/**
 * Resposta de erro/cancelamento decodificada do deep link de callback —
 * mesmo formato Base64(JSON) do payload de sucesso, mas com `code`/`reason`.
 * code 1 = cancelado pelo usuário; 2 = erro genérico; 3 = erro no pagamento;
 * 4 = erro de autenticação (manual oficial, seção "Códigos de Erro").
 */
@Serializable
data class CieloErrorBody(
    val code: Int,
    val reason: String,
)

object CieloErrorCode {
    const val CANCELLED_BY_USER = 1
    const val GENERIC_ERROR = 2
    const val PAYMENT_ERROR = 3
    const val AUTH_ERROR = 4
}
