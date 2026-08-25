package com.nokta.pos.access

/**
 * O que o operador logado pode fazer neste terminal.
 *
 * Espelha as permissões granulares do Venue (`venue-roles.catalog.ts`) que o
 * backend devolve em `GET organizations/:id/me/access`. É usado SÓ para
 * decidir o que a interface oferece — o backend revalida tudo em cada
 * endpoint (`VenuePermissionGuard`), então esconder um botão aqui nunca é a
 * barreira de segurança, só evita que o garçom toque num caminho que vai
 * falhar com 403 na cara dele.
 *
 * Os papéis reais importam para o POS assim:
 *  - WAITER  → lança pedido, consulta mesa/comanda; NÃO registra pagamento.
 *  - CASHIER → registra pagamento e fecha comanda; NÃO lança pedido.
 *  - MANAGER/OWNER → tudo.
 *
 * Por isso a Home nunca some inteira: um garçom sem permissão de pagamento
 * continua vendo mesa/comanda e lançando itens; só o passo de cobrar fica
 * indisponível (e explicado), em vez de um erro seco do servidor.
 */
data class OperatorAccess(
    val role: String? = null,
    val permissions: Set<String> = emptySet(),
) {
    fun can(permission: String): Boolean = permissions.contains(permission)

    val canViewTables get() = can(P_TABLES_VIEW)
    val canViewTabs get() = can(P_TABS_VIEW)
    val canOpenTabs get() = can(P_TABS_OPEN)
    val canManageTabs get() = can(P_TABS_MANAGE)
    val canCreateOrders get() = can(P_ORDERS_CREATE)
    val canTakePayments get() = can(P_PAYMENTS_MANAGE)
    val canViewMenu get() = can(P_MENU_VIEW)

    /** Venda de balcão exige abrir comanda + lançar pedido + cobrar, tudo numa tacada. */
    val canSellAtCounter get() = canOpenTabs && canCreateOrders && canTakePayments

    companion object {
        const val P_MENU_VIEW = "venue.menu.view"
        const val P_TABLES_VIEW = "venue.operation.tables.view"
        const val P_TABS_VIEW = "venue.operation.tabs.view"
        const val P_TABS_OPEN = "venue.operation.tabs.open"
        const val P_TABS_MANAGE = "venue.operation.tabs.manage"
        const val P_ORDERS_VIEW = "venue.operation.orders.view"
        const val P_ORDERS_CREATE = "venue.operation.orders.create"
        const val P_PAYMENTS_MANAGE = "venue.operation.payments.manage"

        /**
         * Fallback quando `GET me/access` não pôde ser consultado (offline no
         * primeiro login, por exemplo). Deliberadamente PERMISSIVO: esconder
         * tudo deixaria o operador sem app numa queda de rede, e o backend
         * continua barrando de verdade o que ele não pode fazer. O oposto
         * (esconder por precaução) transformaria um problema de rede em
         * "a maquininha não funciona".
         */
        val PERMISSIVE = OperatorAccess(
            role = null,
            permissions = setOf(
                P_MENU_VIEW, P_TABLES_VIEW, P_TABS_VIEW, P_TABS_OPEN, P_TABS_MANAGE,
                P_ORDERS_VIEW, P_ORDERS_CREATE, P_PAYMENTS_MANAGE,
            ),
        )
    }
}
