package com.nokta.pos.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.syncStatusDataStore by preferencesDataStore(name = "sync_status")

/**
 * Só o instante da última vez que o Outbox ficou vazio — dado de UI puro
 * ("sincronizado há 4min"), não operacional, por isso continua em DataStore
 * em vez do Room (que guarda a fila em si e as comandas/itens/pagamentos).
 */
@Singleton
class SyncStatusStore @Inject constructor(private val context: Context) {
    private val lastSyncKey = longPreferencesKey("last_sync_at")

    val lastSyncAt: Flow<Long?> = context.syncStatusDataStore.data.map { it[lastSyncKey] }

    suspend fun markSynced() {
        context.syncStatusDataStore.edit { it[lastSyncKey] = System.currentTimeMillis() }
    }
}
