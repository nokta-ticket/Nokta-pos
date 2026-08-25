package com.nokta.pos.comanda.domain

import com.nokta.pos.common.Money

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
 * Um item consumido, já registrado no servidor. Guarda os SNAPSHOTS de nome e
 * preço que o backend gravou no momento do lançamento — se o produto for
 * renomeado ou tiver o preço alterado depois, a comanda continua mostrando o
 * que foi realmente vendido (item 18: a auditoria nunca é reescrita).
 */
data class TabItem(
    val id: Long,
    val orderId: Long,
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
) {
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
    val id: Long,
    val method: PaymentMethod,
    val amount: Money,
    val received: Money?,
    val change: Money?,
    val isCanceled: Boolean,
    val externalReference: String?,
    val confirmedAt: String?,
)

/**
 * Snapshot de uma comanda tal como o servidor a vê. Totais SEMPRE vêm do
 * backend (subtotalCents/totalCents/remainingCents) — o app nunca recalcula
 * localmente para decidir se pode fechar ou quanto cobrar (seção 30 do PRD:
 * duas maquininhas podem editar a mesma comanda ao mesmo tempo, e o servidor
 * é a única fonte de verdade após cada mutação).
 */
data class Tab(
    val id: Long,
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
    val total: Money,
    val paid: Money,
    val remaining: Money,
    val openedAt: String? = null,
    val items: List<TabItem> = emptyList(),
    val payments: List<TabPayment> = emptyList(),
) {
    val isOpen get() = status == TabStatus.OPEN
    val isFullyPaid get() = remaining.isZeroOrNegative()
    val hasPartialPayment get() = paid.isPositive() && !isFullyPaid

    /** Itens que contam para o consumo — cancelado continua visível no histórico, mas não aqui. */
    val activeItems get() = items.filterNot { it.status.isCanceled }

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
