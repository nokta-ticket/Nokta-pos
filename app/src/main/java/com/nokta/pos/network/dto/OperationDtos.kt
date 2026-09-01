package com.nokta.pos.network.dto

import kotlinx.serialization.Serializable

// ---- Caixa (VenueCashSession) ----

/** `GET locations/:id/cash-status` — só isOpen, sem nenhum dado sensível (valores, quem abriu). */
@Serializable
data class CashStatusResponse(val isOpen: Boolean)

// ---- Comanda (VenueTab) ----

@Serializable
data class CreateTabRequest(
    val type: String, // TABLE | INDIVIDUAL | COUNTER | WRISTBAND
    val tableId: Long? = null,
    /**
     * Número/nome digitado na hora pelo garçom — não existe cadastro prévio
     * de mesa no fluxo real do POS. O backend resolve por nome (cria a mesa
     * automaticamente se não existir) e, se a mesa já tiver comanda aberta,
     * devolve essa comanda em vez de criar uma segunda. Exatamente um entre
     * tableId/tableName quando type=TABLE.
     */
    val tableName: String? = null,
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
    /** Obrigatório quando type=WRISTBAND — número já impresso na pulseira física. */
    val publicCode: String? = null,
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
    /** Base 10000 = 100% — vem direto do Prisma (VenueTab.serviceChargeRateBps), só para exibição. */
    val serviceChargeRateBps: Int = 0,
    val totalCents: Long,
    val paidCents: Long,
    val remainingCents: Long,
    val version: Int,
    val openedAt: String? = null,
    val orders: List<OrderResponse> = emptyList(),
    val payments: List<PaymentResponse> = emptyList(),
    /**
     * Presente (não-null) quando esta comanda foi aberta pelo fluxo de
     * "Cartão físico" (`TAB_DETAIL_INCLUDE`/`list()`, backend
     * `venue-tabs.service.ts`) — só o vínculo importa aqui, nunca o objeto
     * completo do cartão. `type` sozinho não distingue isso: uma comanda
     * de cartão físico nasce `INDIVIDUAL`, o mesmo tipo de uma comanda
     * avulsa aberta por outro meio.
     */
    val physicalCard: TabPhysicalCardRef? = null,
    /**
     * Soma das quantidades dos itens não cancelados, já calculada pelo
     * backend. Só vem no endpoint de LISTA (`listTabs`), que por eficiência
     * não devolve orders/items — sem isto as listas do POS não teriam como
     * mostrar "N itens" (contavam sempre 0). No detalhe da comanda
     * (`getTab`), que traz os itens completos, a contagem sai deles.
     */
    val activeItemCount: Int = 0,
)

@Serializable
data class TabPhysicalCardRef(val id: Long)

// ---- Pulseira/Cartão físico (fluxo simplificado de Comandas) ----

/** `GET locations/:id/physical-code/:kind/:publicCode`. Discriminado por [kind]: TAB traz [tab] preenchido, CARD_AVAILABLE traz [card]. */
@Serializable
data class ResolvePhysicalCodeResponse(
    val kind: String, // TAB | CARD_AVAILABLE
    val tab: TabResponse? = null,
    val card: PhysicalCardResponse? = null,
)

@Serializable
data class PhysicalCardResponse(val id: Long, val publicCode: String)

@Serializable
data class BindPhysicalCardRequest(val customerName: String, val customerPhone: String)

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
    val status: String? = null,
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

/**
 * Payload do Outbox para [com.nokta.pos.data.local.entity.OutboxOperationType.CANCEL_ITEM]
 * — carrega o `itemServerId` junto (vai no path da chamada real, não no
 * corpo), diferente de [CancelOrderItemRequest], que só tem o que o backend
 * espera no corpo do POST.
 */
@Serializable
data class CancelItemOutboxPayload(val itemServerId: Long, val reason: String)

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
