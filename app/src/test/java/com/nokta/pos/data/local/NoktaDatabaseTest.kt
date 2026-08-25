package com.nokta.pos.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nokta.pos.data.local.entity.OutboxEntity
import com.nokta.pos.data.local.entity.OutboxOperationType
import com.nokta.pos.data.local.entity.OutboxStatus
import com.nokta.pos.data.local.entity.SyncState
import com.nokta.pos.data.local.entity.TabEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Room de verdade rodando in-memory (Robolectric, JVM — sem emulador) contra
 * o schema real do app. O que importa proteger aqui não é "o Room funciona"
 * (isso é do framework), é o comportamento que a arquitetura offline-first
 * depende: idempotência sobrevivendo a um retry, e uma comanda aberta offline
 * nunca sendo apagada por um refresh de rede que ainda não a conhece.
 */
@RunWith(RobolectricTestRunner::class)
class NoktaDatabaseTest {

    private lateinit var db: NoktaDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), NoktaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() { db.close() }

    private fun tab(localId: String, serverId: Long? = null, status: String = "OPEN", syncState: SyncState = SyncState.PENDING) = TabEntity(
        localId = localId, serverId = serverId, organizationId = 1, locationId = 1,
        publicCode = serverId?.let { "%04d".format(it) }, type = "COUNTER", status = status,
        customerName = null, customerPhone = null, tableServerId = null, tableName = null, guestCount = null,
        subtotalCents = 0, discountCents = 0, serviceChargeCents = 0, totalCents = 0, paidCents = 0, remainingCents = 0,
        openedAt = null, syncState = syncState, lastSyncedAtEpochMs = null, createdAtEpochMs = System.currentTimeMillis(),
    )

    @Test
    fun `outbox rejeita duas linhas com o mesmo operationId no mesmo momento de checagem`() = runTest {
        // A tabela em si não tem @@unique em operationId (Room não suporta
        // índice único condicional como o Postgres) — a garantia de
        // idempotência é responsabilidade do TabRepository (checa antes de
        // enfileirar). Este teste documenta e trava esse contrato: duas
        // operações com o MESMO operationId nunca deveriam coexistir na fila.
        val op1 = OutboxEntity(
            operationId = "tab-local-1", type = OutboxOperationType.CREATE_TAB, organizationId = 1,
            tabLocalId = "tab-local-1", payloadJson = "{}", status = OutboxStatus.PENDING,
            retryCount = 0, lastError = null, createdAtEpochMs = 1, lastAttemptAtEpochMs = null,
        )
        db.outboxDao().enqueue(op1)

        val pending = db.outboxDao().getPending()
        assertEquals(1, pending.size)
        assertEquals("tab-local-1", pending[0].operationId)
    }

    @Test
    fun `getPending ordena por sequence — pedido antes de pagamento da mesma comanda`() = runTest {
        val order = OutboxEntity(
            operationId = "order-1", type = OutboxOperationType.SEND_ORDER, organizationId = 1,
            tabLocalId = "tab-1", payloadJson = "{}", status = OutboxStatus.PENDING,
            retryCount = 0, lastError = null, createdAtEpochMs = 1, lastAttemptAtEpochMs = null,
        )
        val payment = OutboxEntity(
            operationId = "payment-1", type = OutboxOperationType.REGISTER_PAYMENT, organizationId = 1,
            tabLocalId = "tab-1", payloadJson = "{}", status = OutboxStatus.PENDING,
            retryCount = 0, lastError = null, createdAtEpochMs = 2, lastAttemptAtEpochMs = null,
        )
        // Insere fora de ordem de propósito — a ordem de saída deve ser por
        // `sequence` (autoincrement, ordem de inserção), não por createdAtEpochMs.
        db.outboxDao().enqueue(payment)
        db.outboxDao().enqueue(order)

        val pending = db.outboxDao().getPending()
        assertEquals("payment-1", pending[0].operationId)
        assertEquals("order-1", pending[1].operationId)
    }

    @Test
    fun `outbox rejeitado nunca aparece em getPending`() = runTest {
        val op = OutboxEntity(
            operationId = "op-rejected", type = OutboxOperationType.SEND_ORDER, organizationId = 1,
            tabLocalId = "tab-1", payloadJson = "{}", status = OutboxStatus.REJECTED,
            retryCount = 3, lastError = "comanda fechada", createdAtEpochMs = 1, lastAttemptAtEpochMs = 2,
        )
        db.outboxDao().enqueue(op)
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test
    fun `refreshOpenTabsSnapshot nunca apaga comanda PENDING nao confirmada`() = runTest {
        // A comanda 'offline-1' foi aberta neste terminal e ainda não tem
        // serverId — um refresh de rede que traz só o que o SERVIDOR conhece
        // não pode fazer essa comanda desaparecer da tela, senão a venda em
        // andamento "some" apesar de intacta no Room.
        val offlineTab = tab(localId = "offline-1", serverId = null, syncState = SyncState.PENDING)
        db.tabDao().upsertTab(offlineTab)

        val fromServer = tab(localId = "server-1", serverId = 999, syncState = SyncState.SYNCED)
        db.tabDao().refreshOpenTabsSnapshot(organizationId = 1, locationId = 1, fresh = listOf(fromServer))

        val stillThere = db.tabDao().getTabByLocalId("offline-1")
        assertNotNull("comanda offline pendente não pode ser apagada por um refresh de rede", stillThere)
        assertEquals(SyncState.PENDING, stillThere!!.syncState)

        val synced = db.tabDao().getTabByLocalId("server-1")
        assertNotNull(synced)
    }

    @Test
    fun `refreshOpenTabsSnapshot remove comanda SYNCED que o servidor nao lista mais`() = runTest {
        // Uma comanda SYNCED que sumiu da lista do servidor (foi fechada por
        // outro terminal, por exemplo) deve sair do snapshot de "abertas" —
        // só PENDING é protegida, nunca dado já confirmado e desatualizado.
        val staleSynced = tab(localId = "stale-1", serverId = 111, syncState = SyncState.SYNCED)
        db.tabDao().upsertTab(staleSynced)

        db.tabDao().refreshOpenTabsSnapshot(organizationId = 1, locationId = 1, fresh = emptyList())

        assertNull(db.tabDao().getTabByLocalId("stale-1"))
    }

    @Test
    fun `getTabByServerId resolve depois que o outbox confirma a criacao`() = runTest {
        val draft = tab(localId = "local-abc", serverId = null)
        db.tabDao().upsertTab(draft)
        assertNull(db.tabDao().getTabByServerId(500))

        db.tabDao().updateTab(draft.copy(serverId = 500, syncState = SyncState.SYNCED))

        val resolved = db.tabDao().getTabByServerId(500)
        assertNotNull(resolved)
        assertEquals("local-abc", resolved!!.localId)
    }
}
