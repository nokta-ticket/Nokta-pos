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

enum class TabType { TABLE, INDIVIDUAL, COUNTER, WRISTBAND }

/**
 * OPEN -> CLOSING -> PAYMENT_IN_PROGRESS -> CLOSED é o caminho de "fechar a
 * conta antes de cobrar" (opcional — pagar direto de OPEN sem passar por
 * CLOSING continua funcionando e fecha sozinho quando o saldo zera, fluxo
 * rápido de balcão/bar inalterado). CLOSING/PAYMENT_IN_PROGRESS bloqueiam
 * edição de consumo no backend (ver Tab.isEditable) mas a mesa continua
 * fisicamente ocupada nesses estados (ver Tab.isOccupying).
 */
enum class TabStatus { OPEN, CLOSING, PAYMENT_IN_PROGRESS, CLOSED, CANCELED;

    companion object {
        /** Fallback para OPEN quando o backend devolve um status que este app ainda não conhece — nunca derruba a tela por um enum desconhecido. */
        fun parse(raw: String): TabStatus = entries.firstOrNull { it.name == raw } ?: OPEN
    }
}
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
    /** Catálogo de origem — necessário para relançar a mesma linha (ajuste de quantidade). */
    val menuItemId: Long,
    val variantId: Long,
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

    /**
     * Ainda não confirmado pelo servidor — remover é edição local (desfazer um
     * lançamento por engano antes dele existir de verdade), nunca um
     * "cancelamento" auditado. Item já confirmado (`serverId != null`) segue
     * sempre pelo fluxo de cancelamento com motivo obrigatório.
     */
    val canRemoveAsDraft: Boolean get() = serverId == null

    /**
     * Rótulo do badge da linha. Enquanto o lançamento não foi aceito pelo
     * servidor, o estado que importa para o operador é o de SINCRONIZAÇÃO, não
     * o operacional: dizer "Enviado" a um item que ainda está na fila offline é
     * simplesmente falso, e foi o que a comanda mostrava antes. Assim que o
     * backend confirma (`serverId` preenchido), o rótulo volta a ser o estado
     * real do pedido — "Enviado", "Em preparo", "Entregue".
     *
     * Cancelado tem precedência: um item cancelado offline já é um fato local
     * decidido pelo operador, e mostrá-lo como "Pendente" esconderia isso.
     */
    val displayStatusLabel: String
        get() = when {
            status.isCanceled -> status.label
            syncState == LocalSyncState.PENDING -> "Pendente"
            syncState == LocalSyncState.FAILED -> "Falha ao enviar"
            else -> status.label
        }

    /** Aguardando confirmação do servidor — ver [displayStatusLabel]. */
    val isAwaitingSync: Boolean get() = !status.isCanceled && syncState != LocalSyncState.SYNCED

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
    /** Comanda aberta pelo fluxo "Cartão físico" — ver TabEntity.isPhysicalCard. */
    val isPhysicalCard: Boolean = false,
    /** Contagem de itens vinda do endpoint de lista — ver [activeItemCount]. */
    val activeItemCountFromServer: Int = 0,
) {
    val id: Long get() = serverId ?: negativeLocalId

    val isOpen get() = status == TabStatus.OPEN

    /** Pode lançar/editar/cancelar item, aplicar desconto/taxa — só em OPEN (CLOSING/PAYMENT_IN_PROGRESS já congelaram o consumo para cobrança). */
    val isEditable get() = status == TabStatus.OPEN

    /** A mesa ainda está fisicamente ocupada (cliente não foi embora) — usar para decidir o que aparece como "em atendimento", nunca [isOpen] sozinho (que exclui CLOSING/PAYMENT_IN_PROGRESS). */
    val isOccupying get() = status == TabStatus.OPEN || status == TabStatus.CLOSING || status == TabStatus.PAYMENT_IN_PROGRESS

    /**
     * Consumo ainda não contabilizado pelo servidor: itens lançados offline que
     * seguem na fila. Os totais oficiais ([subtotal]/[total]/[remaining]) são
     * SEMPRE os do backend e nunca são recalculados aqui — mas eles ainda
     * desconhecem estes itens, então somá-los é o que permite ao garçom saber
     * quanto cobrar sem esperar a rede voltar. Some zero assim que sincroniza
     * (o item ganha `serverId` e passa a estar dentro do total oficial), então
     * nunca há contagem dupla.
     */
    val pendingConsumption: Money
        get() = Money.sum(activeItems.filter { it.isAwaitingSync }.map { it.lineTotal })

    /** Total incluindo o consumo pendente — o que o cliente deve de fato agora. */
    val totalWithPending: Money get() = total + pendingConsumption

    /** Saldo a cobrar incluindo o consumo pendente. */
    val remainingWithPending: Money get() = remaining + pendingConsumption

    val hasPendingConsumption: Boolean get() = pendingConsumption.isPositive()

    val isFullyPaid get() = remaining.isZeroOrNegative()
    val hasPartialPayment get() = paid.isPositive() && !isFullyPaid

    /** Itens que contam para o consumo — cancelado continua visível no histórico, mas não aqui. */
    val activeItems get() = items.filterNot { it.status.isCanceled }

    /**
     * Quantidade de itens para exibir em LISTAS ("N itens").
     *
     * As listas vêm de um endpoint que não carrega os itens (por eficiência),
     * então [activeItems] fica vazia ali e a contagem tem de vir do servidor;
     * na tela de detalhe, que tem os itens de verdade, eles é que mandam —
     * inclusive porque refletem cancelamentos feitos offline, que o servidor
     * ainda não conhece.
     */
    val activeItemCount: Int
        get() = if (items.isEmpty()) activeItemCountFromServer else activeItems.sumOf { it.quantity }

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
            type == TabType.WRISTBAND -> "Pulseira $publicCode"
            else -> "Comanda $publicCode"
        }
}

data class OrderLineModifier(
    val modifierGroupId: Long,
    val modifierOptionId: Long,
    val quantity: Int = 1,
    /** Só para exibir o rascunho local antes da confirmação — ver [OrderLine]. */
    val name: String = "",
    val priceCents: Long = 0,
)

/**
 * Uma linha a lançar. Os campos que o backend precisa são apenas os ids
 * ([menuItemId]/[variantId]), a quantidade e os adicionais — é isso que vai no
 * request e é sobre isso que o servidor decide preço, disponibilidade e caixa.
 *
 * [productName]/[variantName]/[unitPriceCents] existem por um motivo
 * diferente e estritamente local: enquanto o lançamento está na fila offline,
 * a comanda precisa mostrar "Budweiser 269ml · 1 × R$ 8,00" em vez de uma
 * linha sem nome de R$ 0,00. São um SNAPSHOT do cardápio que o terminal já
 * tinha em mãos no momento do toque, nunca uma segunda fonte de verdade: na
 * sincronização o backend revalida tudo e sua resposta sobrescreve estes
 * valores (ver `writeTabFromServer`). Um preço adulterado aqui mudaria só o
 * que este terminal exibe por alguns segundos, jamais o que é cobrado.
 */
data class OrderLine(
    val menuItemId: Long,
    val variantId: Long,
    val quantity: Int,
    val notes: String? = null,
    val modifiers: List<OrderLineModifier> = emptyList(),
    val productName: String = "",
    val variantName: String = "",
    val unitPriceCents: Long = 0,
)

/** Mesa do salão, com a comanda aberta embutida quando ocupada. */
data class VenueTable(
    val id: Long,
    val name: String,
    val capacity: Int?,
    val active: Boolean,
    val openTabId: Long?,
    val openTabCode: String?,
    /** OPEN, CLOSING ou PAYMENT_IN_PROGRESS — nunca null quando openTabId != null (backend só inclui openTab nesses status). */
    val openTabStatus: TabStatus?,
    val openTabTotal: Money?,
    val openTabRemaining: Money?,
    val openTabCustomerName: String?,
) {
    val isOccupied get() = openTabId != null
}
