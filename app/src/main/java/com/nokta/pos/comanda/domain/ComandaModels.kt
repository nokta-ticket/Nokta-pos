package com.nokta.pos.comanda.domain

import com.nokta.pos.common.Money
import java.util.UUID

/**
 * Deriva um `Long` sempre negativo e estável a partir do `localId` (UUID) de
 * uma comanda ainda não confirmada pelo servidor — usado como [Tab.id]
 * enquanto [Tab.serverId] é nulo, para que rotas Compose/`SavedStateHandle`
 * continuem trafegando `Long` sem qualquer mudança de tipo. Nenhum id de
 * servidor é negativo, então não há ambiguidade possível com um `serverId`
 * real; colisão de hash é astronomicamente improvável para o volume de
 * comandas abertas por um único terminal entre reinicializações.
 */
fun negativeIdFromLocalId(localId: String): Long {
    val uuid = runCatching { UUID.fromString(localId) }.getOrElse { UUID.nameUUIDFromBytes(localId.toByteArray()) }
    val magnitude = uuid.mostSignificantBits xor uuid.leastSignificantBits
    return -(magnitude and Long.MAX_VALUE) - 1L
}

enum class TabType { TABLE, INDIVIDUAL, COUNTER }
enum class TabStatus { OPEN, CLOSED, CANCELED }
enum class OrderStatus { DRAFT, SENT, IN_PREPARATION, READY, DELIVERED, CANCELED }

/**
 * Status de um item já lançado, do ponto de vista do garçom. O POS NÃO é o
 * sistema de produção (isso é do dashboard/cozinha) — mostra o estado só para
 * o operador saber o que responder quando o cliente pergunta "e o meu
 * hambúrguer?". Nunca bloqueia cobrança por causa disto.
 */
enum class OrderItemStatus {
    DRAFT, SENT, IN_PREPARATION, READY, DELIVERED, CANCELED;

    val isCanceled get() = this == CANCELED
    val isDelivered get() = this == DELIVERED

    /** Rótulo curto para caber ao lado do item na lista. */
    val label: String
        get() = when (this) {
            DRAFT -> "Rascunho"
            SENT -> "Enviado"
            IN_PREPARATION -> "Em preparo"
            READY -> "Pronto"
            DELIVERED -> "Entregue"
            CANCELED -> "Cancelado"
        }

    companion object {
        fun parse(raw: String): OrderItemStatus =
            entries.firstOrNull { it.name == raw } ?: SENT
    }
}

enum class PaymentMethod {
    CASH, PIX, DEBIT_CARD, CREDIT_CARD, VOUCHER, OTHER;

    val label: String
        get() = when (this) {
            CASH -> "Dinheiro"
            PIX -> "Pix"
            DEBIT_CARD -> "Débito"
            CREDIT_CARD -> "Crédito"
            VOUCHER -> "Voucher"
            OTHER -> "Outro"
        }

    companion object {
        fun parse(raw: String): PaymentMethod = entries.firstOrNull { it.name == raw } ?: OTHER
    }
}

data class TabItemModifier(val name: String, val quantity: Int, val total: Money)

/**
 * Estado de sincronização de uma entidade offline-first, do ponto de vista da
 * UI — não confundir com [OrderItemStatus]/[TabStatus] (estado OPERACIONAL,
 * sempre vindo do servidor). Isto é sobre "o terminal já confirmou isto com a
 * Nokta?", nunca sobre o andamento do pedido em si.
 */
enum class LocalSyncState { SYNCED, PENDING, FAILED }

/**
 * Um item consumido. Guarda os SNAPSHOTS de nome e preço que o backend gravou
 * no momento do lançamento — se o produto for renomeado ou tiver o preço
 * alterado depois, a comanda continua mostrando o que foi realmente vendido
 * (item 18: a auditoria nunca é reescrita).
 *
 * `id` é sempre o identificador ESTÁVEL para a UI usar (key de lista, alvo de
 * clique): é o `serverId` quando já confirmado, ou o hash do `localId`
 * (UUID) enquanto pendente — nunca muda de valor no meio da tela. `localId`
 * é a chave real usada para persistir/atualizar no Room.
 */
data class TabItem(
    val localId: String,
    val serverId: Long?,
    val orderId: Long?,
    val productName: String,
    val variantName: String,
    val quantity: Int,
    val unitPrice: Money,
    val modifiersTotal: Money,
    val lineTotal: Money,
    val status: OrderItemStatus,
    val notes: String?,
    val modifiers: List<TabItemModifier> = emptyList(),
    val createdAt: String? = null,
    val syncState: LocalSyncState = LocalSyncState.SYNCED,
) {
    val id: String get() = localId
    /** Descrição completa da linha, incluindo adicionais e observação. */
    val detailLine: String?
        get() {
            val parts = buildList {
                if (modifiers.isNotEmpty()) add(modifiers.joinToString(", ") { m ->
                    if (m.quantity > 1) "${m.quantity}x ${m.name}" else m.name
                })
                notes?.takeIf { it.isNotBlank() }?.let { add("Obs.: $it") }
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }
}

data class TabPayment(
    val localId: String,
    val serverId: Long?,
    val method: PaymentMethod,
    val amount: Money,
    val received: Money?,
    val change: Money?,
    val isCanceled: Boolean,
    val externalReference: String?,
    val confirmedAt: String?,
    val syncState: LocalSyncState = LocalSyncState.SYNCED,
) {
    val id: String get() = localId
}

/**
 * Snapshot de uma comanda. Totais SEMPRE vêm do backend quando sincronizados
 * (subtotalCents/totalCents/remainingCents) — o app nunca recalcula
 * localmente para decidir se pode fechar ou quanto cobrar (seção 30 do PRD:
 * duas maquininhas podem editar a mesma comanda ao mesmo tempo, e o servidor
 * é a única fonte de verdade após cada mutação SINCRONIZADA). Enquanto
 * [syncState] é PENDING, os totais são a melhor estimativa local — calculada
 * a partir dos itens/pagamentos já registrados neste terminal.
 *
 * `id` continua `Long` — não é o `Tab.id` de servidor puro: é `serverId` já
 * confirmado, ou um **id local negativo** enquanto a comanda foi aberta
 * offline e ainda não tem confirmação (nenhum id de servidor é negativo, o
 * que torna a distinção inequívoca em toda a UI/navegação sem precisar trocar
 * rotas Compose de `Long` para `String`). [localId] (UUID) é a chave real de
 * persistência/sincronização; a UI nunca precisa conhecê-lo diretamente.
 */
data class Tab(
    val localId: String,
    val serverId: Long?,
    val negativeLocalId: Long,
    val organizationId: Long,
    val locationId: Long,
    val publicCode: String,
    val type: TabType,
    val status: TabStatus,
    val customerName: String?,
    val customerPhone: String? = null,
    val tableId: Long? = null,
    val tableName: String? = null,
    val guestCount: Int?,
    val subtotal: Money,
    val discount: Money,
    val serviceCharge: Money,
    /** Percentual configurado no dashboard (base 10000 = 100%) — só para exibição, o valor cobrado é sempre [serviceCharge]. */
    val serviceChargeRateBps: Int = 0,
    val total: Money,
    val paid: Money,
    val remaining: Money,
    val openedAt: String? = null,
    val items: List<TabItem> = emptyList(),
    val payments: List<TabPayment> = emptyList(),
    val syncState: LocalSyncState = LocalSyncState.SYNCED,
) {
    val id: Long get() = serverId ?: negativeLocalId

    val isOpen get() = status == TabStatus.OPEN
    val isFullyPaid get() = remaining.isZeroOrNegative()
    val hasPartialPayment get() = paid.isPositive() && !isFullyPaid

    /** Itens que contam para o consumo — cancelado continua visível no histórico, mas não aqui. */
    val activeItems get() = items.filterNot { it.status.isCanceled }

    /** "12%" a partir dos basis points — só exibição, nunca usado para recalcular o valor cobrado. */
    val serviceChargeRateLabel: String? get() = serviceChargeRateBps.takeIf { it > 0 }?.let { "${it / 100}%" }

    /** Quantos itens ainda não foram entregues — informativo, nunca trava cobrança. */
    val pendingItemCount get() = activeItems.count { !it.status.isDelivered }

    /** Identificação curta para cabeçalho: "Mesa 12" ou "Comanda 0007". */
    val displayName: String
        get() = when {
            tableName != null -> "Mesa $tableName"
            type == TabType.TABLE -> "Mesa"
            type == TabType.COUNTER -> "Balcão $publicCode"
            else -> "Comanda $publicCode"
        }
}

data class OrderLineModifier(val modifierGroupId: Long, val modifierOptionId: Long, val quantity: Int = 1)

data class OrderLine(
    val menuItemId: Long,
    val variantId: Long,
    val quantity: Int,
    val notes: String? = null,
    val modifiers: List<OrderLineModifier> = emptyList(),
)

/** Mesa do salão, com a comanda aberta embutida quando ocupada. */
data class VenueTable(
    val id: Long,
    val name: String,
    val capacity: Int?,
    val active: Boolean,
    val openTabId: Long?,
    val openTabCode: String?,
    val openTabTotal: Money?,
    val openTabRemaining: Money?,
    val openTabCustomerName: String?,
) {
    val isOccupied get() = openTabId != null
}
