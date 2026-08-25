package com.nokta.pos.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Uma operação pendente de envio, uma linha por operação (substitui o blob
 * JSON único que existia em DataStore — sobrevive a crash NO MEIO de uma
 * gravação, e permite consultar/filtrar sem desserializar a fila inteira).
 *
 * `payloadJson` guarda os dados específicos de cada [OutboxOperationType]
 * (ids locais envolvidos, linhas do pedido, valor do pagamento etc.) — um
 * schema por tipo criaria 6 tabelas quase idênticas para um volume que nunca
 * passa de algumas dezenas de linhas por turno.
 *
 * `sequence` é a ordem de criação (autoincrement), e É a ordem de envio: um
 * `ADD_ITEM` sempre sai antes do `REGISTER_PAYMENT` da mesma comanda, porque
 * foi enfileirado antes. Nunca reordenar por prioridade — a ordem cronológica
 * É a dependência (pagamento maior que o total conhecido é recusado).
 */
enum class OutboxOperationType {
    CREATE_TAB,
    ADD_ITEM,
    SEND_ORDER,
    CANCEL_ITEM,
    REGISTER_PAYMENT,
    CLOSE_TAB,
}

enum class OutboxStatus { PENDING, SYNCING, FAILED_RETRYABLE, REJECTED }

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    /** UUID da operação em si — nunca muda entre tentativas; é a chave de idempotência enviada ao backend. */
    val operationId: String,
    val type: OutboxOperationType,
    val organizationId: Long,
    /** localId da Tab a que esta operação pertence — usado para checar dependência (Fase 4). */
    val tabLocalId: String,
    val payloadJson: String,
    val status: OutboxStatus,
    val retryCount: Int,
    val lastError: String?,
    val createdAtEpochMs: Long,
    val lastAttemptAtEpochMs: Long?,
)
