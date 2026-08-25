package com.nokta.pos.network.dto

import kotlinx.serialization.Serializable

/**
 * Resposta de `GET organizations/:organizationId/me/access` (backend
 * `VenueMeAccessController`). Só o bloco `venue` interessa ao POS — os demais
 * módulos (tickets, jurídico/financeiro) são domínio do dashboard web.
 */
@Serializable
data class MeAccessModule(
    val role: String,
    val permissions: List<String> = emptyList(),
    val defaultRoute: String? = null,
)

@Serializable
data class MeAccessResponse(
    val organizationId: Long,
    val membershipStatus: String? = null,
    val organizationRole: String? = null,
    val modules: Map<String, MeAccessModule> = emptyMap(),
) {
    val venue: MeAccessModule? get() = modules["venue"]
}
