package com.nokta.pos.sync

import kotlinx.serialization.Serializable

/**
 * Fila de operações feitas offline, aguardando o servidor.
 *
 * O que ENTRA aqui e o que NUNCA entra:
 *
 *  - Lançar itens numa comanda existente → entra. É uma operação puramente
 *    interna da Nokta, e o backend já é idempotente por `clientRequestId`.
 *  - Pagamento em DINHEIRO → entra. O dinheiro trocou de mão fisicamente,
 *    fora de qualquer sistema; a Nokta só está registrando um fato.
 *  - Pagamento em CARTÃO (Cielo) → **nunca entra**. Autorização de cartão é
 *    da adquirente e depende da rede dela; enfileirar uma "promessa de
 *    cobrança" seria inventar aprovação financeira offline (proibido pelo
 *    item 19 do brief). Se a Cielo já aprovou e só o REGISTRO no Nokta
 *    falhou, aí sim entra — porque nesse ponto a cobrança é um fato
 *    consumado, e o registro reenvia a mesma `idempotencyKey`.
 *  - PIX → não entra por padrão: o operador confirma o recebimento olhando o
 *    app do banco, e isso pressupõe rede.
 *
 * Toda entrada carrega a chave de idempotência que a operação usaria online.
 * Reenviar a mesma chave é o que garante que uma sincronização repetida (app
 * reaberto, rede oscilando) nunca duplique pedido nem pagamento.
 */
@Serializable
sealed class OutboxOperation {

    abstract val id: String
    abstract val organizationId: Long
    abstract val createdAtEpochMs: Long

    /** Itens lançados numa comanda que já existe no servidor. */
    @Serializable
    data class SubmitOrder(
        override val id: String,
        override val organizationId: Long,
        override val createdAtEpochMs: Long,
        val tabId: Long,
        /** Mesma chave em toda retentativa — o backend devolve o pedido já criado. */
        val clientRequestId: String,
        val lines: List<OutboxOrderLine>,
    ) : OutboxOperation()

    /** Pagamento cujo dinheiro já é fato (dinheiro em espécie, ou cartão já aprovado). */
    @Serializable
    data class RegisterPayment(
        override val id: String,
        override val organizationId: Long,
        override val createdAtEpochMs: Long,
        val tabId: Long,
        val method: String,
        val amountCents: Long,
        val receivedCents: Long? = null,
        /** Vira `VenuePayment.idempotencyKey` — impede pagamento duplicado. */
        val idempotencyKey: String,
        val externalReference: String? = null,
    ) : OutboxOperation()
}

@Serializable
data class OutboxOrderLine(
    val menuItemId: Long,
    val variantId: Long,
    val quantity: Int,
    val notes: String? = null,
    val modifiers: List<OutboxOrderLineModifier> = emptyList(),
)

@Serializable
data class OutboxOrderLineModifier(
    val modifierGroupId: Long,
    val modifierOptionId: Long,
    val quantity: Int = 1,
)

/** Resultado de uma tentativa de sincronização de UMA operação. */
sealed class SyncOutcome {
    /** Aplicada no servidor (ou já estava aplicada — idempotência). Remover da fila. */
    data object Success : SyncOutcome()

    /**
     * Falha de rede/servidor temporária. Mantém na fila para a próxima
     * tentativa — nunca descarta silenciosamente uma venda.
     */
    data class Retry(val reason: String) : SyncOutcome()

    /**
     * O servidor recusou de forma definitiva (comanda já fechada, item que
     * não existe mais). Retentar não muda nada: sai da fila e vira pendência
     * visível para o operador resolver — nunca some sem deixar rastro.
     */
    data class Rejected(val reason: String) : SyncOutcome()
}
