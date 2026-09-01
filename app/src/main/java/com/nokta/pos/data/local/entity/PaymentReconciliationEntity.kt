package com.nokta.pos.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Registro de uma divergência financeira real: um pagamento já foi
 * registrado (cobrado do cliente) contando com um item que, na
 * sincronização, o backend recusou. O dinheiro entrou por um valor que o
 * consumo aceito não sustenta mais — nunca é corrigido apagando/ajustando o
 * pagamento em silêncio (ver `SyncEngine`, branch `Rejected` de
 * `SEND_ORDER`); vira este registro append-only, visível na comanda até o
 * operador/gestor revisar e resolver manualmente (ex.: cobrar o item de
 * novo, ou não — decisão de negócio fora do escopo deste registro).
 *
 * Nunca é apagado automaticamente: `resolvedAtEpochMs`/`resolvedNote` são
 * preenchidos só quando alguém explicitamente marca como tratado.
 */
@Entity(
    tableName = "payment_reconciliation",
    foreignKeys = [
        ForeignKey(
            entity = TabEntity::class,
            parentColumns = ["localId"],
            childColumns = ["tabLocalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("tabLocalId")],
)
data class PaymentReconciliationEntity(
    @PrimaryKey val localId: String,
    val tabLocalId: String,
    /** localId do TabPaymentEntity que já cobria o item recusado. */
    val paymentLocalId: String,
    /** Snapshot descritivo do item recusado — o item em si já foi descartado (discardLocalOrder) quando este registro nasce. */
    val rejectedItemDescription: String,
    val rejectedItemAmountCents: Long,
    val rejectionReason: String,
    val createdAtEpochMs: Long,
    val resolvedAtEpochMs: Long? = null,
    val resolvedNote: String? = null,
) {
    val isResolved: Boolean get() = resolvedAtEpochMs != null
}
