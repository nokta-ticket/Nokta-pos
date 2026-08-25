package com.nokta.pos.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.nokta.pos.session.SessionEvents
import com.nokta.pos.ui.cardapio.CardapioScreen
import com.nokta.pos.ui.checkout.CheckoutScreen
import com.nokta.pos.ui.comanda.BuscarComandaScreen
import com.nokta.pos.ui.comanda.ComandaScreen
import com.nokta.pos.ui.components.PosLoading
import com.nokta.pos.ui.historico.HistoricoScreen
import com.nokta.pos.ui.home.HomeScreen
import com.nokta.pos.ui.login.LoginScreen
import com.nokta.pos.ui.mesa.MesasScreen
import com.nokta.pos.ui.pairing.PairingScreen
import com.nokta.pos.ui.splash.SplashViewModel
import com.nokta.pos.ui.splash.StartDestination
import com.nokta.pos.ui.venda.NovaVendaScreen

object Routes {
    const val SPLASH = "splash"
    const val PAIRING = "pairing"
    const val LOGIN = "login"
    const val HOME = "home"
    const val NOVA_VENDA = "nova-venda"
    const val MESAS = "mesas"
    const val BUSCAR_COMANDA = "buscar-comanda"
    const val HISTORICO = "historico"
    const val COMANDA = "comanda/{tabId}"
    const val CARDAPIO = "cardapio/{tabId}"
    const val CHECKOUT = "checkout/{tabId}"

    fun comanda(tabId: Long) = "comanda/$tabId"
    fun cardapio(tabId: Long) = "cardapio/$tabId"
    fun checkout(tabId: Long) = "checkout/$tabId"
}

private val tabIdArg = navArgument("tabId") { type = NavType.LongType }

/**
 * Navegação do POS.
 *
 * Duas coisas importantes aqui:
 *
 * 1. A entrada é o SPLASH, não o pareamento. Um terminal já pareado com
 *    operador logado vai direto pra Home; antes, ele voltava sempre para
 *    "digite o código de 6 dígitos" e exigia um código novo do dashboard a
 *    cada abertura do app.
 *
 * 2. Sessão expirada (401) leva ao login de qualquer tela, uma vez só, sem
 *    derrubar o pareamento do terminal — quem observa isso é o
 *    `SessionEvents`, alimentado pelo interceptor de rede.
 *
 * A rota de scanner de QR foi removida da navegação: um garçom com maquininha
 * digita "mesa 12" ou "comanda 123", não escaneia (item 3 do brief). Os
 * arquivos do scanner continuam no projeto, sem tela apontando para eles.
 */
@Composable
fun NoktaPosNavHost(navController: NavHostController, sessionEvents: SessionEvents) {

    // Sessão caiu em qualquer ponto do app → volta pro login limpando a pilha.
    val expired by sessionEvents.expired.collectAsState(initial = null)
    LaunchedEffect(expired) {
        if (expired != null) {
            navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
            sessionEvents.consume()
        }
    }

    NavHost(navController = navController, startDestination = Routes.SPLASH) {

        composable(Routes.SPLASH) {
            val viewModel: SplashViewModel = hiltViewModel()
            val destination by viewModel.destination.collectAsState()
            LaunchedEffect(destination) {
                val route = when (destination) {
                    StartDestination.PAIRING -> Routes.PAIRING
                    StartDestination.LOGIN -> Routes.LOGIN
                    StartDestination.HOME -> Routes.HOME
                    null -> return@LaunchedEffect
                }
                navController.navigate(route) { popUpTo(Routes.SPLASH) { inclusive = true } }
            }
            PosLoading()
        }

        composable(Routes.PAIRING) {
            PairingScreen(onPaired = {
                navController.navigate(Routes.LOGIN) { popUpTo(Routes.PAIRING) { inclusive = true } }
            })
        }

        composable(Routes.LOGIN) {
            LoginScreen(onLoggedIn = {
                navController.navigate(Routes.HOME) { popUpTo(Routes.LOGIN) { inclusive = true } }
            })
        }

        composable(Routes.HOME) {
            HomeScreen(
                onNovaVenda = { navController.navigate(Routes.NOVA_VENDA) },
                onMesas = { navController.navigate(Routes.MESAS) },
                onComandas = { navController.navigate(Routes.BUSCAR_COMANDA) },
                onHistorico = { navController.navigate(Routes.HISTORICO) },
                onOpenTab = { tabId -> navController.navigate(Routes.comanda(tabId)) },
                onLogout = {
                    navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
                },
            )
        }

        composable(Routes.NOVA_VENDA) {
            NovaVendaScreen(
                onFinished = { navController.popBackStack(Routes.HOME, inclusive = false) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.MESAS) {
            MesasScreen(
                onOpenTab = { tabId -> navController.navigate(Routes.comanda(tabId)) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.BUSCAR_COMANDA) {
            BuscarComandaScreen(
                onOpenTab = { tabId -> navController.navigate(Routes.comanda(tabId)) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.HISTORICO) {
            HistoricoScreen(
                onOpenTab = { tabId -> navController.navigate(Routes.comanda(tabId)) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.COMANDA, arguments = listOf(tabIdArg)) { entry ->
            val tabId = entry.arguments!!.getLong("tabId")
            ComandaScreen(
                tabId = tabId,
                onAddProducts = { navController.navigate(Routes.cardapio(tabId)) },
                onCheckout = { navController.navigate(Routes.checkout(tabId)) },
                onBack = { navController.popBackStack(Routes.HOME, inclusive = false) },
            )
        }

        // O tabId da rota chega ao CardapioViewModel pelo SavedStateHandle —
        // é ele que decide entre "lançar na comanda" e "montar carrinho de
        // balcão" (rota NOVA_VENDA, que não tem tabId).
        composable(Routes.CARDAPIO, arguments = listOf(tabIdArg)) {
            CardapioScreen(
                title = "Adicionar itens",
                confirmLabel = "Enviar pedido",
                onDone = { navController.popBackStack() },
            )
        }

        composable(Routes.CHECKOUT, arguments = listOf(tabIdArg)) { entry ->
            val tabId = entry.arguments!!.getLong("tabId")
            CheckoutScreen(
                tabId = tabId,
                onTabClosed = { navController.popBackStack(Routes.HOME, inclusive = false) },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
