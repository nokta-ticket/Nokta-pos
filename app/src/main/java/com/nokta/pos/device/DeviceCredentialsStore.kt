package com.nokta.pos.device

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.nokta.pos.payment.cielo.CieloCredentials
import com.nokta.pos.payment.cielo.CieloCredentialsProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Armazenamento seguro local do terminal — nunca sincronizado de volta ao
 * backend (seção 46 do PRD: segredos de backend nunca vão pro dispositivo,
 * mas o inverso também vale: o que o dispositivo recebe fica só nele).
 * EncryptedSharedPreferences (AndroidX Security) cifra em repouso usando o
 * Android Keystore — sobrevive a restart do processo/reboot (seção 47).
 */
@Singleton
class DeviceCredentialsStore @Inject constructor(
    context: Context,
) : CieloCredentialsProvider {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "nokta_pos_device_credentials",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun saveDeviceToken(token: String) {
        prefs.edit().putString(KEY_DEVICE_TOKEN, token).apply()
    }

    fun deviceToken(): String? = prefs.getString(KEY_DEVICE_TOKEN, null)

    fun isPaired(): Boolean = deviceToken() != null

    fun saveSession(
        jwt: String,
        userId: Long,
        userName: String,
        role: String,
        organizationId: Long,
        locationId: Long,
        locationName: String? = null,
        sessionExpiresAtEpochMs: Long? = null,
    ) {
        prefs.edit()
            .putString(KEY_JWT, jwt)
            .putLong(KEY_USER_ID, userId)
            .putString(KEY_USER_NAME, userName)
            .putString(KEY_USER_ROLE, role)
            .putLong(KEY_ORGANIZATION_ID, organizationId)
            .putLong(KEY_LOCATION_ID, locationId)
            .apply {
                if (locationName != null) putString(KEY_LOCATION_NAME, locationName)
                if (sessionExpiresAtEpochMs != null) putLong(KEY_SESSION_EXPIRES_AT, sessionExpiresAtEpochMs)
            }
            .apply()
    }

    fun organizationId(): Long? = prefs.getLong(KEY_ORGANIZATION_ID, -1).takeIf { it != -1L }
    fun locationId(): Long? = prefs.getLong(KEY_LOCATION_ID, -1).takeIf { it != -1L }
    fun locationName(): String? = prefs.getString(KEY_LOCATION_NAME, null)

    /**
     * Cardápio principal da unidade, resolvido no login (o backend responde
     * qual é o `isMain`). Antes disso o app usava um id fixo `1L`, que só
     * funcionava por coincidência na primeira organização de teste.
     */
    fun saveMainMenuId(menuId: Long) {
        prefs.edit().putLong(KEY_MAIN_MENU_ID, menuId).apply()
    }

    fun mainMenuId(): Long? = prefs.getLong(KEY_MAIN_MENU_ID, -1).takeIf { it != -1L }

    fun savePosConfig(
        operationMode: String?,
        requireOpenCashSessionForPayments: Boolean,
        blockTabCloseWithPendingItems: Boolean,
    ) {
        prefs.edit()
            .putString(KEY_OPERATION_MODE, operationMode)
            .putBoolean(KEY_REQUIRE_CASH_SESSION, requireOpenCashSessionForPayments)
            .putBoolean(KEY_BLOCK_CLOSE_PENDING, blockTabCloseWithPendingItems)
            .apply()
    }

    fun operationMode(): String? = prefs.getString(KEY_OPERATION_MODE, null)
    fun requiresOpenCashSessionForPayments(): Boolean = prefs.getBoolean(KEY_REQUIRE_CASH_SESSION, true)
    fun blocksTabCloseWithPendingItems(): Boolean = prefs.getBoolean(KEY_BLOCK_CLOSE_PENDING, false)

    /** Permissões granulares do operador (GET me/access), separadas por vírgula. */
    fun savePermissions(permissions: Set<String>) {
        prefs.edit().putString(KEY_PERMISSIONS, permissions.joinToString(",")).apply()
    }

    fun permissions(): Set<String> =
        prefs.getString(KEY_PERMISSIONS, null)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()

    fun sessionExpiresAt(): Long? = prefs.getLong(KEY_SESSION_EXPIRES_AT, -1).takeIf { it != -1L }

    /**
     * Marca em qual boot do aparelho a sessão atual foi criada/confirmada.
     * `bootInstant` é constante durante a vida do kernel e muda sempre que o
     * aparelho reinicia de verdade — diferente de fechar/reabrir o app
     * (mesmo processo do kernel, mesmo valor), que nunca deve derrubar a
     * sessão. Ver [AuthRepository.isSessionStaleAfterReboot].
     */
    fun saveSessionBootInstant(bootInstant: Long) {
        prefs.edit().putLong(KEY_SESSION_BOOT_INSTANT, bootInstant).apply()
    }

    fun sessionBootInstant(): Long? = prefs.getLong(KEY_SESSION_BOOT_INSTANT, -1).takeIf { it != -1L }

    /**
     * Sessão do operador — some no logout/expiração. NUNCA apaga o pareamento
     * do terminal nem as credenciais Cielo da unidade: trocar de operador não
     * pode exigir parear a maquininha de novo (docs/pos-mvp-architecture.md,
     * "dois estágios de identidade").
     */
    fun clearSession() {
        prefs.edit()
            .remove(KEY_JWT)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_NAME)
            .remove(KEY_USER_ROLE)
            .remove(KEY_PERMISSIONS)
            .remove(KEY_SESSION_EXPIRES_AT)
            .remove(KEY_SESSION_BOOT_INSTANT)
            .apply()
    }

    fun jwt(): String? = prefs.getString(KEY_JWT, null)
    fun currentUserRole(): String? = prefs.getString(KEY_USER_ROLE, null)
    fun currentUserName(): String? = prefs.getString(KEY_USER_NAME, null)

    fun saveCieloCredentials(clientId: String, accessToken: String) {
        prefs.edit()
            .putString(KEY_CIELO_CLIENT_ID, clientId)
            .putString(KEY_CIELO_ACCESS_TOKEN, accessToken)
            .apply()
    }

    fun clearCieloCredentials() {
        prefs.edit().remove(KEY_CIELO_CLIENT_ID).remove(KEY_CIELO_ACCESS_TOKEN).apply()
    }

    override suspend fun current(): CieloCredentials? {
        val clientId = prefs.getString(KEY_CIELO_CLIENT_ID, null) ?: return null
        val accessToken = prefs.getString(KEY_CIELO_ACCESS_TOKEN, null) ?: return null
        return CieloCredentials(clientId, accessToken)
    }

    private companion object {
        const val KEY_DEVICE_TOKEN = "device_token"
        const val KEY_JWT = "jwt"
        const val KEY_USER_ID = "user_id"
        const val KEY_USER_NAME = "user_name"
        const val KEY_USER_ROLE = "user_role"
        const val KEY_PERMISSIONS = "permissions"
        const val KEY_SESSION_EXPIRES_AT = "session_expires_at"
        const val KEY_SESSION_BOOT_INSTANT = "session_boot_instant"
        const val KEY_ORGANIZATION_ID = "organization_id"
        const val KEY_LOCATION_ID = "location_id"
        const val KEY_LOCATION_NAME = "location_name"
        const val KEY_MAIN_MENU_ID = "main_menu_id"
        const val KEY_OPERATION_MODE = "operation_mode"
        const val KEY_REQUIRE_CASH_SESSION = "require_cash_session"
        const val KEY_BLOCK_CLOSE_PENDING = "block_close_pending"
        const val KEY_CIELO_CLIENT_ID = "cielo_client_id"
        const val KEY_CIELO_ACCESS_TOKEN = "cielo_access_token"
    }
}
