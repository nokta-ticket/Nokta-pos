package com.nokta.pos.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.nokta.pos.data.local.entity.OutboxEntity
import com.nokta.pos.data.local.entity.OutboxStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface OutboxDao {

    @Insert
    suspend fun enqueue(operation: OutboxEntity): Long

    /** Ordem de envio = ordem de criação (`sequence` autoincrement) — nunca reordenar. */
    @Query("SELECT * FROM outbox WHERE status != 'REJECTED' ORDER BY sequence ASC")
    suspend fun getPending(): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox WHERE status != 'REJECTED'")
    fun observePendingCount(): Flow<Int>

    @Update
    suspend fun update(operation: OutboxEntity)

    @Delete
    suspend fun delete(operation: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE tabLocalId = :tabLocalId AND status != 'REJECTED' ORDER BY sequence ASC")
    suspend fun getPendingForTab(tabLocalId: String): List<OutboxEntity>

    @Query("UPDATE outbox SET status = :status WHERE sequence = :sequence")
    suspend fun setStatus(sequence: Long, status: OutboxStatus)

    /**
     * Rejeita em cascata toda operação ainda pendente da mesma comanda — usado
     * quando o `CREATE_TAB` dela é recusado pelo servidor: sem a comanda
     * existir no backend, `SEND_ORDER`/`REGISTER_PAYMENT` da mesma
     * `tabLocalId` nunca teriam como ser aceitos, e ficariam retentando para
     * sempre em vez de aparecer como a falha real que são (ver
     * [com.nokta.pos.sync.SyncEngine]).
     */
    @Query("UPDATE outbox SET status = 'REJECTED', lastError = :reason WHERE tabLocalId = :tabLocalId AND status != 'REJECTED'")
    suspend fun rejectAllForTab(tabLocalId: String, reason: String)
}
