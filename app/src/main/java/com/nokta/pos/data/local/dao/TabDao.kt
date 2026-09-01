package com.nokta.pos.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.nokta.pos.data.local.entity.SyncState
import com.nokta.pos.data.local.entity.TabEntity
import com.nokta.pos.data.local.entity.TabItemEntity
import com.nokta.pos.data.local.entity.TabOrderEntity
import com.nokta.pos.data.local.entity.TabPaymentEntity
import com.nokta.pos.data.local.entity.TabWithItemsAndPayments
import com.nokta.pos.data.local.entity.VenueTableEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TabDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTab(tab: TabEntity)

    /**
     * Grava o snapshot completo de uma comanda vindo do servidor — tab +
     * pedidos + itens + pagamentos — como UMA ÚNICA transação. Sem isto, cada
     * chamada (upsertTab, deleteItemsForTab, upsertItems...) commitava
     * separadamente, e [observeTabWithDetails] (que reage a cada commit)
     * emitia um estado intermediário com a comanda já atualizada mas os
     * itens temporariamente vazios (entre o delete e o reinsert) — a UI via
     * a contagem de itens cair e subir de novo sem nenhum item ter mudado de
     * verdade, disparando falsos avisos de "item adicionado".
     */
    @Transaction
    suspend fun writeTabSnapshot(tab: TabEntity, orders: List<TabOrderEntity>, items: List<TabItemEntity>, payments: List<TabPaymentEntity>) {
        upsertTab(tab)
        deleteItemsForTab(tab.localId)
        deletePaymentsForTab(tab.localId)
        orders.forEach { upsertOrder(it) }
        upsertItems(items)
        upsertPayments(payments)
    }

    @Update
    suspend fun updateTab(tab: TabEntity)

    @Query("SELECT * FROM tab WHERE localId = :localId")
    suspend fun getTabByLocalId(localId: String): TabEntity?

    @Query("SELECT * FROM tab WHERE serverId = :serverId")
    suspend fun getTabByServerId(serverId: Long): TabEntity?

    /** Comandas ainda sem confirmação do servidor — volume sempre pequeno, usado para resolver o id local negativo de volta ao localId. */
    @Query("SELECT * FROM tab WHERE organizationId = :organizationId AND serverId IS NULL")
    suspend fun getUnsyncedTabsForOrganization(organizationId: Long): List<TabEntity>

    @Transaction
    @Query("SELECT * FROM tab WHERE localId = :localId")
    fun observeTabWithDetails(localId: String): Flow<TabWithItemsAndPayments?>

    @Transaction
    @Query("SELECT * FROM tab WHERE localId = :localId")
    suspend fun getTabWithDetails(localId: String): TabWithItemsAndPayments?

    /**
     * Comandas abertas conhecidas localmente, para a busca funcionar sem
     * rede — não é uma cópia de tudo que existe no servidor, só do que este
     * terminal já viu (via lista, abertura ou criação própria).
     */
    @Query(
        """
        SELECT * FROM tab
        WHERE organizationId = :organizationId AND locationId = :locationId AND status = 'OPEN'
        AND (:search IS NULL OR publicCode LIKE '%' || :search || '%' OR customerName LIKE '%' || :search || '%')
        ORDER BY createdAtEpochMs DESC
        """,
    )
    suspend fun searchOpenTabsLocal(organizationId: Long, locationId: Long, search: String?): List<TabEntity>

    @Query(
        """
        SELECT * FROM tab
        WHERE organizationId = :organizationId AND locationId = :locationId AND status = 'CLOSED'
        ORDER BY createdAtEpochMs DESC LIMIT :limit
        """,
    )
    suspend fun getRecentClosedTabsLocal(organizationId: Long, locationId: Long, limit: Int): List<TabEntity>

    /** Snapshot do histórico recente — simples upsert, sem remover nada (histórico só cresce). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertClosedTabsSnapshot(tabs: List<TabEntity>)

    @Query("SELECT COUNT(*) FROM tab WHERE organizationId = :organizationId AND locationId = :locationId AND status = 'OPEN'")
    fun observeOpenTabsCount(organizationId: Long, locationId: Long): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceOpenTabsSnapshot(tabs: List<TabEntity>)

    /**
     * Substitui a lista de comandas ABERTAS conhecidas por uma leitura fresca
     * do servidor — nunca toca em comandas PENDING (criadas offline, ainda
     * não confirmadas): sobrescrever uma comanda local pendente com "não
     * existe no servidor ainda" apagaria uma venda em andamento.
     */
    @Transaction
    suspend fun refreshOpenTabsSnapshot(organizationId: Long, locationId: Long, fresh: List<TabEntity>) {
        deleteSyncedOpenTabsNotIn(organizationId, locationId, fresh.mapNotNull { it.serverId })
        replaceOpenTabsSnapshot(fresh)
    }

    @Query(
        """
        DELETE FROM tab WHERE organizationId = :organizationId AND locationId = :locationId
        AND status = 'OPEN' AND syncState = 'SYNCED' AND (serverId IS NULL OR serverId NOT IN (:keepServerIds))
        """,
    )
    suspend fun deleteSyncedOpenTabsNotIn(organizationId: Long, locationId: Long, keepServerIds: List<Long>)

    // ---- Itens ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItem(item: TabItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<TabItemEntity>)

    @Update
    suspend fun updateItem(item: TabItemEntity)

    @Query("SELECT * FROM tab_item WHERE localId = :localId")
    suspend fun getItemByLocalId(localId: String): TabItemEntity?

    /** Usado pelo SyncEngine para localizar o registro local a partir do serverId gravado no payload do Outbox (CANCEL_ITEM). */
    @Query("SELECT * FROM tab_item WHERE serverId = :serverId")
    suspend fun getItemByServerId(serverId: Long): TabItemEntity?

    @Query("DELETE FROM tab_item WHERE tabLocalId = :tabLocalId")
    suspend fun deleteItemsForTab(tabLocalId: String)

    /** Remove só este item — nunca use [deleteItemsForTab] para isto, que apaga a comanda inteira. */
    @Query("DELETE FROM tab_item WHERE localId = :localId")
    suspend fun deleteItemByLocalId(localId: String)

    // ---- Pedidos ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrder(order: TabOrderEntity)

    @Query("SELECT * FROM tab_order WHERE localId = :localId")
    suspend fun getOrderByLocalId(localId: String): TabOrderEntity?

    /**
     * Desfaz o rascunho otimista de um pedido que o SERVIDOR RECUSOU por regra
     * de negócio (4xx) — ver TabRepository.submitOrder. Só para esse caso:
     * falha de rede nunca apaga nada, vai pro Outbox e sincroniza depois.
     */
    @Transaction
    suspend fun discardLocalOrder(orderLocalId: String) {
        deleteItemsForOrder(orderLocalId)
        deleteOrderByLocalId(orderLocalId)
    }

    @Query("DELETE FROM tab_item WHERE orderLocalId = :orderLocalId")
    suspend fun deleteItemsForOrder(orderLocalId: String)

    @Query("DELETE FROM tab_order WHERE localId = :localId")
    suspend fun deleteOrderByLocalId(localId: String)

    // ---- Pagamentos ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPayment(payment: TabPaymentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPayments(payments: List<TabPaymentEntity>)

    @Query("DELETE FROM tab_payment WHERE tabLocalId = :tabLocalId")
    suspend fun deletePaymentsForTab(tabLocalId: String)

    // ---- Mesas ----

    @Query("SELECT * FROM venue_table WHERE organizationId = :organizationId AND locationId = :locationId ORDER BY nome ASC")
    fun observeTables(organizationId: Long, locationId: Long): Flow<List<VenueTableEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTables(tables: List<VenueTableEntity>)

    @Transaction
    suspend fun replaceTablesSnapshot(organizationId: Long, locationId: Long, tables: List<VenueTableEntity>) {
        deleteTables(organizationId, locationId)
        upsertTables(tables)
    }

    @Query("DELETE FROM venue_table WHERE organizationId = :organizationId AND locationId = :locationId")
    suspend fun deleteTables(organizationId: Long, locationId: Long)

    @Query("SELECT MAX(fetchedAtEpochMs) FROM venue_table WHERE organizationId = :organizationId AND locationId = :locationId")
    suspend fun getTablesFetchedAt(organizationId: Long, locationId: Long): Long?

    /**
     * Marca uma comanda que nunca vai conseguir sincronizar (`CREATE_TAB`
     * recusado pelo servidor) como CANCELED — sai da contagem de "Abertas"
     * (que só considera `status = 'OPEN'') sem apagar o registro. Reaproveita
     * `TabStatus.CANCELED` (não um status novo tipo "FAILED"): a comanda de
     * fato nunca existiu no servidor, e "cancelada" já é o status que a UI
     * (filtros, `when` exaustivos) sabe tratar como "não vai a lugar nenhum".
     * `syncState = FAILED` (nunca antes gravado) diferencia isso de um
     * cancelamento normal para quem inspecionar o registro local depois.
     */
    @Query("UPDATE tab SET status = 'CANCELED', syncState = 'FAILED' WHERE localId = :localId")
    suspend fun markTabFailed(localId: String)
}
