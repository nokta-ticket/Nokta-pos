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
 */
@Singleton
class PendingCieloAttemptStore @Inject constructor(
    private val context: Context,
) {
    private val key = stringPreferencesKey("pending_attempt_json")

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
}
