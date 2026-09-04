package com.nokta.pos.payment.cielo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.pendingPaymentDataStore by preferencesDataStore(name = "pending_cielo_payment")

@Serializable
data class PendingCieloAttempt(
    val attemptId: String,
    val tabId: Long,
    val amountCents: Long,
    val startedAtEpochMs: Long,
    /**
     * Método enviado à Cielo (`PosPaymentMethod.name`) — necessário para
     * retomar o REGISTRO do pagamento depois de um process death, quando o
     * ViewModel que sabia disso deixou de existir. Default só para não
     * quebrar a leitura de uma tentativa gravada por uma versão anterior do
     * app (migração implícita do DataStore).
     */
    val method: String = "CREDIT_CARD",
)

/**
 * Resultado APROVADO que a Cielo devolveu pelo callback e que ainda não foi
 * registrado como pagamento no Nokta.
 *
 * Existe porque o callback pode chegar quando não há mais ninguém esperando
 * por ele — o processo do app foi morto pelo Android enquanto o app da Cielo
 * cobrava o cliente, e a coroutine suspensa em `startPayment` morreu junto.
 * Sem persistir isto, a única prova de que o cliente FOI COBRADO se perdia:
 * ao reabrir, o app sabia que havia uma tentativa em andamento mas não o
 * desfecho dela, e o operador só tinha a opção de descartar (arriscando
 * cobrar de novo) ou conferir o extrato na mão.
 *
 * Guarda exatamente o que `TabRepository.registerPayment` precisa para
 * concluir o registro depois, com a MESMA `idempotencyKey` (`attemptId`) —
 * retomar nunca gera uma segunda cobrança.
 */
@Serializable
data class ApprovedCieloResult(
    val attemptId: String,
    val tabId: Long,
    val amountCents: Long,
    val providerTransactionId: String,
    val method: String,
    val approvedAtEpochMs: Long,
)

/**
 * Persiste a tentativa de pagamento Cielo EM ANDAMENTO em disco, antes de
 * disparar o Intent — sobrevive a processo morto/Activity recriada
 * (seção 47 do PRD: "pagamento em andamento não pode ser apagado
 * simplesmente porque a activity foi recriada"). Ao reabrir o app, a Home
 * checa se há uma tentativa pendente e força o usuário a resolvê-la (ver
 * CieloDeepLinkPaymentProvider.recoverPendingAttempt) antes de iniciar
 * qualquer nova cobrança para a mesma comanda — nunca dispara uma segunda
 * cobrança automática por cima de um resultado desconhecido (seção 24).
 *
 * Guarda também o RESULTADO aprovado ([ApprovedCieloResult]) quando ele
 * chega sem ninguém esperando — ver o KDoc daquela classe.
 */
@Singleton
class PendingCieloAttemptStore @Inject constructor(
    private val context: Context,
) {
    private val key = stringPreferencesKey("pending_attempt_json")
    private val approvedKey = stringPreferencesKey("approved_result_json")

    suspend fun save(attempt: PendingCieloAttempt) {
        context.pendingPaymentDataStore.edit { prefs ->
            prefs[key] = Json.encodeToString(attempt)
        }
    }

    suspend fun clear() {
        context.pendingPaymentDataStore.edit { prefs -> prefs.remove(key) }
    }

    suspend fun current(): PendingCieloAttempt? {
        val raw = context.pendingPaymentDataStore.data.first()[key] ?: return null
        return runCatching { Json.decodeFromString<PendingCieloAttempt>(raw) }.getOrNull()
    }

    /**
     * Grava o resultado aprovado assim que o callback é decodificado — ANTES
     * de qualquer tentativa de entregá-lo a quem está esperando. Se o app
     * morrer no instante seguinte, a aprovação continua registrada em disco.
     */
    suspend fun saveApprovedResult(result: ApprovedCieloResult) {
        context.pendingPaymentDataStore.edit { prefs ->
            prefs[approvedKey] = Json.encodeToString(result)
        }
    }

    suspend fun approvedResult(): ApprovedCieloResult? {
        val raw = context.pendingPaymentDataStore.data.first()[approvedKey] ?: return null
        return runCatching { Json.decodeFromString<ApprovedCieloResult>(raw) }.getOrNull()
    }

    /** Só depois do pagamento estar REGISTRADO no Nokta — nunca antes. */
    suspend fun clearApprovedResult() {
        context.pendingPaymentDataStore.edit { prefs -> prefs.remove(approvedKey) }
    }
}
