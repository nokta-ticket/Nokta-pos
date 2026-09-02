package com.nokta.pos.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class RedeemPairingCodeRequest(val code: String)

@Serializable
data class RedeemPairingCodeResponse(
    val id: Long,
    val deviceToken: String,
    val label: String,
    val organizationId: Long,
    val locationId: Long,
)

@Serializable
data class DeviceLoginRequest(val email: String, val senha: String)

@Serializable
data class DeviceStatusResponse(
    val id: Long,
    val label: String,
    val organizationId: Long,
    val locationId: Long,
)

@Serializable
data class DeviceLoginUser(
    val userId: Long,
    val email: String,
    val role: String,
    val nome: String? = null,
    val sobrenome: String? = null,
    val nivelProdutor: Int? = null,
) {
    /** Primeiro nome, que é como o operador se reconhece na Home. Cai pro e-mail só se o backend não mandar nome. */
    val displayName: String get() = nome?.trim()?.takeIf { it.isNotEmpty() }?.substringBefore(' ') ?: email
}

@Serializable
data class DeviceLoginLocation(val id: Long, val nome: String)

@Serializable
data class DeviceLoginMenu(val id: Long, val nome: String)

/**
 * `clientId`/`accessToken` são GLOBAIS da Nokta (identificam a integração,
 * não o estabelecimento — cadastrados uma vez pelo SUPER_ADMIN no painel
 * Adquirentes). `merchantCode` é o único campo por unidade: o EC que
 * identifica quem recebe o dinheiro daquela venda na Cielo.
 */
@Serializable
data class DeviceLoginCielo(val clientId: String, val accessToken: String, val merchantCode: String)

/**
 * Configuração operacional da unidade. NUNCA remove capacidade do app — o POS
 * suporta balcão, mesa e comanda sempre. Isto só decide o que a Home destaca
 * primeiro e quais avisos mostrar antes do operador esbarrar num erro do
 * backend (ex.: caixa fechado bloqueando pagamento).
 */
@Serializable
data class DeviceLoginPosConfig(
    val operationMode: String? = null, // TABLE_SERVICE | COUNTER_SERVICE | MIXED
    val requireOpenCashSessionForPayments: Boolean = true,
    val blockTabCloseWithPendingItems: Boolean = false,
)

@Serializable
data class DeviceLoginResponse(
    val requires2fa: Boolean = false,
    val twoFactorToken: String? = null,
    val token: String? = null,
    val sessionExpiresAt: String? = null,
    val user: DeviceLoginUser? = null,
    val organizationId: Long? = null,
    val location: DeviceLoginLocation? = null,
    val mainMenu: DeviceLoginMenu? = null,
    val posConfig: DeviceLoginPosConfig? = null,
    val cielo: DeviceLoginCielo? = null,
)
