package com.nokta.pos.comanda.data

import com.nokta.pos.comanda.domain.OrderItemStatus
import com.nokta.pos.comanda.domain.OrderLine
import com.nokta.pos.comanda.domain.PaymentMethod
import com.nokta.pos.comanda.domain.Tab
import com.nokta.pos.comanda.domain.TabItem
import com.nokta.pos.comanda.domain.TabItemModifier
import com.nokta.pos.comanda.domain.TabPayment
import com.nokta.pos.comanda.domain.TabStatus
import com.nokta.pos.comanda.domain.TabType
import com.nokta.pos.comanda.domain.VenueTable
import com.nokta.pos.common.Money
import com.nokta.pos.network.NoktaApi
import com.nokta.pos.network.dto.CancelOrderItemRequest
import com.nokta.pos.network.dto.CreateOrderItemRequest
import com.nokta.pos.network.dto.CreateOrderRequest
import com.nokta.pos.network.dto.CreatePaymentRequest
import com.nokta.pos.network.dto.CreateTabRequest
import com.nokta.pos.network.dto.OrderItemModifierRequest
import com.nokta.pos.network.dto.TabResponse
import com.nokta.pos.network.dto.TableResponse
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OperationRepository @Inject constructor(
    private val api: NoktaApi,
) {

    suspend fun openTab(
        organizationId: Long,
        locationId: Long,
        type: TabType,
        tableId: Long? = null,
        customerName: String? = null,
        customerPhone: String? = null,
        guestCount: Int? = null,
    ): Tab = api.createTab(
        organizationId,
        locationId,
        CreateTabRequest(
            type = type.name,
            tableId = tableId,
            customerName = customerName,
            customerPhone = customerPhone,
            guestCount = guestCount,
        ),
    ).toDomain()

    suspend fun getTab(organizationId: Long, tabId: Long): Tab =
        api.getTab(organizationId, tabId).toDomain()

    /** Ler comanda por código curto — "comanda 123" digitado pelo operador. */
    suspend fun getTabByPublicCode(organizationId: Long, locationId: Long, publicCode: String): Tab =
        api.getTabByPublicCode(organizationId, locationId, publicCode.trim()).toDomain()

    /**
     * Busca comandas abertas. `search` casa código OU nome do cliente no
     * backend, então serve tanto para "123" quanto para "João" — o operador
     * não precisa saber qual dos dois está digitando.
     */
    suspend fun searchOpenTabs(
        organizationId: Long,
        locationId: Long,
        search: String? = null,
        type: TabType? = null,
    ): List<Tab> = api.listTabs(
        organizationId = organizationId,
        locationId = locationId,
        status = TabStatus.OPEN.name,
        type = type?.name,
        search = search?.trim()?.takeIf { it.isNotEmpty() },
    ).map { it.toDomain() }

    /** Mesas da unidade, já com a comanda aberta de cada uma (1 chamada). */
    suspend fun listTables(organizationId: Long, locationId: Long): List<VenueTable> =
        api.listTables(organizationId, locationId).map { it.toDomain() }

    suspend fun closeTab(organizationId: Long, tabId: Long): Tab =
        api.closeTab(organizationId, tabId).toDomain()

    /**
     * clientRequestId gerado uma vez por tentativa de envio de pedido — se o
     * app perder a resposta por falha de rede e o operador tocar "enviar" de
     * novo, o backend devolve o MESMO pedido em vez de duplicar (seção 13/48
     * do PRD: rede instável, toque duplo). O caller (ViewModel) é responsável
     * por gerar esse id uma única vez por tentativa e reenviar o mesmo valor
     * em retry — nunca gerar um novo a cada chamada desta função.
     */
    suspend fun submitOrder(
        organizationId: Long,
        tabId: Long,
        lines: List<OrderLine>,
        clientRequestId: String,
        notes: String? = null,
    ) {
        val order = api.createOrder(
            organizationId,
            tabId,
            CreateOrderRequest(
                items = lines.map {
                    CreateOrderItemRequest(
                        menuItemId = it.menuItemId,
                        variantId = it.variantId,
                        quantity = it.quantity,
                        notes = it.notes,
                        modifiers = it.modifiers.map { m ->
                            OrderItemModifierRequest(m.modifierGroupId, m.modifierOptionId, m.quantity)
                        },
                    )
                },
                clientRequestId = clientRequestId,
                notes = notes,
            ),
        )
        api.sendOrder(organizationId, order.id)
    }

    /**
     * Cancela um item lançado por engano. O backend nunca apaga: marca
     * CANCELED com autor e motivo, e recalcula o total — o item continua
     * visível no histórico da comanda.
     */
    suspend fun cancelItem(organizationId: Long, itemId: Long, reason: String) {
        api.cancelOrderItem(organizationId, itemId, CancelOrderItemRequest(reason))
    }

    /**
     * Registra um pagamento. `amount` pode ser MENOR que o saldo (pagamento
     * parcial/divisão) — o backend só rejeita se ultrapassar o restante, e
     * recalcula `remainingCents` a cada registro. Nunca somamos/subtraímos
     * localmente para decidir o próximo valor.
     */
    suspend fun registerPayment(
        organizationId: Long,
        tabId: Long,
        method: String,
        amount: Money,
        idempotencyKey: String = UUID.randomUUID().toString(),
        receivedCents: Long? = null,
        externalReference: String? = null,
        notes: String? = null,
    ): Tab {
        api.createPayment(
            organizationId,
            tabId,
            CreatePaymentRequest(
                method = method,
                amountCents = amount.cents,
                receivedCents = receivedCents,
                idempotencyKey = idempotencyKey,
                externalReference = externalReference,
                notes = notes,
            ),
        )
        // O pagamento não devolve o Tab atualizado — relê para ter
        // remainingCents/total fresco (mesma fonte de verdade do servidor,
        // nunca subtraído localmente).
        return getTab(organizationId, tabId)
    }
}

private fun TabResponse.toDomain(): Tab = Tab(
    id = id,
    organizationId = organizationId,
    locationId = locationId,
    publicCode = publicCode,
    type = TabType.entries.firstOrNull { it.name == type } ?: TabType.INDIVIDUAL,
    status = TabStatus.entries.firstOrNull { it.name == status } ?: TabStatus.OPEN,
    customerName = customerName,
    customerPhone = customerPhone,
    tableId = tableId,
    tableName = table?.nome,
    guestCount = guestCount,
    subtotal = Money(subtotalCents),
    discount = Money(discountCents),
    serviceCharge = Money(serviceChargeCents),
    total = Money(totalCents),
    paid = Money(paidCents),
    remaining = Money(remainingCents),
    openedAt = openedAt,
    // Achata orders[].items[] numa lista única: para o garçom o que importa é
    // "o que esta pessoa consumiu", não em qual pedido cada item entrou. A
    // ordem cronológica vem do backend (orderBy createdAt asc nos dois níveis).
    items = orders.flatMap { order ->
        order.items.map { item ->
            TabItem(
                id = item.id,
                orderId = order.id,
                productName = item.productNameSnapshot,
                variantName = item.variantNameSnapshot,
                quantity = item.quantity,
                unitPrice = Money(item.unitPriceCents),
                modifiersTotal = Money(item.modifiersTotalCents),
                lineTotal = Money(item.lineTotalCents),
                status = OrderItemStatus.parse(item.status),
                notes = item.notes,
                modifiers = item.modifiers.map { m ->
                    TabItemModifier(
                        name = m.optionNameSnapshot ?: "Adicional",
                        quantity = m.quantity,
                        total = Money(m.totalPriceCents),
                    )
                },
                createdAt = item.createdAt ?: order.createdAt,
            )
        }
    },
    payments = payments.map { p ->
        TabPayment(
            id = p.id,
            method = PaymentMethod.parse(p.method),
            amount = Money(p.amountCents),
            received = p.receivedCents?.let { Money(it) },
            change = p.changeCents?.let { Money(it) },
            isCanceled = p.status == "CANCELED",
            externalReference = p.externalReference,
            confirmedAt = p.confirmedAt ?: p.createdAt,
        )
    },
)

private fun TableResponse.toDomain(): VenueTable = VenueTable(
    id = id,
    name = nome,
    capacity = capacidade,
    active = active,
    openTabId = openTab?.id,
    openTabCode = openTab?.publicCode,
    openTabTotal = openTab?.let { Money(it.totalCents) },
    openTabRemaining = openTab?.let { Money(it.remainingCents) },
    openTabCustomerName = openTab?.customerName,
)
