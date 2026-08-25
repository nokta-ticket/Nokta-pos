package com.nokta.pos.network.dto

import kotlinx.serialization.Serializable

// ---- Comanda (VenueTab) ----

@Serializable
data class CreateTabRequest(
    val type: String, // TABLE | INDIVIDUAL | COUNTER
    val tableId: Long? = null,
    val customerName: String? = null,
    val customerPhone: String? = null,
    val guestCount: Int? = null,
    /**
     * Chave de idempotência offline-first — o `localId` (UUID) gerado no
     * momento em que a comanda é criada localmente. Retry do mesmo request
     * (fila de sincronização reenviando após rede caiu no meio) nunca abre
     * uma segunda comanda: o backend devolve a já criada.
     */
    val clientRequestId: String? = null,
)

@Serializable
data class TabTableResponse(val id: Long, val nome: String)

/**
 * `GET tabs/:tabId` já devolve `orders[].items[].modifiers[]` e `payments[]`
 * (TAB_DETAIL_INCLUDE, backend `venue-tabs.service.ts`). Estes campos existiam
 * na resposta desde sempre e eram descartados aqui — declará-los é o que
 * permite mostrar consumo, histórico e pagamentos sem nenhum endpoint novo.
 * `@Serializable` com `ignoreUnknownKeys` continua tolerando o resto do shape.
 */
@Serializable
data class TabResponse(
    val id: Long,
    val organizationId: Long,
    val locationId: Long,
    val tableId: Long? = null,
    val table: TabTableResponse? = null,
    val publicCode: String,
    val type: String,
    val status: String, // OPEN | CLOSED | CANCELED
    val customerName: String? = null,
    val customerPhone: String? = null,
    val guestCount: Int? = null,
    val subtotalCents: Long,
    val discountCents: Long,
    val serviceChargeCents: Long,
    val totalCents: Long,
    val paidCents: Long,
    val remainingCents: Long,
    val version: Int,
    val openedAt: String? = null,
    val orders: List<OrderResponse> = emptyList(),
    val payments: List<PaymentResponse> = emptyList(),
)

// ---- Mesa (VenueTable) ----

/**
 * `GET locations/:locationId/tables` já devolve a comanda aberta de cada mesa
 * (`openTab`, backend `venue-tables.service.ts:43`) — ocupação nunca é campo
 * salvo, é derivada. Uma chamada resolve a tela de mesas inteira.
 */
@Serializable
data class TableOpenTabResponse(
    val id: Long,
    val publicCode: String? = null,
    val type: String? = null,
    val totalCents: Long = 0,
    val paidCents: Long = 0,
    val remainingCents: Long = 0,
    val customerName: String? = null,
    val openedAt: String? = null,
)

@Serializable
data class TableResponse(
    val id: Long,
    val locationId: Long,
    val areaId: Long? = null,
    val nome: String,
    val capacidade: Int? = null,
    val active: Boolean = true,
    val displayOrder: Int = 0,
    val openTab: TableOpenTabResponse? = null,
)

// ---- Pedido (VenueOrder) ----

@Serializable
data class OrderItemModifierRequest(
    val modifierGroupId: Long,
    val modifierOptionId: Long,
    val quantity: Int? = null,
)

@Serializable
data class CreateOrderItemRequest(
    val menuItemId: Long,
    val variantId: Long,
    val quantity: Int,
    val notes: String? = null,
    val modifiers: List<OrderItemModifierRequest>? = null,
)

@Serializable
data class CreateOrderRequest(
    val items: List<CreateOrderItemRequest>,
    val clientRequestId: String? = null,
    val notes: String? = null,
)

/** `reason` é obrigatório no backend — cancelamento sempre fica auditado com o motivo. */
@Serializable
data class CancelOrderItemRequest(val reason: String)

@Serializable
data class OrderItemModifierResponse(
    val id: Long,
    val modifierGroupId: Long? = null,
    val modifierOptionId: Long? = null,
    val groupNameSnapshot: String? = null,
    val optionNameSnapshot: String? = null,
    val quantity: Int = 1,
    val unitPriceCents: Long = 0,
    val totalPriceCents: Long = 0,
)

@Serializable
data class OrderItemResponse(
    val id: Long,
    val productId: Long,
    val variantId: Long,
    val quantity: Int,
    val productNameSnapshot: String,
    val variantNameSnapshot: String,
    val unitPriceCents: Long,
    val modifiersTotalCents: Long,
    val lineTotalCents: Long,
    val status: String,
    val notes: String? = null,
    val createdAt: String? = null,
    val modifiers: List<OrderItemModifierResponse> = emptyList(),
)

@Serializable
data class OrderResponse(
    val id: Long,
    val tabId: Long,
    val publicCode: String,
    val status: String, // DRAFT | SENT | ...
    val createdAt: String? = null,
    val items: List<OrderItemResponse> = emptyList(),
)

// ---- Pagamento (VenuePayment) ----

@Serializable
data class CreatePaymentRequest(
    val method: String, // CASH | PIX | DEBIT_CARD | CREDIT_CARD | VOUCHER | OTHER
    val amountCents: Long,
    val receivedCents: Long? = null,
    val idempotencyKey: String,
    val externalReference: String? = null,
    val notes: String? = null,
)

@Serializable
data class PaymentResponse(
    val id: Long,
    val tabId: Long,
    val method: String,
    val status: String, // CONFIRMED | CANCELED
    val amountCents: Long,
    val receivedCents: Long? = null,
    val changeCents: Long? = null,
    val externalReference: String? = null,
    val notes: String? = null,
    val confirmedAt: String? = null,
    val createdAt: String? = null,
)
