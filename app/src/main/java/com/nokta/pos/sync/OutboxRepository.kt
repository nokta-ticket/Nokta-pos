package com.nokta.pos.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nokta.pos.comanda.data.OperationRepository
import com.nokta.pos.comanda.domain.OrderLine
import com.nokta.pos.comanda.domain.OrderLineModifier
import com.nokta.pos.common.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.outboxDataStore by preferencesDataStore(name = "operation_outbox")

/**
 * Fila local de operações pendentes de envio.
 *
 * Guardada em DataStore (não Room) de propósito: a fila é curta por natureza
 * — segundos ou minutos de rede ruim, algumas dezenas de operações no pior
 * caso — e o app já depende de DataStore para cache e credenciais. Uma
 * dependência de banco a mais só se justificaria com volume ou consultas
 * complexas, e não há nem um nem outro aqui.
 *
 * A ordem de envio é FIFO e IMPORTA: lançar itens antes de registrar o
 * pagamento da mesma comanda, senão o pagamento seria maior que o total
 * conhecido pelo servidor e seria recusado.
 */
@Singleton
class OutboxRepository @Inject constructor(
    private val context: Context,
    private val operationRepository: OperationRepository,
) {
    private val queueKey = stringPreferencesKey("pending_operations")
    private val lastSyncKey = longPreferencesKey("last_sync_at")
    private val json = Json { ignoreUnknownKeys = true }

    val pendingCount: Flow<Int> = context.outboxDataStore.data.map { prefs ->
        prefs[queueKey]?.let { runCatching { decode(it).size }.getOrDefault(0) } ?: 0
    }

    /**
     * Quando a fila esvaziou pela última vez. Alimenta o "sincronizado há X"
     * da Home — offline sem essa informação não diz ao operador se ele pode
     * fechar o turno ou se tem venda de uma hora atrás presa no aparelho.
     */
    val lastSyncAt: Flow<Long?> = context.outboxDataStore.data.map { it[lastSyncKey] }

    private suspend fun markSynced() {
        context.outboxDataStore.edit { it[lastSyncKey] = System.currentTimeMillis() }
    }

    suspend fun enqueue(operation: OutboxOperation) {
        context.outboxDataStore.edit { prefs ->
            val current = prefs[queueKey]?.let { runCatching { decode(it) }.getOrDefault(emptyList()) } ?: emptyList()
            prefs[queueKey] = encode(current + operation)
        }
    }

    suspend fun peekAll(): List<OutboxOperation> {
        val raw = context.outboxDataStore.data.first()[queueKey] ?: return emptyList()
        return runCatching { decode(raw) }.getOrDefault(emptyList())
    }

    private suspend fun remove(operationId: String) {
        context.outboxDataStore.edit { prefs ->
            val current = prefs[queueKey]?.let { runCatching { decode(it) }.getOrDefault(emptyList()) } ?: emptyList()
            prefs[queueKey] = encode(current.filterNot { it.id == operationId })
        }
    }

    /**
     * Tenta enviar tudo que está na fila, em ordem.
     *
     * Para na PRIMEIRA falha de rede em vez de seguir para as próximas: se a
     * conexão caiu, as seguintes falhariam igual, e insistir só arrisca
     * inverter a ordem entre pedido e pagamento da mesma comanda.
     *
     * Devolve o que aconteceu com cada operação processada, para a UI poder
     * avisar sobre rejeições definitivas.
     */
    suspend fun syncAll(): List<Pair<OutboxOperation, SyncOutcome>> {
        val results = mutableListOf<Pair<OutboxOperation, SyncOutcome>>()
        for (operation in peekAll()) {
            val outcome = runOperation(operation)
            results += operation to outcome
            when (outcome) {
                is SyncOutcome.Success, is SyncOutcome.Rejected -> remove(operation.id)
                is SyncOutcome.Retry -> return results // rede caiu — tenta tudo de novo depois
            }
        }
        // Chegou aqui: nada ficou pendente por falha de rede. Marca o momento
        // em que o terminal esteve comprovadamente em dia com o servidor.
        markSynced()
        return results
    }

    private suspend fun runOperation(operation: OutboxOperation): SyncOutcome = try {
        when (operation) {
            is OutboxOperation.SubmitOrder -> {
                operationRepository.submitOrder(
                    organizationId = operation.organizationId,
                    tabId = operation.tabId,
                    lines = operation.lines.map { line ->
                        OrderLine(
                            menuItemId = line.menuItemId,
                            variantId = line.variantId,
                            quantity = line.quantity,
                            notes = line.notes,
                            modifiers = line.modifiers.map {
                                OrderLineModifier(it.modifierGroupId, it.modifierOptionId, it.quantity)
                            },
                        )
                    },
                    clientRequestId = operation.clientRequestId,
                )
                SyncOutcome.Success
            }
            is OutboxOperation.RegisterPayment -> {
                operationRepository.registerPayment(
                    organizationId = operation.organizationId,
                    tabId = operation.tabId,
                    method = operation.method,
                    amount = Money(operation.amountCents),
                    idempotencyKey = operation.idempotencyKey,
                    receivedCents = operation.receivedCents,
                    externalReference = operation.externalReference,
                )
                SyncOutcome.Success
            }
        }
    } catch (e: IOException) {
        // Sem rede: a operação continua válida, só não deu para enviar agora.
        SyncOutcome.Retry(e.message ?: "Sem conexão")
    } catch (e: HttpException) {
        // 4xx é decisão do servidor e não muda com retry (comanda fechada,
        // item inexistente). 5xx é falha temporária do lado dele.
        if (e.code() in 400..499) {
            SyncOutcome.Rejected(e.message() ?: "Operação recusada pelo servidor")
        } else {
            SyncOutcome.Retry("Servidor indisponível (${e.code()})")
        }
    } catch (e: Exception) {
        SyncOutcome.Retry(e.message ?: "Falha ao sincronizar")
    }

    private fun encode(operations: List<OutboxOperation>): String = json.encodeToString(operations)
    private fun decode(raw: String): List<OutboxOperation> = json.decodeFromString(raw)
}
