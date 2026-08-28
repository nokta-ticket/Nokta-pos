package com.nokta.pos.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import kotlinx.serialization.Serializable

/**
 * Forma serializável de [com.nokta.pos.comanda.domain.TabItemModifier] só
 * para persistir em [TabItemEntity.modifiersJson] — o domínio usa `Money`
 * (value class não serializável por padrão) e não deveria carregar
 * anotação de serialização só por causa do Room.
 */
@Serializable
data class PersistedModifier(val name: String, val quantity: Int, val totalCents: Long)

/**
 * Como o servidor está por trás da linha de sincronismo em vez de ser a
 * fonte imediata: toda entidade abaixo tem uma PK LOCAL (`localId`, um
 * UUID gerado no dispositivo) e um `serverId` opcional. Antes desta
 * arquitetura, `Tab.id` era o `Long` do servidor direto — o que tornava
 * IMPOSSÍVEL representar "uma comanda que o operador já está vendo e
 * mexendo, mas que o servidor ainda não confirmou" (o caso central de uma
 * venda de balcão aberta sem rede).
 *
 * Toda referência interna do app (carrinho → comanda → pedido → item →
 * pagamento) aponta pelo `localId`. O `serverId` só é lido na hora de montar
 * a URL de uma chamada HTTP, e só existe depois que o Outbox confirmou a
 * criação no backend.
 */
enum class SyncState { SYNCED, PENDING, FAILED }

@Entity(tableName = "tab", indices = [Index("serverId", unique = true)])
data class TabEntity(
    @PrimaryKey val localId: String,
    val serverId: Long?,
    val organizationId: Long,
    val locationId: Long,
    /** Nulo até o servidor gerar o código curto — só existe depois do CREATE confirmado. */
    val publicCode: String?,
    val type: String, // TabType.name
    val status: String, // TabStatus.name
    val customerName: String?,
    val customerPhone: String?,
    val tableServerId: Long?,
    val tableName: String?,
    val guestCount: Int?,
    val subtotalCents: Long,
    val discountCents: Long,
    val serviceChargeCents: Long,
    val serviceChargeRateBps: Int = 0,
    val totalCents: Long,
    val paidCents: Long,
    val remainingCents: Long,
    val openedAt: String?,
    val syncState: SyncState,
    /** Quando esta linha foi vista pela última vez confirmada pelo servidor. */
    val lastSyncedAtEpochMs: Long?,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "tab_item",
    foreignKeys = [
        ForeignKey(
            entity = TabEntity::class,
            parentColumns = ["localId"],
            childColumns = ["tabLocalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("tabLocalId"), Index("serverId", unique = true)],
)
data class TabItemEntity(
    @PrimaryKey val localId: String,
    val serverId: Long?,
    val tabLocalId: String,
    /** Pedido (VenueOrder) local ao qual este item pertence — agrupa o envio pro preparo. */
    val orderLocalId: String,
    val menuItemId: Long,
    val variantId: Long,
    val productName: String,
    val variantName: String,
    val quantity: Int,
    val unitPriceCents: Long,
    val modifiersTotalCents: Long,
    val lineTotalCents: Long,
    val status: String, // OrderItemStatus.name
    val notes: String?,
    /** JSON de List<TabItemModifier> — poucos itens por linha, não justifica tabela própria. */
    val modifiersJson: String,
    val createdAtEpochMs: Long,
)

/**
 * Um pedido (lote de itens enviado numa tacada) — existe principalmente para
 * carregar o `clientRequestId` que o backend usa como chave de idempotência
 * (`@@unique([tabId, clientRequestId])`). `localId` também SERVE como esse
 * `clientRequestId`: nunca gerar um valor diferente para a mesma tentativa.
 */
@Entity(
    tableName = "tab_order",
    foreignKeys = [
        ForeignKey(
            entity = TabEntity::class,
            parentColumns = ["localId"],
            childColumns = ["tabLocalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("tabLocalId"), Index("serverId", unique = true)],
)
data class TabOrderEntity(
    @PrimaryKey val localId: String,
    val serverId: Long?,
    val tabLocalId: String,
    val status: String, // DRAFT | SENT
    val syncState: SyncState,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "tab_payment",
    foreignKeys = [
        ForeignKey(
            entity = TabEntity::class,
            parentColumns = ["localId"],
            childColumns = ["tabLocalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("tabLocalId"), Index("serverId", unique = true)],
)
data class TabPaymentEntity(
    @PrimaryKey val localId: String,
    val serverId: Long?,
    val tabLocalId: String,
    val method: String, // PaymentMethod.name
    val amountCents: Long,
    val receivedCents: Long?,
    val changeCents: Long?,
    val isCanceled: Boolean,
    val externalReference: String?,
    val confirmedAt: String?,
    val syncState: SyncState,
    val createdAtEpochMs: Long,
)

/**
 * Última foto conhecida de cada mesa da unidade — cache read-through, nunca
 * fonte de verdade para decisão financeira. A UI que a lê sempre sabe (via
 * `fetchedAtEpochMs`) se está olhando dado fresco ou de X minutos atrás.
 */
@Entity(tableName = "venue_table")
data class VenueTableEntity(
    @PrimaryKey val serverId: Long,
    val organizationId: Long,
    val locationId: Long,
    val nome: String,
    val capacidade: Int?,
    val active: Boolean,
    val openTabServerId: Long?,
    val openTabCode: String?,
    /** OPEN, CLOSING ou PAYMENT_IN_PROGRESS — nunca CLOSED/CANCELED (não apareceria como openTab). Null só para linhas gravadas antes deste campo existir. */
    val openTabStatus: String?,
    val openTabTotalCents: Long?,
    val openTabRemainingCents: Long?,
    val openTabCustomerName: String?,
    val fetchedAtEpochMs: Long,
)

data class TabWithItemsAndPayments(
    @Embedded val tab: TabEntity,
    @Relation(parentColumn = "localId", entityColumn = "tabLocalId")
    val items: List<TabItemEntity>,
    @Relation(parentColumn = "localId", entityColumn = "tabLocalId")
    val payments: List<TabPaymentEntity>,
)
