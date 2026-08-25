package com.nokta.pos.network

import com.nokta.pos.network.dto.*
import retrofit2.http.*

/**
 * Todo endpoint aqui já existe no backend nokta-api (ver
 * docs/pos-mvp-reuse-map.md) — o app POS não introduz nenhuma regra de
 * negócio nova do lado servidor além do módulo VenueDevice/device-login.
 */
interface NoktaApi {

    // ---- Pareamento e autenticação do terminal ----

    @POST("venue-devices/pairing/redeem")
    suspend fun redeemPairingCode(@Body body: RedeemPairingCodeRequest): RedeemPairingCodeResponse

    @POST("auth/device-login")
    suspend fun deviceLogin(@Body body: DeviceLoginRequest): DeviceLoginResponse

    /** Permissões granulares do operador — decide o que a UI oferece (backend revalida sempre). */
    @GET("organizations/{orgId}/me/access")
    suspend fun getMeAccess(@Path("orgId") organizationId: Long): MeAccessResponse

    // ---- Cardápio ----

    @GET("organizations/{orgId}/venue/menus/{menuId}/preview")
    suspend fun getMenuPreview(
        @Path("orgId") organizationId: Long,
        @Path("menuId") menuId: Long,
    ): MenuPreviewResponse

    @GET("organizations/{orgId}/venue/products/{productId}/modifier-groups")
    suspend fun getProductModifierGroups(
        @Path("orgId") organizationId: Long,
        @Path("productId") productId: Long,
    ): List<ProductModifierGroupResponse>

    // ---- Comanda ----

    @POST("organizations/{orgId}/venue/operation/locations/{locationId}/tabs")
    suspend fun createTab(
        @Path("orgId") organizationId: Long,
        @Path("locationId") locationId: Long,
        @Body body: CreateTabRequest,
    ): TabResponse

    @GET("organizations/{orgId}/venue/operation/tabs/{tabId}")
    suspend fun getTab(
        @Path("orgId") organizationId: Long,
        @Path("tabId") tabId: Long,
    ): TabResponse

    @GET("organizations/{orgId}/venue/operation/locations/{locationId}/tabs/by-code/{publicCode}")
    suspend fun getTabByPublicCode(
        @Path("orgId") organizationId: Long,
        @Path("locationId") locationId: Long,
        @Path("publicCode") publicCode: String,
    ): TabResponse

    @POST("organizations/{orgId}/venue/operation/tabs/{tabId}/close")
    suspend fun closeTab(
        @Path("orgId") organizationId: Long,
        @Path("tabId") tabId: Long,
    ): TabResponse

    /**
     * Busca comandas da unidade. `search` casa `publicCode` OU `customerName`
     * no backend (`venue-tabs.service.ts`), então o mesmo campo serve para
     * "comanda 123" e para "João" — sem endpoint novo.
     */
    @GET("organizations/{orgId}/venue/operation/locations/{locationId}/tabs")
    suspend fun listTabs(
        @Path("orgId") organizationId: Long,
        @Path("locationId") locationId: Long,
        @Query("status") status: String? = null,
        @Query("type") type: String? = null,
        @Query("search") search: String? = null,
    ): List<TabResponse>

    // ---- Mesas ----

    /** Já vem com `openTab` de cada mesa — ocupação e consumo numa única chamada. */
    @GET("organizations/{orgId}/venue/operation/locations/{locationId}/tables")
    suspend fun listTables(
        @Path("orgId") organizationId: Long,
        @Path("locationId") locationId: Long,
    ): List<TableResponse>

    // ---- Pedidos ----

    @POST("organizations/{orgId}/venue/operation/tabs/{tabId}/orders")
    suspend fun createOrder(
        @Path("orgId") organizationId: Long,
        @Path("tabId") tabId: Long,
        @Body body: CreateOrderRequest,
    ): OrderResponse

    @POST("organizations/{orgId}/venue/operation/orders/{orderId}/send")
    suspend fun sendOrder(
        @Path("orgId") organizationId: Long,
        @Path("orderId") orderId: Long,
    ): OrderResponse

    /**
     * Cancela um item lançado. Nunca apaga da ledger — o backend marca
     * CANCELED gravando quem cancelou e o motivo (`canceledByUserId`,
     * `cancellationReason`) e recalcula o total da comanda.
     */
    @POST("organizations/{orgId}/venue/operation/order-items/{itemId}/cancel")
    suspend fun cancelOrderItem(
        @Path("orgId") organizationId: Long,
        @Path("itemId") itemId: Long,
        @Body body: CancelOrderItemRequest,
    ): OrderItemResponse

    // ---- Pagamentos ----

    @POST("organizations/{orgId}/venue/operation/tabs/{tabId}/payments")
    suspend fun createPayment(
        @Path("orgId") organizationId: Long,
        @Path("tabId") tabId: Long,
        @Body body: CreatePaymentRequest,
    ): PaymentResponse
}
